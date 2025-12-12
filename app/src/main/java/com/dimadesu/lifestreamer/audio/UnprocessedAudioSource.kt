package com.dimadesu.lifestreamer.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
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
 * Audio source that uses UNPROCESSED mode for raw audio capture.
 * 
 * This is intended for USB audio devices where:
 * - The device has its own high-quality preamp
 * - System DSP processing (noise cancellation, echo suppression) is not desired
 * - Raw, unprocessed audio is preferred
 * 
 * For built-in microphones, use StreamPack's MicrophoneSource instead,
 * which uses DEFAULT and benefits from system audio processing.
 */
class UnprocessedAudioSource(private val context: Context) : IAudioSourceInternal {
    
    companion object {
        private const val TAG = "UnprocessedAudioSource"
        
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
        
        Log.i(TAG, "Configured with UNPROCESSED audio source (raw USB audio capture)")
    }
    
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(config.byteFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                config.sampleRate,
                config.channelConfig,
                config.byteFormat,
                bufferSize
            )
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
 * Factory to create an [UnprocessedAudioSource].
 * 
 * Use this when you specifically want UNPROCESSED audio capture,
 * typically for USB audio devices.
 */
class UnprocessedAudioSourceFactory : IAudioSourceInternal.Factory {
    
    override suspend fun create(context: Context): IAudioSourceInternal {
        return UnprocessedAudioSource(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is UnprocessedAudioSource
    }

    override fun toString(): String {
        return "UnprocessedAudioSourceFactory()"
    }
}
