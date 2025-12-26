package com.dimadesu.lifestreamer.models

import android.media.AudioFormat

/**
 * Data class holding current audio configuration for debug display
 */
data class AudioDebugInfo(
    val audioSource: String, // e.g., "DEFAULT", "UNPROCESSED", "VOICE_COMMUNICATION"
    val actualSystemSource: String?, // What Android HAL is actually using (from AudioManager)
    val sampleRate: Int,      // e.g., 44100, 48000
    val bitFormat: Int,       // e.g., AudioFormat.ENCODING_PCM_16BIT
    val channelConfig: Int,   // e.g., AudioFormat.CHANNEL_IN_STEREO
    val bitrate: Int,         // encoder bitrate in bps
    val noiseSuppression: Boolean,    // NS enabled
    val acousticEchoCanceler: Boolean, // AEC enabled
    val automaticGainControl: Boolean  // AGC enabled
) {
    /**
     * Returns human-readable bit format string
     */
    fun getBitFormatString(): String = when (bitFormat) {
        AudioFormat.ENCODING_PCM_8BIT -> "8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> "Unknown ($bitFormat)"
    }
    
    /**
     * Returns human-readable channel configuration string
     */
    fun getChannelConfigString(): String = when (channelConfig) {
        AudioFormat.CHANNEL_IN_MONO -> "Mono"
        AudioFormat.CHANNEL_IN_STEREO -> "Stereo"
        else -> "Unknown ($channelConfig)"
    }
    
    /**
     * Returns sample rate in kHz
     */
    fun getSampleRateKHz(): String = "${"%.1f".format(sampleRate / 1000.0)} kHz"
    
    /**
     * Returns bitrate in kbps
     */
    fun getBitrateKbps(): String = "${bitrate / 1000} kbps"
    
    /**
     * Returns formatted audio effects status
     */
    fun getAudioEffectsString(): String {
        val effects = mutableListOf<String>()
        if (noiseSuppression) effects.add("NS")
        if (acousticEchoCanceler) effects.add("AEC")
        if (automaticGainControl) effects.add("AGC")
        return if (effects.isEmpty()) "None" else effects.joinToString(", ")
    }
    
    /**
     * Returns actual system source or "None" if null
     */
    fun getActualSystemSourceDisplay(): String = actualSystemSource ?: "None"
}
