package com.dimadesu.lifestreamer.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.utils.dataStore
import kotlinx.coroutines.flow.first
import java.util.UUID
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
class BluetoothAudioSource(
    private val context: Context,
    private val preferredDevice: AudioDeviceInfo?,
    private val audioSourceType: Int = MediaRecorder.AudioSource.DEFAULT,
    private val effects: Set<UUID> = emptySet()
) :
    IAudioSourceInternal, IAudioFrameSourceInternal, SuspendStreamable, SuspendConfigurable<AudioSourceConfig>, Releasable {

    companion object {
        private const val TAG = "BluetoothAudioSource"
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private var currentConfig: AudioSourceConfig? = null
    private var scoStarted = false
    
    // Audio effects
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

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

Log.i(TAG, "Creating AudioRecord with audioSourceType=$audioSourceType, effects=$effects")
                
                val builder = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioSource(audioSourceType)

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
                audioSourceType,
                config.sampleRate,
                config.channelConfig,
                config.byteFormat,
                bufferSize
            )
        }

        // Apply audio effects
        audioRecord?.let { record ->
            val audioSessionId = record.audioSessionId
            Log.i(TAG, "Applying audio effects to session $audioSessionId")
            
            if (effects.contains(NoiseSuppressor.EFFECT_TYPE_NS) && NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                    noiseSuppressor?.enabled = true
                    Log.i(TAG, "NoiseSuppressor enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create NoiseSuppressor: ${e.message}")
                }
            }
            
            if (effects.contains(AcousticEchoCanceler.EFFECT_TYPE_AEC) && AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId)
                    echoCanceler?.enabled = true
                    Log.i(TAG, "AcousticEchoCanceler enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create AcousticEchoCanceler: ${e.message}")
                }
            }
            
            if (effects.contains(AutomaticGainControl.EFFECT_TYPE_AGC) && AutomaticGainControl.isAvailable()) {
                try {
                    gainControl = AutomaticGainControl.create(audioSessionId)
                    gainControl?.enabled = true
                    Log.i(TAG, "AutomaticGainControl enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create AutomaticGainControl: ${e.message}")
                }
            }
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
                // Do not change AudioManager.mode here; keep MODE_NORMAL to avoid residual routing issues

                // Start SCO - use modern API on Android S+, legacy on older versions
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // On Android S+, use setCommunicationDevice()
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            val result = audioManager.setCommunicationDevice(device)
                            android.util.Log.i("BluetoothAudioSource", "setCommunicationDevice result=$result for device=${device.productName}")
                            scoStarted = result
                        } else {
                            android.util.Log.w("BluetoothAudioSource", "BLUETOOTH_CONNECT permission not granted; cannot set communication device on S+")
                        }
                    } else {
                        // On older versions, use deprecated startBluetoothSco()
                        android.util.Log.i("BluetoothAudioSource", "Calling startBluetoothSco() (pre-S)")
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        scoStarted = true
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("BluetoothAudioSource", "Failed to start SCO: ${e.message}")
                }
            }
        } catch (_: Throwable) {}
        
        // If we started SCO on pre-S, wait for it to become connected before starting recording.
        if (scoStarted && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val connected = waitForScoConnected(context, 3000)
            if (!connected) {
                // SCO didn't connect in time; still try to record but log.
                try { android.util.Log.w("BluetoothAudioSource", "SCO did not connect before timeout") } catch (_: Throwable) {}
            }
        }

        ar.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    /**
     * Wait for SCO to become connected (legacy API only, pre-Android S).
     * Uses deprecated isBluetoothScoOn property which is only used on older Android versions.
     */
    @Suppress("DEPRECATION")
    private suspend fun waitForScoConnected(context: Context, timeoutMs: Long): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (audioManager.isBluetoothScoOn) return true
            } catch (_: Throwable) {}
            kotlinx.coroutines.delay(200)
        }
        return false
    }

    override suspend fun stopStream() {
        val ar = audioRecord ?: return
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
        ar.stop()
        _isStreamingFlow.tryEmit(false)

        // Stop SCO if we started it
        stopScoIfNeeded()
    }

    override fun release() {
        _isStreamingFlow.tryEmit(false)
        
        // Release audio effects
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing NoiseSuppressor: ${e.message}")
        }
        try {
            echoCanceler?.release()
            echoCanceler = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AcousticEchoCanceler: ${e.message}")
        }
        try {
            gainControl?.release()
            gainControl = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AutomaticGainControl: ${e.message}")
        }
        
        audioRecord?.release()
        audioRecord = null
        currentConfig = null
        // Ensure SCO stopped on release
        stopScoIfNeeded()
    }

    /**
     * Stop SCO/communication device if we started it.
     * Uses modern API on Android S+, legacy on older versions.
     */
    private fun stopScoIfNeeded() {
        if (!scoStarted) return
        
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // On Android S+, use clearCommunicationDevice()
                audioManager.clearCommunicationDevice()
                android.util.Log.i("BluetoothAudioSource", "clearCommunicationDevice called")
            } else {
                // On older versions, use deprecated stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                android.util.Log.i("BluetoothAudioSource", "stopBluetoothSco called (pre-S)")
            }
            try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
            scoStarted = false
        } catch (e: Throwable) {
            android.util.Log.w("BluetoothAudioSource", "Failed to stop SCO: ${e.message}")
        }
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
 * Factory for `BluetoothAudioSource` that matches `IAudioSourceInternal.Factory`.
 * Reads audio source type and effects from DataStore settings.
 */
class BluetoothAudioSourceFactory(private val device: AudioDeviceInfo?) : IAudioSourceInternal.Factory {
    
    companion object {
        private const val TAG = "BluetoothAudioSourceFactory"
    }
    
    override suspend fun create(context: Context): IAudioSourceInternal {
        // Read settings from DataStore
        val dataStoreRepository = DataStoreRepository(context, context.dataStore)
        
        val audioSourceType = dataStoreRepository.audioSourceTypeFlow.first()
        val enableNs = dataStoreRepository.audioEffectNsFlow.first()
        val enableAec = dataStoreRepository.audioEffectAecFlow.first()
        val enableAgc = dataStoreRepository.audioEffectAgcFlow.first()
        
        // Build effects set based on settings
        val effects = mutableSetOf<UUID>()
        if (enableNs) {
            effects.add(NoiseSuppressor.EFFECT_TYPE_NS)
        }
        if (enableAec) {
            effects.add(AcousticEchoCanceler.EFFECT_TYPE_AEC)
        }
        if (enableAgc) {
            effects.add(AutomaticGainControl.EFFECT_TYPE_AGC)
        }
        
        Log.i(TAG, "Creating BluetoothAudioSource with audioSourceType=$audioSourceType, effects: NS=$enableNs, AEC=$enableAec, AGC=$enableAgc")
        
        return BluetoothAudioSource(context.applicationContext, device, audioSourceType, effects)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is BluetoothAudioSource
    }
}
