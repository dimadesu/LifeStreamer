package com.dimadesu.lifestreamer.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioFrameSourceInternal
import io.github.thibaultbee.streampack.core.elements.interfaces.SuspendConfigurable
import io.github.thibaultbee.streampack.core.elements.interfaces.SuspendStreamable
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * App-level Bluetooth-backed audio source that implements the StreamPack public/internal
 * interfaces without depending on StreamPack internal sealed classes.
 *
 * This implementation intentionally mirrors the minimal behavior of `AudioRecordSource` so it
 * can be used by the app as a drop-in replacement. It prefers the provided `AudioDeviceInfo`
 * when building `AudioRecord` by using reflection to call `setPreferredDevice` on the builder
 * when supported.
 */
class AppBluetoothSource(private val context: Context, private val preferredDevice: AudioDeviceInfo?) :
    IAudioSourceInternal, IAudioFrameSourceInternal, SuspendStreamable, SuspendConfigurable<AudioSourceConfig>, Releasable {

    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private var currentConfig: AudioSourceConfig? = null
    private var scoStarted = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configure(config: AudioSourceConfig) {
        // Release existing if configured
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Audio source is already running")
            } else {
                release()
            }
        }

        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        ).also { if (it <= 0) throw IllegalArgumentException("Invalid buffer size: $it") }

        currentConfig = config

        audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(config.byteFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            val builder = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioSource(MediaRecorder.AudioSource.DEFAULT)

            // Try to prefer the Bluetooth device reflectively
            try {
                if (preferredDevice != null) {
                    val m = try {
                        builder::class.java.getMethod("setPreferredDevice", AudioDeviceInfo::class.java)
                    } catch (_: NoSuchMethodException) {
                        null
                    }
                    m?.invoke(builder, preferredDevice)
                }
            } catch (_: Throwable) {
            }

            builder.build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                config.sampleRate,
                config.channelConfig,
                config.byteFormat,
                bufferSize
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            throw IllegalArgumentException("Failed to initialize AudioRecord with config: $config")
        }
    }

    override suspend fun startStream() {
        val ar = requireNotNull(audioRecord) { "Audio source is not configured" }
        if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        // If a Bluetooth SCO device is preferred, attempt to start SCO and route audio.
        try {
            preferredDevice?.let { device ->
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                // Set communication mode
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                } catch (_: Throwable) {}

                // Start SCO if available
                try {
                    // On Android S+, ensure BLUETOOTH_CONNECT permission; guard calls if missing
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            android.util.Log.i("AppBluetoothSource", "Calling startBluetoothSco() (with BLUETOOTH_CONNECT granted)")
                            audioManager.startBluetoothSco()
                            scoStarted = true
                        } else {
                            android.util.Log.w("AppBluetoothSource", "BLUETOOTH_CONNECT permission not granted; cannot start SCO on S+")
                        }
                    } else {
                        android.util.Log.i("AppBluetoothSource", "Calling startBluetoothSco() (pre-S)")
                        audioManager.startBluetoothSco()
                        scoStarted = true
                    }
                } catch (_: Throwable) {}

                // Try to set communication device reflectively (if available)
                try {
                    val m = audioManager::class.java.getMethod("setCommunicationDevice", AudioDeviceInfo::class.java)
                    android.util.Log.i("AppBluetoothSource", "Invoking AudioManager.setCommunicationDevice via reflection")
                    m.invoke(audioManager, device)
                    android.util.Log.i("AppBluetoothSource", "setCommunicationDevice invoked")
                } catch (t: Throwable) {
                    android.util.Log.w("AppBluetoothSource", "setCommunicationDevice reflection failed: ${t.message}")
                }
            }
        } catch (_: Throwable) {}
        // If we started SCO, wait for it to become connected before starting recording.
        if (scoStarted) {
            val connected = waitForScoConnected(context, 3000)
            if (!connected) {
                // SCO didn't connect in time; still try to record but log.
                try { android.util.Log.w("AppBluetoothSource", "SCO did not connect before timeout") } catch (_: Throwable) {}
            }
        }

        ar.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    private suspend fun waitForScoConnected(context: Context, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                        intent?.let { i ->
                            if (i.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                                val state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                                    if (cont.isActive) cont.resume(true)
                                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                                    // ignore; wait for connected or timeout
                                }
                            }
                        }
                    }
                }

                val filter = android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                context.registerReceiver(receiver, filter)

                cont.invokeOnCancellation {
                    try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
                }
            }
        } != null
    }

    override suspend fun stopStream() {
        val ar = audioRecord ?: return
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
        ar.stop()
        _isStreamingFlow.tryEmit(false)

        // Stop SCO if we started it
        try {
            if (scoStarted) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
                scoStarted = false
            }
        } catch (_: Throwable) {}
    }

    override fun release() {
        _isStreamingFlow.tryEmit(false)
        audioRecord?.release()
        audioRecord = null
        currentConfig = null
        // Ensure SCO stopped on release
        try {
            if (scoStarted) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
                scoStarted = false
            }
        } catch (_: Throwable) {}
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val ar = requireNotNull(audioRecord) { "Audio source is not initialized" }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Audio source is not recording")
        }

        val buffer = frame.rawBuffer
        val length = ar.read(buffer, buffer.remaining())
        if (length > 0) {
            frame.timestampInUs = System.nanoTime() / 1000L
            return frame
        } else {
            frame.close()
            throw IllegalArgumentException("AudioRecord read error: $length")
        }
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val cfg = requireNotNull(currentConfig) { "Audio source is not configured" }
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }

    // Effects: no-op in app-level implementation. Return false to indicate effect not applied.
    fun addEffect(effectType: java.util.UUID): Boolean = false

    fun removeEffect(effectType: java.util.UUID) {}
}

/**
 * Factory for `AppBluetoothSource` that matches `IAudioSourceInternal.Factory`.
 */
class AppBluetoothSourceFactory(private val device: AudioDeviceInfo?) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return AppBluetoothSource(context.applicationContext, device)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is AppBluetoothSource
    }
}
