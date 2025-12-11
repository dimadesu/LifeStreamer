package com.dimadesu.lifestreamer.audio

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Smart microphone source that automatically selects the optimal audio source type
 * based on connected audio devices:
 * - USB audio device connected → UNPROCESSED (raw audio, no DSP processing)
 * - Built-in microphone → DEFAULT (allows system noise cancellation, etc.)
 * 
 * This provides the best audio quality for each scenario:
 * - USB audio interfaces typically have their own high-quality preamps and don't need processing
 * - Built-in phone mics benefit from noise cancellation and echo suppression
 */
class SmartMicrophoneSource(private val context: Context) : IAudioSourceInternal {
    
    companion object {
        private const val TAG = "SmartMicrophoneSource"
        
        private fun audioRecordErrorToString(error: Int): String {
            return when (error) {
                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                AudioRecord.ERROR -> "ERROR"
                else -> "UNKNOWN ($error)"
            }
        }
    }
    
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int? = null
    
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()
    
    private val audioTimestamp = AudioTimestamp()
    
    private val isRunning: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configure(config: AudioSourceConfig) {
        // Release any existing AudioRecord
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Audio source is already running")
            } else {
                release()
            }
        }
        
        bufferSize = getMinBufferSize(config)
        audioRecord = buildAudioRecord(config, bufferSize!!)
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalArgumentException("Failed to initialize audio source with config: $config")
        }
    }
    
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        val hasUsbAudio = hasUsbAudioInput()
        val audioSourceType = if (hasUsbAudio) {
            Log.i(TAG, "USB audio input detected - using UNPROCESSED audio source for raw capture")
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            Log.i(TAG, "No USB audio input - using DEFAULT audio source (with system processing)")
            MediaRecorder.AudioSource.DEFAULT
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(config.byteFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioSource(audioSourceType)
                .build()
        } else {
            AudioRecord(
                audioSourceType,
                config.sampleRate,
                config.channelConfig,
                config.byteFormat,
                bufferSize
            )
        }
    }
    
    /**
     * Check if a USB audio input device is currently connected.
     */
    private fun hasUsbAudioInput(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
            
            val usbDevice = inputDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            
            if (usbDevice != null) {
                Log.d(TAG, "Found USB audio input: ${usbDevice.productName ?: "Unknown"} (type=${usbDevice.type})")
            }
            
            usbDevice != null
        } catch (e: Exception) {
            Log.w(TAG, "Error checking for USB audio: ${e.message}")
            false
        }
    }
    
    private fun getMinBufferSize(config: AudioSourceConfig): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
        require(minBufferSize > 0) { "Invalid buffer size: $minBufferSize" }
        return minBufferSize
    }

    override suspend fun startStream() {
        if (isRunning) {
            Log.d(TAG, "Already running")
            return
        }
        val record = requireNotNull(audioRecord) { "AudioRecord not configured" }
        record.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        if (!isRunning) {
            Log.d(TAG, "Not running")
            return
        }
        audioRecord?.stop()
        _isStreamingFlow.tryEmit(false)
    }

    override fun release() {
        _isStreamingFlow.tryEmit(false)
        audioRecord?.release()
        audioRecord = null
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val record = requireNotNull(audioRecord) { "AudioRecord not configured" }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Audio source is not recording")
        }
        
        val buffer = frame.rawBuffer
        val length = record.read(buffer, buffer.remaining())
        if (length > 0) {
            frame.timestampInUs = getTimestampInUs(record)
            return frame
        } else {
            frame.close()
            throw IllegalArgumentException(audioRecordErrorToString(length))
        }
    }
    
    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val size = requireNotNull(bufferSize) { "Buffer size not set" }
        // Dummy timestamp: it is overwritten in fillAudioFrame
        return fillAudioFrame(frameFactory.create(size, 0))
    }
    
    private fun getTimestampInUs(audioRecord: AudioRecord): Long {
        var timestamp: Long = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (audioRecord.getTimestamp(
                    audioTimestamp,
                    AudioTimestamp.TIMEBASE_MONOTONIC
                ) == AudioRecord.SUCCESS
            ) {
                timestamp = audioTimestamp.nanoTime / 1000 // to us
            }
        }
        
        if (timestamp < 0) {
            timestamp = System.nanoTime() / 1000
        }
        
        return timestamp
    }
}

/**
 * Factory to create a [SmartMicrophoneSource].
 * 
 * This factory creates an audio source that automatically detects USB audio
 * and selects the appropriate audio source type (UNPROCESSED for USB, DEFAULT for built-in).
 */
class SmartMicrophoneSourceFactory : IAudioSourceInternal.Factory {
    
    override suspend fun create(context: Context): IAudioSourceInternal {
        return SmartMicrophoneSource(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is SmartMicrophoneSource
    }

    override fun toString(): String {
        return "SmartMicrophoneSourceFactory()"
    }
}
