package com.dimadesu.lifestreamer.rtmp.audio

import android.media.AudioFormat
import io.github.thibaultbee.streampack.core.logger.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Audio format converter similar to Moblin's AVAudioConverter.
 * Handles sample rate conversion and channel count conversion for PCM16 audio.
 * 
 * This is a simple linear interpolation resampler - not as sophisticated as
 * iOS's AVAudioConverter, but sufficient for common conversions (44.1kHz -> 48kHz, mono -> stereo, etc.)
 */
class AudioConverter(
    private val inputSampleRate: Int,
    private val inputChannels: Int,
    private val outputSampleRate: Int,
    private val outputChannels: Int
) {
    companion object {
        private const val TAG = "AudioConverter"
    }

    private val needsConversion = (inputSampleRate != outputSampleRate) || (inputChannels != outputChannels)
    
    init {
        if (needsConversion) {
            Logger.i(TAG, "Audio converter initialized: $inputSampleRate Hz $inputChannels ch -> $outputSampleRate Hz $outputChannels ch")
        } else {
            Logger.d(TAG, "Audio converter initialized but no conversion needed (formats match)")
        }
    }

    /**
     * Check if conversion is needed
     */
    fun isConversionNeeded(): Boolean = needsConversion

    /**
     * Calculate the output buffer size needed for a given input size
     */
    fun getOutputBufferSize(inputBufferSize: Int): Int {
        if (!needsConversion) return inputBufferSize
        
        val inputFrames = inputBufferSize / (inputChannels * 2) // 2 bytes per sample (PCM16)
        val outputFrames = (inputFrames.toDouble() * outputSampleRate / inputSampleRate).roundToInt()
        return outputFrames * outputChannels * 2
    }

    /**
     * Convert audio from input format to output format.
     * Returns converted audio data or original data if no conversion needed.
     */
    fun convert(inputData: ByteArray): ByteArray {
        if (!needsConversion) {
            return inputData
        }

        // Convert byte array to PCM16 samples
        val inputSamples = bytesToPcm16(inputData)
        
        // Step 1: Convert channels if needed
        val channelConverted = if (inputChannels != outputChannels) {
            convertChannels(inputSamples, inputChannels, outputChannels)
        } else {
            inputSamples
        }
        
        // Step 2: Resample if needed
        val resampled = if (inputSampleRate != outputSampleRate) {
            resample(channelConverted, outputChannels, inputSampleRate, outputSampleRate)
        } else {
            channelConverted
        }
        
        // Convert back to bytes
        return pcm16ToBytes(resampled)
    }

    /**
     * Convert byte array to array of PCM16 samples (Int16 values)
     */
    private fun bytesToPcm16(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) {
            samples[i] = buffer.short
        }
        return samples
    }

    /**
     * Convert PCM16 samples back to byte array
     */
    private fun pcm16ToBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    /**
     * Convert between different channel counts.
     * Mono -> Stereo: Duplicate the channel
     * Stereo -> Mono: Average the channels
     */
    private fun convertChannels(samples: ShortArray, fromChannels: Int, toChannels: Int): ShortArray {
        if (fromChannels == toChannels) return samples
        
        return when {
            fromChannels == 1 && toChannels == 2 -> {
                // Mono to Stereo: duplicate each sample
                ShortArray(samples.size * 2) { i ->
                    samples[i / 2]
                }
            }
            fromChannels == 2 && toChannels == 1 -> {
                // Stereo to Mono: average each pair of samples
                ShortArray(samples.size / 2) { i ->
                    val left = samples[i * 2].toInt()
                    val right = samples[i * 2 + 1].toInt()
                    ((left + right) / 2).toShort()
                }
            }
            else -> {
                Logger.w(TAG, "Unsupported channel conversion: $fromChannels -> $toChannels")
                samples
            }
        }
    }

    /**
     * Resample audio using linear interpolation.
     * This is a simple but effective method for common conversions like 44.1kHz -> 48kHz.
     * 
     * Note: samples array is interleaved (e.g., for stereo: [L, R, L, R, ...])
     */
    private fun resample(samples: ShortArray, channels: Int, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return samples
        
        val inputFrames = samples.size / channels
        val outputFrames = (inputFrames.toDouble() * toRate / fromRate).roundToInt()
        val outputSamples = ShortArray(outputFrames * channels)
        
        val ratio = inputFrames.toDouble() / outputFrames
        
        for (outFrame in 0 until outputFrames) {
            val inFrameFloat = outFrame * ratio
            val inFrame = inFrameFloat.toInt()
            val fraction = inFrameFloat - inFrame
            
            // Linear interpolation between current and next frame
            for (ch in 0 until channels) {
                val sample1 = samples[inFrame * channels + ch].toInt()
                
                // If we're at the last frame, just use it without interpolation
                val sample2 = if (inFrame + 1 < inputFrames) {
                    samples[(inFrame + 1) * channels + ch].toInt()
                } else {
                    sample1
                }
                
                val interpolated = sample1 + ((sample2 - sample1) * fraction).roundToInt()
                outputSamples[outFrame * channels + ch] = interpolated.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        
        return outputSamples
    }
}
