package com.dimadesu.lifestreamer.bitrate

/**
 * Configuration for Moblin SrtFight adaptive bitrate algorithm.
 * Values match exactly with Moblin's Swift implementation.
 */
data class MoblinSrtFightConfig(
    val fastSettings: MoblinSrtFightSettings = MoblinSrtFightSettings(
        packetsInFlight = 200L,
        rttDiffHighFactor = 0.9,
        rttDiffHighAllowedSpike = 50.0,
        rttDiffHighMinDecrease = 250_000L,
        pifDiffIncreaseFactor = 100_000L,
        minimumBitrate = 50_000L
    ),
    val slowSettings: MoblinSrtFightSettings = MoblinSrtFightSettings(
        packetsInFlight = 500L,
        rttDiffHighFactor = 0.95,
        rttDiffHighAllowedSpike = 100.0,
        rttDiffHighMinDecrease = 100_000L,
        pifDiffIncreaseFactor = 25_000L,
        minimumBitrate = 50_000L
    )
)

/**
 * Settings for Moblin SrtFight algorithm - matches AdaptiveBitrateSettings from Moblin
 */
data class MoblinSrtFightSettings(
    /**
     * Packets in flight threshold - key metric for SrtFight algorithm
     * Fast: 200, Slow: 500
     */
    val packetsInFlight: Long,
    
    /**
     * Factor for RTT diff high decrease (0.9 = 10% decrease, 0.95 = 5% decrease)
     * Fast: 0.9 (more aggressive), Slow: 0.95 (more conservative)
     */
    val rttDiffHighFactor: Double,
    
    /**
     * Allowed RTT spike in milliseconds before triggering decrease
     * Fast: 50ms, Slow: 100ms
     */
    val rttDiffHighAllowedSpike: Double,
    
    /**
     * Minimum decrease amount in bits per second for RTT diff high
     * Fast: 250k (more aggressive), Slow: 100k (more conservative)
     */
    val rttDiffHighMinDecrease: Long,
    
    /**
     * Increase factor based on PIF difference
     * Fast: 100k (faster recovery), Slow: 25k (slower recovery)
     */
    val pifDiffIncreaseFactor: Long,
    
    /**
     * Minimum bitrate in bits per second
     * Both: 50k
     */
    val minimumBitrate: Long
)
