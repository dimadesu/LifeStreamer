package com.dimadesu.lifestreamer.audio

import kotlin.math.log10

/**
 * Represents audio level measurements.
 * 
 * @param rms Root Mean Square level (0.0 to 1.0 linear scale)
 * @param peak Peak level (0.0 to 1.0 linear scale)
 */
data class AudioLevel(
    val rms: Float = 0f,
    val peak: Float = 0f
) {
    /**
     * RMS level in decibels (dB).
     * Returns -100 for silence, 0 for maximum level.
     */
    val rmsDb: Float
        get() = if (rms > 0.0001f) 20 * log10(rms) else -100f
    
    /**
     * Peak level in decibels (dB).
     * Returns -100 for silence, 0 for maximum level.
     */
    val peakDb: Float
        get() = if (peak > 0.0001f) 20 * log10(peak) else -100f
    
    /**
     * Returns a normalized level suitable for UI display (0.0 to 1.0).
     * Maps -60dB to 0.0 and 0dB to 1.0.
     */
    val normalizedLevel: Float
        get() {
            val db = rmsDb.coerceIn(-60f, 0f)
            return (db + 60f) / 60f
        }
    
    /**
     * Returns true if audio is clipping (peak at or near maximum).
     */
    val isClipping: Boolean
        get() = peak > 0.99f
    
    companion object {
        val SILENT = AudioLevel(0f, 0f)
    }
}
