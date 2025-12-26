package com.dimadesu.lifestreamer.models

import android.media.AudioFormat

/**
 * Data class holding current audio configuration for debug display
 */
data class AudioDebugInfo(
    val audioSource: String, // e.g., "DEFAULT", "UNPROCESSED", "VOICE_COMMUNICATION"
    val actualSystemSource: String?, // What Android HAL is actually using (from AudioManager)
    val sampleRate: Int?,      // e.g., 44100, 48000 (null if no active recording)
    val bitFormat: Int?,       // e.g., AudioFormat.ENCODING_PCM_16BIT (null if no active recording)
    val channelConfig: Int?,   // e.g., AudioFormat.CHANNEL_IN_STEREO (null if no active recording)
    val bitrate: Int,         // encoder bitrate in bps
    val noiseSuppression: Boolean?,    // NS enabled (null if no active recording)
    val acousticEchoCanceler: Boolean?, // AEC enabled (null if no active recording)
    val automaticGainControl: Boolean?,  // AGC enabled (null if no active recording)
    val hasActiveRecording: Boolean = false // Whether we have actual system data
) {
    /**
     * Returns human-readable bit format string or N/A if no active recording
     */
    fun getBitFormatString(): String = if (bitFormat == null) "N/A" else when (bitFormat) {
        AudioFormat.ENCODING_PCM_8BIT -> "8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> "Unknown ($bitFormat)"
    }
    
    /**
     * Returns human-readable channel configuration string or N/A if no active recording
     */
    fun getChannelConfigString(): String = if (channelConfig == null) "N/A" else when (channelConfig) {
        AudioFormat.CHANNEL_IN_MONO -> "Mono"
        AudioFormat.CHANNEL_IN_STEREO -> "Stereo"
        else -> "Unknown ($channelConfig)"
    }
    
    /**
     * Returns sample rate in kHz or N/A if no active recording
     */
    fun getSampleRateKHz(): String = if (sampleRate == null) "N/A" else "${"%.1f".format(sampleRate / 1000.0)} kHz"
    
    /**
     * Returns bitrate in kbps
     */
    fun getBitrateKbps(): String = "${bitrate / 1000} kbps"
    
    /**
     * Returns formatted audio effects status or N/A if no active recording
     */
    fun getAudioEffectsString(): String {
        if (!hasActiveRecording) return "N/A"
        val effects = mutableListOf<String>()
        if (noiseSuppression == true) effects.add("NS")
        if (acousticEchoCanceler == true) effects.add("AEC")
        if (automaticGainControl == true) effects.add("AGC")
        return if (effects.isEmpty()) "None" else effects.joinToString(", ")
    }
    
    /**
     * Returns actual system source or "N/A" if no active recording
     */
    fun getActualSystemSourceDisplay(): String = if (hasActiveRecording) (actualSystemSource ?: "Unknown") else "N/A"
}
