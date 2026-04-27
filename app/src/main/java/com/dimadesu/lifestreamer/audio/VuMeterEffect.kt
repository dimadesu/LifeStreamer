package com.dimadesu.lifestreamer.audio

import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.processing.audio.IConsumerAudioEffect
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A VU meter audio effect that computes RMS and peak levels per channel.
 *
 * Implements [IConsumerAudioEffect] so it runs on a background coroutine with a
 * deep-copied buffer — it does NOT block the capture thread.
 *
 * @param onLevel Called with computed audio levels for each audio frame.
 */
class VuMeterEffect(
    private val onLevel: (AudioLevel) -> Unit
) : IConsumerAudioEffect {

    /**
     * Number of audio channels (1 = mono, 2 = stereo).
     * Update this when the audio configuration changes.
     */
    @Volatile
    var channelCount: Int = 1

    override fun consume(isMuted: Boolean, data: RawFrame) {
        val buffer = data.rawBuffer
        val position = buffer.position()
        val remaining = buffer.limit() - position

        if (remaining < 2) {
            onLevel(AudioLevel.SILENT)
            return
        }

        val readBuffer = buffer.duplicate()
        readBuffer.position(position)
        readBuffer.order(ByteOrder.LITTLE_ENDIAN)

        var maxSampleLeft = 0
        var maxSampleRight = 0
        var sumSquaresLeft = 0.0
        var sumSquaresRight = 0.0
        var sampleCountLeft = 0
        var sampleCountRight = 0

        val isStereo = channelCount >= 2
        var isLeftChannel = true

        while (readBuffer.remaining() >= 2) {
            val sample = readBuffer.short.toInt()
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

        val peakLeft = if (sampleCountLeft > 0) (maxSampleLeft / 32767f).coerceIn(0f, 1f) else 0f
        val rmsLeft = if (sampleCountLeft > 0)
            (sqrt(sumSquaresLeft / sampleCountLeft) / 32767.0).toFloat().coerceIn(0f, 1f) else 0f

        val peakRight = if (sampleCountRight > 0) (maxSampleRight / 32767f).coerceIn(0f, 1f) else 0f
        val rmsRight = if (sampleCountRight > 0)
            (sqrt(sumSquaresRight / sampleCountRight) / 32767.0).toFloat().coerceIn(0f, 1f) else 0f

        onLevel(
            AudioLevel(
                rms = rmsLeft,
                peak = peakLeft,
                rmsRight = rmsRight,
                peakRight = peakRight,
                isStereo = isStereo
            )
        )
    }

    override fun close() {
        // nothing to release
    }
}
