package com.dimadesu.lifestreamer.bitrate

import android.util.Log
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.ext.srt.regulator.SrtBitrateRegulator
import kotlin.math.max
import kotlin.math.min

/**
 * Moblin SrtFight adaptive bitrate algorithm implementation.
 *
 * This algorithm is based on Moblin's AdaptiveBitrateSrtFight which focuses on:
 * - Packets In Flight (PIF) monitoring with dual tracking (smooth vs fast)
 * - Dual RTT tracking (average vs fast RTT)
 * - Dynamic maximum bitrate management
 * - Aggressive response to network congestion
 * - Fast recovery when conditions improve
 *
 * @param bitrateRegulatorConfig bitrate regulation configuration
 * @param moblinConfig Moblin SrtFight-specific algorithm configuration
 * @param onVideoTargetBitrateChange call when you have to change video bitrate
 */
class MoblinSrtFightBitrateRegulator(
    bitrateRegulatorConfig: BitrateRegulatorConfig,
    private val moblinConfig: MoblinSrtFightConfig = MoblinSrtFightConfig(),
    onVideoTargetBitrateChange: ((Int) -> Unit)
) : SrtBitrateRegulator(
    bitrateRegulatorConfig,
    onVideoTargetBitrateChange,
    { /* No audio bitrate changes */ }
) {

    companion object {
        private const val TAG = "MoblinSrtFightBitrateRegulator"
        private const val ADAPTIVBITRATE_START = 1_000_000L // 1 Mbps starting bitrate
    }

    // Current bitrate state - matches Moblin's approach
    private var currentBitrate: Long = ADAPTIVBITRATE_START
    private var previousBitrate: Long = ADAPTIVBITRATE_START
    private var targetBitrate: Long = 0L
    private var currentMaximumBitrate: Long = ADAPTIVBITRATE_START

    // RTT tracking - dual approach like Moblin
    private var avgRtt: Double = 0.0
    private var fastRtt: Double = 0.0

    // PIF tracking - core of SrtFight algorithm
    private var smoothPif: Double = 0.0
    private var fastPif: Double = 0.0

    // Current settings (fast vs slow)
    private var currentSettings = moblinConfig.fastSettings

    // Action logging for debugging
    private val actionHistory = mutableListOf<String>()
    private var lastUpdateTime = 0L

    override fun update(stats: Stats, currentVideoBitrate: Int, currentAudioBitrate: Int) {
        val currentTime = System.currentTimeMillis()
        
        // Skip if called too frequently (Moblin updates every 200ms)
        if (currentTime - lastUpdateTime < 200) {
            return
        }
        lastUpdateTime = currentTime

        // Initialize target bitrate if first run
        if (targetBitrate == 0L) {
            // Use the upper limit from bitrate regulator config as target, not current bitrate
            targetBitrate = bitrateRegulatorConfig.videoBitrateRange.upper.toLong()
            currentMaximumBitrate = currentVideoBitrate.toLong() // Start from current, scale up to target
            // Log.i(TAG, "*** INITIALIZED: Target bitrate = ${targetBitrate / 1000}k (from config), Starting max = ${currentMaximumBitrate / 1000}k ***")
            // Log.i(TAG, "*** Bitrate Range: ${bitrateRegulatorConfig.videoBitrateRange.lower / 1000}k - ${bitrateRegulatorConfig.videoBitrateRange.upper / 1000}k ***")
            // Log.i(TAG, "*** Settings: ${if (currentSettings == moblinConfig.fastSettings) "FAST" else "SLOW"} - PIF=${currentSettings.packetsInFlight}, Factor=${currentSettings.pifDiffIncreaseFactor} ***")
        }

        // Skip if no valid data
        if (stats.msRTT <= 0) {
            // Log.w(TAG, "Skipping update - invalid RTT: ${stats.msRTT}")
            return
        }

        val rttMs = stats.msRTT.toDouble()
        val packetsInFlight = stats.pktFlightSize.toDouble()
        
        // Log.d(TAG, "=== SRT UPDATE ===")
        // Log.d(TAG, "RTT: ${stats.msRTT}ms, PIF: ${stats.pktFlightSize}, Send rate: ${stats.mbpsSendRate}Mbps")
        // Log.d(TAG, "Current: ${currentBitrate / 1000}k, Max: ${currentMaximumBitrate / 1000}k, Target: ${targetBitrate / 1000}k")
        // Log.d(TAG, "Smooth PIF: ${"%.1f".format(smoothPif)}, Avg RTT: ${"%.1f".format(avgRtt)}, Fast RTT: ${"%.1f".format(fastRtt)}")
        // Log.d(TAG, "Settings: PIF=${currentSettings.packetsInFlight}, Factor=${currentSettings.rttDiffHighFactor}")

        // Core algorithm steps - matches Moblin's update() method
        calcPifs(packetsInFlight)
        calcRtts(rttMs)
        increaseCurrentMaxBitrate(packetsInFlight, rttMs, allowedRttJitter = 15.0, allowedPifJitter = 10.0)
        
        // Slow decreases if needed - matches Moblin's thresholds
        decreaseMaxRateIfPifIsHigh(factor = 0.9, pifMax = 100.0, minimumDecrease = 250_000L)
        decreaseMaxRateIfRttIsHigh(factor = 0.9, rttMax = 250.0, minimumDecrease = 250_000L)
        decreaseMaxRateIfRttDiffIsHigh(
            rttMs,
            factor = currentSettings.rttDiffHighFactor,
            rttSpikeAllowed = currentSettings.rttDiffHighAllowedSpike,
            minimumDecrease = currentSettings.rttDiffHighMinDecrease
        )
        
        calculateCurrentBitrate(stats)

        // Apply video bitrate change if needed
        if (previousBitrate != currentBitrate) {
            val newVideoBitrate = currentBitrate.toInt()
            onVideoTargetBitrateChange(newVideoBitrate)
            previousBitrate = currentBitrate
            // Log.i(TAG, "*** BITRATE CHANGED: ${(previousBitrate / 1000)}k -> ${newVideoBitrate / 1000}k ***")
        } else {
            // Log.d(TAG, "Bitrate unchanged: ${currentBitrate / 1000}k")
        }
        
        // Log.d(TAG, "=== END UPDATE ===")
    }

    /**
     * Calculate Packets In Flight averages - matches Moblin's calcPifs
     */
    private fun calcPifs(packetsInFlight: Double) {
        if (packetsInFlight > smoothPif) {
            smoothPif *= 0.97
            smoothPif += packetsInFlight * 0.03
        } else {
            smoothPif *= 0.9
            smoothPif += packetsInFlight * 0.1
        }
        fastPif *= 0.67
        fastPif += packetsInFlight * 0.33
    }

    /**
     * Calculate RTT averages - matches Moblin's calcRtts
     */
    private fun calcRtts(rttMs: Double) {
        if (avgRtt < 1) {
            avgRtt = rttMs
        }
        if (avgRtt > rttMs) {
            avgRtt *= 0.60
            avgRtt += rttMs * 0.40
        } else {
            avgRtt *= 0.96
            if (rttMs < 450) {
                avgRtt += rttMs * 0.04
            } else {
                avgRtt += 450 * 0.001
            }
        }
        
        if (fastRtt > rttMs) {
            fastRtt *= 0.70
            fastRtt += rttMs * 0.30
        } else {
            fastRtt *= 0.90
            fastRtt += rttMs * 0.10
        }
        
        if (avgRtt > 450) {
            avgRtt = 450.0
        }
    }

    /**
     * Increase current max bitrate - matches Moblin's increaseCurrentMaxBitrate
     */
    private fun increaseCurrentMaxBitrate(
        packetsInFlight: Double,
        rttMs: Double,
        allowedRttJitter: Double,
        allowedPifJitter: Double
    ) {
        val oldMaxBitrate = currentMaximumBitrate
        
        var pifSpikeDiff = (packetsInFlight - smoothPif).toLong()
        if (pifSpikeDiff < 0) {
            pifSpikeDiff = 0
        }
        if (pifSpikeDiff > currentSettings.packetsInFlight) {
            pifSpikeDiff = currentSettings.packetsInFlight
        }
        
        val pifDiffThing = currentSettings.packetsInFlight - pifSpikeDiff
        
        // Log.d(TAG, "--- INCREASE CHECK ---")
        // Log.d(TAG, "PIF: ${"%.1f".format(packetsInFlight)}, Smooth PIF: ${"%.1f".format(smoothPif)}, Threshold: ${currentSettings.packetsInFlight}")
        // Log.d(TAG, "Fast RTT: ${"%.1f".format(fastRtt)}, Avg RTT: ${"%.1f".format(avgRtt)}, Jitter allowed: ${allowedRttJitter}")
        // Log.d(TAG, "PIF spike diff: ${pifSpikeDiff}, PIF diff thing: ${pifDiffThing}")
        // Log.d(TAG, "PIF - smooth PIF: ${"%.1f".format(packetsInFlight - smoothPif)}, Allowed: ${allowedPifJitter}")
        
        val pifCondition = smoothPif < currentSettings.packetsInFlight.toDouble()
        val rttCondition = fastRtt <= avgRtt + allowedRttJitter
        val jitterCondition = packetsInFlight - smoothPif < allowedPifJitter
        
        // Log.d(TAG, "Conditions - PIF OK: ${pifCondition}, RTT OK: ${rttCondition}, Jitter OK: ${jitterCondition}")
        
        if (pifCondition && rttCondition) {
            if (jitterCondition) {
                val increase = (currentSettings.pifDiffIncreaseFactor * pifDiffThing) / currentSettings.packetsInFlight
                currentMaximumBitrate += increase
                // Log.d(TAG, "INCREASING: +${increase / 1000}k (factor=${currentSettings.pifDiffIncreaseFactor})")
                
                if (currentMaximumBitrate > targetBitrate) {
                    currentMaximumBitrate = targetBitrate
                    // Log.d(TAG, "Capped at target: ${targetBitrate / 1000}k")
                }
                // Log.i(TAG, "Max bitrate increased: ${oldMaxBitrate / 1000}k -> ${currentMaximumBitrate / 1000}k")
            } else {
                // Log.d(TAG, "Not increasing - jitter too high")
            }
        } else {
            // Log.d(TAG, "Not increasing - conditions not met")
        }
    }

    /**
     * Decrease max rate if PIF is high - matches Moblin's decreaseMaxRateIfPifIsHigh
     */
    private fun decreaseMaxRateIfPifIsHigh(factor: Double, pifMax: Double, minimumDecrease: Long) {
        if (smoothPif <= pifMax) return
        
        val factorDecrease = (currentMaximumBitrate.toDouble() * (1 - factor)).toLong()
        val decrease = max(factorDecrease, minimumDecrease)
        currentMaximumBitrate -= decrease
        // logAction("PIF: Decreasing bitrate by ${decrease / 1000}k, smooth ${smoothPif.toInt()} > max ${pifMax.toInt()}")
    }

    /**
     * Decrease max rate if RTT is high - matches Moblin's decreaseMaxRateIfRttIsHigh
     */
    private fun decreaseMaxRateIfRttIsHigh(factor: Double, rttMax: Double, minimumDecrease: Long) {
        if (avgRtt <= rttMax) return
        
        val factorDecrease = (currentMaximumBitrate.toDouble() * (1 - factor)).toLong()
        val decrease = max(factorDecrease, minimumDecrease)
        currentMaximumBitrate -= decrease
        // logAction("RTT: Decrease bitrate by ${decrease / 1000}k, avg ${avgRtt.toInt()} > max ${rttMax.toInt()}")
    }

    /**
     * Decrease max rate if RTT diff is high - matches Moblin's decreaseMaxRateIfRttDiffIsHigh
     */
    private fun decreaseMaxRateIfRttDiffIsHigh(
        rttMs: Double,
        factor: Double,
        rttSpikeAllowed: Double,
        minimumDecrease: Long
    ) {
        if (rttMs <= avgRtt + rttSpikeAllowed) return
        
        val factorDecrease = (currentMaximumBitrate.toDouble() * (1 - factor)).toLong()
        val decrease = max(factorDecrease, minimumDecrease)
        currentMaximumBitrate -= decrease
        // logAction("RTT: Decreasing bitrate by ${decrease / 1000}k, ${rttMs.toInt()} > avg + allow ${(avgRtt + rttSpikeAllowed).toInt()}")
    }

    /**
     * Calculate current bitrate - matches Moblin's calculateCurrentBitrate
     */
    private fun calculateCurrentBitrate(stats: Stats) {
        val oldCurrentBitrate = currentBitrate
        val oldMaxBitrate = currentMaximumBitrate
        
        var pifSpikeDiff = (fastPif - smoothPif).toLong()
        
        // Log.d(TAG, "--- CALCULATE BITRATE ---")
        // Log.d(TAG, "Fast PIF: ${"%.1f".format(fastPif)}, Smooth PIF: ${"%.1f".format(smoothPif)}")
        // Log.d(TAG, "PIF spike diff: ${pifSpikeDiff} (threshold: ${currentSettings.packetsInFlight})")
        
        // Lazy decrease
        if (pifSpikeDiff > currentSettings.packetsInFlight) {
            // logAction("PIF: Lazy decrease diff $pifSpikeDiff > ${currentSettings.packetsInFlight}")
            currentMaximumBitrate = (currentMaximumBitrate.toDouble() * 0.95).toLong()
            // Log.d(TAG, "Lazy decrease applied: ${oldMaxBitrate / 1000}k -> ${currentMaximumBitrate / 1000}k")
        }
        
        if (pifSpikeDiff <= (currentSettings.packetsInFlight / 5)) {
            pifSpikeDiff = 0
        }
        if (pifSpikeDiff < 0) {
            pifSpikeDiff = 0
        }
        if (pifSpikeDiff > currentSettings.packetsInFlight) {
            pifSpikeDiff = currentSettings.packetsInFlight
        }
        
        // Harder decrease
        if (pifSpikeDiff == currentSettings.packetsInFlight) {
            currentMaximumBitrate -= 500_000
            // logAction("PIF: -500k dec diff $pifSpikeDiff == ${currentSettings.packetsInFlight}")
            // Log.d(TAG, "Hard decrease applied: -500k")
        }
        
        val pifDiffThing = currentSettings.packetsInFlight - pifSpikeDiff
        
        // Log.d(TAG, "PIF diff thing: ${pifDiffThing} (${currentSettings.packetsInFlight} - ${pifSpikeDiff})")
        
        val minimumBitrate = max(50000, currentSettings.minimumBitrate)
        if (currentMaximumBitrate < minimumBitrate) {
            currentMaximumBitrate = minimumBitrate
            // Log.d(TAG, "Min bitrate applied: ${minimumBitrate / 1000}k")
        }
        
        var tempBitrate = currentMaximumBitrate
        tempBitrate *= pifDiffThing
        tempBitrate /= currentSettings.packetsInFlight
        currentBitrate = tempBitrate
        
        // Log.d(TAG, "Calculated bitrate: ${currentBitrate / 1000}k (${currentMaximumBitrate / 1000}k * ${pifDiffThing} / ${currentSettings.packetsInFlight})")
        
        if (currentBitrate < currentSettings.minimumBitrate) {
            currentBitrate = currentSettings.minimumBitrate
            // Log.d(TAG, "Applied minimum: ${currentSettings.minimumBitrate / 1000}k")
        }
        
        // PIF running away - do a quick lower of bitrate temporarily
        if ((fastPif - smoothPif).toInt() > currentSettings.packetsInFlight * 2) {
            currentBitrate = currentSettings.minimumBitrate
            // Log.w(TAG, "PIF running away - emergency minimum!")
        }

        // Log.d(TAG, "Final bitrate: ${oldCurrentBitrate / 1000}k -> ${currentBitrate / 1000}k")
        
        // Apply bounds from configuration
        currentBitrate = max(
            min(currentBitrate, bitrateRegulatorConfig.videoBitrateRange.upper.toLong()),
            max(
                currentSettings.minimumBitrate,
                bitrateRegulatorConfig.videoBitrateRange.lower.toLong()
            )
        )
    }

    /**
     * Switch between fast and slow settings - can be called externally
     */
    fun setSettings(useFastSettings: Boolean) {
        currentSettings = if (useFastSettings) {
            moblinConfig.fastSettings
        } else {
            moblinConfig.slowSettings
        }
        Log.i(TAG, "Switched to ${if (useFastSettings) "fast" else "slow"} settings")
    }

    /**
     * Set target bitrate
     */
    fun setTargetBitrate(bitrate: Int) {
        targetBitrate = bitrate.toLong()
    }

    /**
     * Get current bitrate
     */
    fun getCurrentBitrate(): Int = currentBitrate.toInt()

    /**
     * Get current maximum bitrate in Kbps
     */
    fun getCurrentMaximumBitrateInKbps(): Long = currentMaximumBitrate / 1000

    /**
     * Get fast PIF
     */
    fun getFastPif(): Long = fastPif.toLong()

    /**
     * Get smooth PIF
     */
    fun getSmoothPif(): Long = smoothPif.toLong()

    /**
     * Log action for debugging
     */
    private fun logAction(action: String) {
        Log.d(TAG, action)
        actionHistory.add(action)
        if (actionHistory.size > 6) {
            actionHistory.removeAt(0)
        }
    }

    /**
     * Get recent actions for debugging
     */
    fun getActionHistory(): List<String> = actionHistory.toList()
}
