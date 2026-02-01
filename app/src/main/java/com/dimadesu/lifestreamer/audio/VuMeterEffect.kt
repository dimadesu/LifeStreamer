package com.dimadesu.lifestreamer.audio

import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.processing.audio.IConsumerAudioEffect
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * An audio effect that calculates VU meter levels from audio frames.
 * 
 * This effect implements [IConsumerAudioEffect], meaning it runs on a separate coroutine
 * and doesn't block the audio pipeline. It receives a copy of the audio buffer.
 * 
 * @param channelCount Number of audio channels (1 for mono, 2 for stereo)
 * @param onLevelUpdate Callback invoked with calculated audio levels
 */
class VuMeterEffect(
    var channelCount: Int = 1,
    private val onLevelUpdate: (AudioLevel) -> Unit
) : IConsumerAudioEffect {

    /**
     * Process the audio frame and calculate levels.
     * This is called on a separate coroutine, so it won't block the audio pipeline.
     */
    override fun consume(isMuted: Boolean, data: RawFrame) {
        if (isMuted) {
            // When muted, report silence
            onLevelUpdate(AudioLevel.SILENT)
            return
        }
        
        val levels = calculateAudioLevels(data, channelCount)
        onLevelUpdate(levels)
    }

    override fun close() {
        // Nothing to clean up
    }

    /**
     * Calculate RMS and peak audio levels from 16-bit PCM audio buffer.
     * Supports mono (1 channel) and stereo (2 channels, interleaved L-R-L-R).
     * 
     * @param frame RawFrame containing 16-bit PCM audio samples
     * @param channels Number of audio channels (1 or 2)
     * @return AudioLevel with per-channel RMS and peak values
     */
    private fun calculateAudioLevels(frame: RawFrame, channels: Int): AudioLevel {
        val buffer = frame.rawBuffer
        val remaining = buffer.remaining()
        
        if (remaining < 2) {
            return AudioLevel.SILENT
        }
        
        // The buffer is already a copy provided by AudioFrameProcessor,
        // so we can read directly from it. Just set byte order.
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        var maxSampleLeft = 0
        var maxSampleRight = 0
        var sumSquaresLeft = 0.0
        var sumSquaresRight = 0.0
        var sampleCountLeft = 0
        var sampleCountRight = 0
        
        val isStereo = channels >= 2
        var isLeftChannel = true
        
        while (buffer.remaining() >= 2) {
            val sample = buffer.short.toInt()
            val absSample = abs(sample)
            val sampleSquared = (sample.toLong() * sample.toLong()).toDouble()
            
            if (!isStereo || isLeftChannel) {
                if (absSample > maxSampleLeft) maxSampleLeft = absSample
                sumSquaresLeft += sampleSquared
                sampleCountLeft++
            } else {
                if (absSample > maxSampleRight) maxSampleRight = absSample
                sumSquaresRight += sampleSquared
                sampleCountRight++
            }
            
            if (isStereo) isLeftChannel = !isLeftChannel
        }
        
        // Normalize to 0.0-1.0 range (32767 is max for 16-bit signed)
        val peakLeft = if (sampleCountLeft > 0) (maxSampleLeft / 32767f).coerceIn(0f, 1f) else 0f
        val rmsLeft = if (sampleCountLeft > 0) (sqrt(sumSquaresLeft / sampleCountLeft) / 32767.0).toFloat().coerceIn(0f, 1f) else 0f
        
        val peakRight = if (sampleCountRight > 0) (maxSampleRight / 32767f).coerceIn(0f, 1f) else 0f
        val rmsRight = if (sampleCountRight > 0) (sqrt(sumSquaresRight / sampleCountRight) / 32767.0).toFloat().coerceIn(0f, 1f) else 0f
        
        return AudioLevel(
            rms = rmsLeft,
            peak = peakLeft,
            rmsRight = rmsRight,
            peakRight = peakRight,
            isStereo = isStereo
        )
    }
}
