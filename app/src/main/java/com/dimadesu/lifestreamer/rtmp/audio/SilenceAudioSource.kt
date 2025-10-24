package com.dimadesu.lifestreamer.rtmp.audio

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio source that generates silence (all zeros).
 * Used as a fallback when MediaProjection is not available for RTMP audio capture.
 */
class SilenceAudioSource : IAudioSourceInternal, Releasable {
    
    companion object {
        private const val TAG = "SilenceAudioSource"
    }
    
    private var config: AudioSourceConfig? = null
    private var bufferSize: Int = 0
    private var samplesPerFrame: Int = 1024
    private var lastTimestampUs: Long = 0L
    
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()
    
    override suspend fun configure(config: AudioSourceConfig) {
        this.config = config
        
        // Calculate buffer size (typical: 1024 samples * 2 bytes per sample * channels)
        val channelCount = when (config.channelConfig) {
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.CHANNEL_OUT_MONO -> 1
            else -> 2
        }
        val bytesPerSample = when (config.byteFormat) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        bufferSize = 1024 * bytesPerSample * channelCount
        
        Logger.i(TAG, "Configured silence audio source: ${config.sampleRate}Hz, $channelCount channels, buffer=$bufferSize bytes")
    }
    
    override suspend fun startStream() {
        if (_isStreamingFlow.value) {
            Logger.d(TAG, "Already streaming")
            return
        }
        
        // Initialize timestamp - will use current time like AudioRecordSource
        lastTimestampUs = 0L
        
        _isStreamingFlow.tryEmit(true)
        Logger.i(TAG, "Started silence audio stream")
    }
    
    override suspend fun stopStream() {
        if (!_isStreamingFlow.value) {
            Logger.d(TAG, "Not streaming")
            return
        }
        
        _isStreamingFlow.tryEmit(false)
        Logger.i(TAG, "Stopped silence audio stream")
    }
    
    override fun release() {
        _isStreamingFlow.tryEmit(false)
        config = null
        Logger.i(TAG, "Released silence audio source")
    }
    
    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        requireNotNull(config) { "Audio source is not configured" }
        if (!_isStreamingFlow.value) {
            frame.close()
            throw IllegalStateException("Audio source is not streaming")
        }
        
        // Generate silence (all zeros)
        val buffer = frame.rawBuffer
        val silenceData = ByteArray(bufferSize) // All zeros = silence for PCM
        buffer.put(silenceData)
        buffer.flip()
        
        // Use current time for timestamp, matching AudioRecordSource behavior
        // This mimics the fallback path in AudioRecordSource.getTimestampInUs()
        frame.timestampInUs = TimeUtils.currentTime()
        
        return frame
    }
    
    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        requireNotNull(config) { "Audio source is not configured" }
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }
}

/**
 * Factory for creating SilenceAudioSource instances.
 */
class SilenceAudioSourceFactory : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return SilenceAudioSource()
    }
    
    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is SilenceAudioSource
    }
}
