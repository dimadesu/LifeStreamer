package com.dimadesu.lifestreamer.bitrate

import android.util.Log
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.ext.srt.regulator.SrtBitrateRegulator
import kotlin.math.max
import kotlin.math.min

/**
 * Port of AdaptiveBitrateSrtBela (Belabox) adaptive bitrate algorithm from the inspiration Swift code.
 * This is a faithful translation of the control logic and timers.
 */
class BelaboxSrtBelaRegulator(
    bitrateRegulatorConfig: BitrateRegulatorConfig,
    onVideoTargetBitrateChange: ((Int) -> Unit)
) : SrtBitrateRegulator(bitrateRegulatorConfig, onVideoTargetBitrateChange, { /* no audio */ }) {

    companion object {
        private const val TAG = "BelaboxSrtBela"

        private const val BITRATE_INCR_MIN: Long = 100_000L
        private const val BITRATE_INCR_INTERVAL_MS: Long = 400L
        private const val BITRATE_INCR_SCALE: Long = 30L

        private const val BITRATE_DECR_MIN: Long = 100_000L
        private const val BITRATE_DECR_INTERVAL_MS: Long = 200L
        private const val BITRATE_DECR_FAST_INTERVAL_MS: Long = 250L
        private const val BITRATE_DECR_SCALE: Long = 10L

        private const val ADAPTIVE_BITRATE_START: Long = 1_000_000L
        private const val ADAPTIVE_BITRATE_TRANSPORT_MINIMUM: Long = ADAPTIVE_BITRATE_START
    }

    // Settings mirror AdaptiveBitrateSettings from Swift
    data class Settings(
        var packetsInFlight: Long = 200L,
        var rttDiffHighFactor: Double = 0.9,
        var rttDiffHighAllowedSpike: Double = 50.0,
        var rttDiffHighMinDecrease: Long = 250_000L,
        var pifDiffIncreaseFactor: Long = 100_000L,
        var minimumBitrate: Long = 250_000L
    )

    private var settings = Settings()
    private var targetBitrate: Long = bitrateRegulatorConfig.videoBitrateRange.upper.toLong()

    // State
    private var sendBufferSizeAvg: Double = 0.0
    private var sendBufferSizeJitter: Double = 0.0
    private var prevSendBufferSize: Double = 0.0
    private var rttAvg: Double = 0.0
    private var rttAvgDelta: Double = 0.0
    private var prevRtt: Double = 300.0
    private var rttMin: Double = 200.0
    private var rttJitter: Double = 0.0
    private var throughput: Double = 0.0
    private var nextBitrateIncrTimeNs: Long = System.nanoTime()
    private var nextBitrateDecrTimeNs: Long = System.nanoTime()
    private var curBitrate: Long = 0L

    private val defaultSrtLatencyMs: Int = 3000

    fun setTargetBitrate(bitrate: Int) {
        targetBitrate = bitrate.toLong()
    }

    fun setSettings(s: Settings) {
        Log.i(TAG, "adaptive-bitrate: Using settings $s")
        settings = s
    }

    fun getCurrentBitrate(): Int = curBitrate.toInt()

    fun getCurrentMaximumBitrateInKbps(): Long = curBitrate / 1000

    private fun rttToSendBufferSize(rtt: Double, throughput: Double): Double {
        return (throughput / 8.0) * rtt / 1316.0
    }

    private fun updateSendBufferSizeAverage(sendBufferSize: Double) {
        sendBufferSizeAvg = sendBufferSizeAvg * 0.99 + sendBufferSize * 0.01
    }

    private fun updateSendBufferSizeJitter(sendBufferSize: Double) {
        sendBufferSizeJitter = 0.99 * sendBufferSizeJitter
        val deltaSendBufferSize = sendBufferSize - prevSendBufferSize
        if (deltaSendBufferSize > sendBufferSizeJitter) {
            sendBufferSizeJitter = deltaSendBufferSize
        }
        prevSendBufferSize = sendBufferSize
    }

    private fun updateRttAverage(rtt: Double) {
        if (rttAvg == 0.0) {
            rttAvg = rtt
        } else {
            rttAvg = rttAvg * 0.99 + 0.01 * rtt
        }
    }

    private fun updateAverageRttDelta(rtt: Double): Double {
        val deltaRtt = rtt - prevRtt
        rttAvgDelta = rttAvgDelta * 0.8 + deltaRtt * 0.2
        prevRtt = rtt
        return deltaRtt
    }

    private fun updateRttMin(rtt: Double) {
        rttMin *= 1.001
        if (rtt != 100.0 && rtt < rttMin && rttAvgDelta < 1.0) {
            rttMin = rtt
        }
    }

    private fun updateRttJitter(deltaRtt: Double) {
        rttJitter *= 0.99
        if (deltaRtt > rttJitter) {
            rttJitter = deltaRtt
        }
    }

    private fun updateThroughput(mbpsSendRate: Double?) {
        if (mbpsSendRate == null) return
        throughput *= 0.97
        throughput += (mbpsSendRate * 1_000_000.0 / 1024.0) * 0.03
    }

    private fun logAdaptiveAction(actionTaken: String) {
        Log.i(TAG, actionTaken)
    }

    private fun updateBitrate(stats: Stats) {
    val rttMs = stats.msRTT
    if (rttMs <= 0) return

        if (curBitrate == 0L) {
            curBitrate = ADAPTIVE_BITRATE_START
        }

        val sendBufferSize = stats.pktFlightSize.toDouble()
        updateSendBufferSizeAverage(sendBufferSize)
        updateSendBufferSizeJitter(sendBufferSize)

        val rtt = rttMs.toDouble()
        updateRttAverage(rtt)
        val deltaRtt = updateAverageRttDelta(rtt)
        updateRttMin(rtt)
        updateRttJitter(deltaRtt)
        updateThroughput(stats.mbpsSendRate)

        // srtdroid Stats doesn't expose every optional field the Swift version used.
        // Use a sensible default SRT latency when not available.
        val srtLatency = defaultSrtLatencyMs.toDouble()
        val nowNs = System.nanoTime()
        var bitrate = curBitrate

        val sendBufferSizeTh3 = (sendBufferSizeAvg + sendBufferSizeJitter) * 4.0
        var sendBufferSizeTh2 = max(50.0, sendBufferSizeAvg + max(sendBufferSizeJitter * 3.0, sendBufferSizeAvg))
        sendBufferSizeTh2 = min(sendBufferSizeTh2, rttToSendBufferSize(srtLatency / 2.0, throughput))
        // 'relaxed' flag not available on Stats in this build; default to false
        val sendBufferSizeTh1 = max(50.0, sendBufferSizeAvg + sendBufferSizeJitter * 2.5)
        val rttThMax = rttAvg + max(rttJitter * 4.0, rttAvg * 15.0 / 100.0)
        val rttThMin = rttMin + max(1.0, rttJitter * 2.0)

        // Decrease to minimum if severe conditions
        if (bitrate > settings.minimumBitrate && (rtt >= (srtLatency / 3.0) || sendBufferSize > sendBufferSizeTh3)) {
            bitrate = settings.minimumBitrate
            nextBitrateDecrTimeNs = nowNs + BITRATE_DECR_INTERVAL_MS * 1_000_000L
            logAdaptiveAction("Set min: ${bitrate / 1000}, rtt: $rtt >= latency/3: ${srtLatency / 3.0} or bs: $sendBufferSize > bs_th3: $sendBufferSizeTh3")
        } else if (nowNs > nextBitrateDecrTimeNs && (rtt > (srtLatency / 5.0) || sendBufferSize > sendBufferSizeTh2)) {
            bitrate -= (BITRATE_DECR_MIN + bitrate / BITRATE_DECR_SCALE)
            nextBitrateDecrTimeNs = nowNs + BITRATE_DECR_FAST_INTERVAL_MS * 1_000_000L
            logAdaptiveAction("Fast decr: ${(BITRATE_DECR_MIN + bitrate / BITRATE_DECR_SCALE) / 1000}, rtt: $rtt > latency/5: ${srtLatency / 5.0} or bs: $sendBufferSize > bs_th2: $sendBufferSizeTh2")
        } else if (nowNs > nextBitrateDecrTimeNs && (rtt > rttThMax || sendBufferSize > sendBufferSizeTh1)) {
            bitrate -= BITRATE_DECR_MIN
            nextBitrateDecrTimeNs = nowNs + BITRATE_DECR_INTERVAL_MS * 1_000_000L
            logAdaptiveAction("Decr: ${BITRATE_DECR_MIN / 1000}, rtt: $rtt > rtt_th_max: $rttThMax or bs: $sendBufferSize > bs_th1: $sendBufferSizeTh1")
        } else if (nowNs > nextBitrateIncrTimeNs && rtt < rttThMin && rttAvgDelta < 0.01) {
            bitrate += BITRATE_INCR_MIN + bitrate / BITRATE_INCR_SCALE
            nextBitrateIncrTimeNs = nowNs + BITRATE_INCR_INTERVAL_MS * 1_000_000L
        }

        // Cap against transport estimate if available from mbpsBandwidth
        val transportBitsPerSecond = (stats.mbpsBandwidth * 1_000_000.0).toLong()
        if (transportBitsPerSecond > 0L) {
            val maximumBitrate = max(transportBitsPerSecond + ADAPTIVE_BITRATE_TRANSPORT_MINIMUM, (17 * transportBitsPerSecond) / 10)
            if (bitrate > maximumBitrate) {
                bitrate = maximumBitrate
            }
        }

        // Bound bitrate to target/minimum
        bitrate = max(min(bitrate, targetBitrate), settings.minimumBitrate)

        if (bitrate != curBitrate) {
            curBitrate = bitrate
            onVideoTargetBitrateChange(curBitrate.toInt())
        }
    }

    override fun update(stats: Stats, currentVideoBitrate: Int, currentAudioBitrate: Int) {
        updateBitrate(stats)
    }
}
