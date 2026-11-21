package com.dimadesu.lifestreamer.bitrate

import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetricsTracker
import io.github.thibaultbee.streampack.ext.srt.elements.endpoints.SrtEndpointMetrics
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
 * Supports live bitrate range updates, Fast/Slow settings changes, and warm state handoff.
 */
class MoblinSrtFightBitrateRegulator(
    override val metricsTracker: EndpointMetricsTracker,
    initialVideoBitrateRange: BitrateRange,
    private val moblinConfig: MoblinSrtFightConfig = MoblinSrtFightConfig(),
    private val onVideoTargetBitrateChange: ((Int) -> Unit),
    initialSnapshot: SrtRegulatorSnapshot? = null,
    nanoTimeProvider: () -> Long = System::nanoTime
) : SnapshotableSrtRegulator {

    companion object {
        private const val TAG = "MoblinSrtFightBitrateRegulator"
        private const val ADAPTIVBITRATE_START = 1_000_000L // 1 Mbps starting bitrate

        // Matches Moblin's adaptiveBitrateTransportMinimum (= adaptiveBitrateStart).
        private const val TRANSPORT_BITRATE_MINIMUM = ADAPTIVBITRATE_START
    }

    private var videoBitrateRange: BitrateRange = initialVideoBitrateRange

    // Current settings (fast vs slow)
    private var currentSettings = moblinConfig.fastSettings

    // Current bitrate state - matches Moblin's approach
    private var targetBitrate: Long = initialVideoBitrateRange.upper.toLong()
    private var currentBitrate: Long = ADAPTIVBITRATE_START.coerceIn(configuredMinBitrate(), targetBitrate)
    private var previousBitrate: Long = currentBitrate
    private var currentMaximumBitrate: Long = currentBitrate

    // RTT tracking - dual approach like Moblin
    private var avgRtt: Double = 0.0
    private var fastRtt: Double = 0.0

    // PIF tracking - core of SrtFight algorithm
    private var smoothPif: Double = 0.0
    private var fastPif: Double = 0.0

    // Real send rate, used to cap the max bitrate (Moblin's limitByTransportBitrate).
    // Ported from Moblin's updateSrtTransportBitrate: EMA over deltas of the cumulative bytes-sent
    // counter, not SRT's own mbpsSendRate stat (which Moblin's algorithm never actually reads).
    private val transportBitrateEstimator = TransportBitrateEstimator(nanoTimeProvider)
    private val transportBitrateBps: Long
        get() = transportBitrateEstimator.bitrateBps

    // Action logging for debugging
    private val actionHistory = mutableListOf<String>()

    init {
        if (initialSnapshot != null) {
            val seeded = initialSnapshot.currentBitrate.coerceIn(configuredMinBitrate(), targetBitrate)
            currentBitrate = seeded
            previousBitrate = seeded
            currentMaximumBitrate = seeded
            transportBitrateEstimator.restoreSnapshot(initialSnapshot.transportSnapshot)
            if (initialSnapshot.avgRtt > 0.0) {
                avgRtt = initialSnapshot.avgRtt
                fastRtt = initialSnapshot.avgRtt
            }
            if (initialSnapshot.avgPif > 0.0) {
                smoothPif = initialSnapshot.avgPif
                fastPif = initialSnapshot.avgPif
            }
        }
    }

    private fun configuredMinBitrate(): Long =
        max(currentSettings.minimumBitrate, videoBitrateRange.lower.toLong())

    private fun emitVideoTargetBitrate(bitrate: Int) {
        onVideoTargetBitrateChange(videoBitrateRange.coerceIn(bitrate))
    }

    override fun updateVideoBitrateRange(range: BitrateRange) {
        videoBitrateRange = range
        targetBitrate = range.upper.toLong()
        val minBitrate = configuredMinBitrate()
        val floor = min(minBitrate, targetBitrate)
        currentMaximumBitrate = currentMaximumBitrate.coerceIn(floor, targetBitrate)
        val clampedBitrate = currentBitrate.coerceIn(floor, targetBitrate)
        if (clampedBitrate != currentBitrate) {
            currentBitrate = clampedBitrate
            previousBitrate = clampedBitrate
            emitVideoTargetBitrate(currentBitrate.toInt())
        }
    }

    override fun exportSnapshot(): SrtRegulatorSnapshot = SrtRegulatorSnapshot(
        currentBitrate = currentBitrate,
        transportSnapshot = transportBitrateEstimator.exportSnapshot(),
        avgRtt = avgRtt,
        avgPif = smoothPif
    )

    override fun update(currentVideoBitrate: Int, currentAudioBitrate: Int) {
        val metrics = metricsTracker.cumulative as? SrtEndpointMetrics ?: return
        val stats = metrics.rawMetrics.bistatsOrNull(clear = false, instantaneous = true) ?: return
        updateWithStats(
            rttMs = stats.msRTT,
            packetsInFlight = stats.pktFlightSize.toDouble(),
            byteSentTotal = stats.byteSentTotal
        )
    }

    // Expected to be called every 200 ms by DynamicBitrateRegulatorController (matching Moblin iOS).
    internal fun updateWithStats(rttMs: Double, packetsInFlight: Double, byteSentTotal: Long) {
        // Skip if no valid data
        if (rttMs <= 0) {
            return
        }

        updateTransportBitrate(byteSentTotal)

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

        calculateCurrentBitrate()

        // Apply video bitrate change if needed
        if (previousBitrate != currentBitrate) {
            val newVideoBitrate = currentBitrate.toInt()
            emitVideoTargetBitrate(newVideoBitrate)
            previousBitrate = currentBitrate
        }
    }

    /**
     * Ported from Moblin's updateSrtTransportBitrate: EMA over deltas of the cumulative bytes-sent
     * counter, giving the real recent send rate independently of what the encoder is configured for.
     */
    private fun updateTransportBitrate(byteSentTotal: Long) {
        transportBitrateEstimator.update(byteSentTotal)
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
        var pifSpikeDiff = (packetsInFlight - smoothPif).toLong()
        if (pifSpikeDiff < 0) {
            pifSpikeDiff = 0
        }
        if (pifSpikeDiff > currentSettings.packetsInFlight) {
            pifSpikeDiff = currentSettings.packetsInFlight
        }
        
        val pifDiffThing = currentSettings.packetsInFlight - pifSpikeDiff
        
        val pifCondition = smoothPif < currentSettings.packetsInFlight.toDouble()
        val rttCondition = fastRtt <= avgRtt + allowedRttJitter
        val jitterCondition = packetsInFlight - smoothPif < allowedPifJitter
        
        if (pifCondition && rttCondition) {
            if (jitterCondition) {
                val increase = (currentSettings.pifDiffIncreaseFactor * pifDiffThing) / currentSettings.packetsInFlight
                currentMaximumBitrate += increase
                
                if (currentMaximumBitrate > targetBitrate) {
                    currentMaximumBitrate = targetBitrate
                }
            }
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
    }

    /**
     * Decrease max rate if RTT is high - matches Moblin's decreaseMaxRateIfRttIsHigh
     */
    private fun decreaseMaxRateIfRttIsHigh(factor: Double, rttMax: Double, minimumDecrease: Long) {
        if (avgRtt <= rttMax) return
        
        val factorDecrease = (currentMaximumBitrate.toDouble() * (1 - factor)).toLong()
        val decrease = max(factorDecrease, minimumDecrease)
        currentMaximumBitrate -= decrease
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
    }

    /**
     * Calculate current bitrate - matches Moblin's calculateCurrentBitrate
     */
    private fun calculateCurrentBitrate() {
        var pifSpikeDiff = (fastPif - smoothPif).toLong()
        
        // Lazy decrease
        if (pifSpikeDiff > currentSettings.packetsInFlight) {
            currentMaximumBitrate = (currentMaximumBitrate.toDouble() * 0.95).toLong()
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
        }
        
        val pifDiffThing = currentSettings.packetsInFlight - pifSpikeDiff
        
        // Don't let the max bitrate run away from what SRT can actually send right now.
        if (transportBitrateBps > 0) {
            val maxAllowedByTransport = max(
                transportBitrateBps + TRANSPORT_BITRATE_MINIMUM,
                (17 * transportBitrateBps) / 10
            )
            currentMaximumBitrate = min(currentMaximumBitrate, maxAllowedByTransport)
        }
        
        val configuredMinBitrate = configuredMinBitrate()
        val minimumBitrate = max(50000L, configuredMinBitrate)
        if (currentMaximumBitrate < minimumBitrate) {
            currentMaximumBitrate = minimumBitrate
        }
        
        var tempBitrate = currentMaximumBitrate
        tempBitrate *= pifDiffThing
        tempBitrate /= currentSettings.packetsInFlight
        currentBitrate = tempBitrate
        
        if (currentBitrate < configuredMinBitrate) {
            currentBitrate = configuredMinBitrate
        }
        
        // PIF running away - do a quick lower of bitrate temporarily
        if ((fastPif - smoothPif).toInt() > currentSettings.packetsInFlight * 2) {
            currentBitrate = configuredMinBitrate
        }
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
        BitrateRegulatorLog.i(TAG, "Switched to ${if (useFastSettings) "fast" else "slow"} settings")
    }

    internal fun isUsingFastSettings(): Boolean = currentSettings == moblinConfig.fastSettings

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

    internal fun getAvgRtt(): Double = avgRtt

    internal fun getTransportBitrateBps(): Long = transportBitrateBps

    /**
     * Log action for debugging
     */
    private fun logAction(action: String) {
        BitrateRegulatorLog.d(TAG, action)
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
