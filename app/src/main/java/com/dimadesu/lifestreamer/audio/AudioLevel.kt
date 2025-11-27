package com.dimadesu.lifestreamer.audio

import kotlin.math.log10

/**
 * Represents audio level measurements for one or two channels.
 * For mono, only left channel values are used (rms/peak).
 * For stereo, both left and right channel values are provided.
 * 
 * @param rms Root Mean Square level for left/mono channel (0.0 to 1.0 linear scale)
 * @param peak Peak level for left/mono channel (0.0 to 1.0 linear scale)
 * @param rmsRight Root Mean Square level for right channel (0.0 to 1.0 linear scale)
 * @param peakRight Peak level for right channel (0.0 to 1.0 linear scale)
 * @param isStereo Whether this represents stereo audio
 */
data class AudioLevel(
    val rms: Float = 0f,
    val peak: Float = 0f,
    val rmsRight: Float = 0f,
    val peakRight: Float = 0f,
    val isStereo: Boolean = false
) {
    /**
     * RMS level in decibels (dB) for left/mono channel.
     * Returns -100 for silence, 0 for maximum level.
     */
    val rmsDb: Float
        get() = if (rms > 0.0001f) 20 * log10(rms) else -100f
    
    /**
     * Peak level in decibels (dB) for left/mono channel.
     * Returns -100 for silence, 0 for maximum level.
     */
    val peakDb: Float
        get() = if (peak > 0.0001f) 20 * log10(peak) else -100f
    
    /**
     * RMS level in decibels (dB) for right channel.
     */
    val rmsDbRight: Float
        get() = if (rmsRight > 0.0001f) 20 * log10(rmsRight) else -100f
    
    /**
     * Peak level in decibels (dB) for right channel.
     */
    val peakDbRight: Float
        get() = if (peakRight > 0.0001f) 20 * log10(peakRight) else -100f
    
    /**
     * Returns a normalized level suitable for UI display (0.0 to 1.0) for left/mono channel.
     * Maps -60dB to 0.0 and 0dB to 1.0.
     */
    val normalizedLevel: Float
        get() {
            val db = rmsDb.coerceIn(-60f, 0f)
            return (db + 60f) / 60f
        }
    
    /**
     * Returns a normalized level for right channel (0.0 to 1.0).
     */
    val normalizedLevelRight: Float
        get() {
            val db = rmsDbRight.coerceIn(-60f, 0f)
            return (db + 60f) / 60f
        }
    
    /**
     * Returns true if left channel audio is clipping (peak at or near maximum).
     */
    val isClipping: Boolean
        get() = peak > 0.99f
    
    /**
     * Returns true if right channel audio is clipping.
     */
    val isClippingRight: Boolean
        get() = peakRight > 0.99f
    
    companion object {
        val SILENT = AudioLevel(0f, 0f, 0f, 0f, false)
    }
}
