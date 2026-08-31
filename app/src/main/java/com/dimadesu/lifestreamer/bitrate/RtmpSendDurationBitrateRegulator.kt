package com.dimadesu.lifestreamer.bitrate

import android.util.Log
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetricsTracker
import io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpointMetrics
import io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.rtmpMetrics
import io.github.thibaultbee.streampack.ext.rtmp.regulator.RtmpBitrateRegulator
import io.github.komedia.komuxer.rtmp.util.metrics.RtmpMetrics
import kotlin.math.max
import kotlin.math.min

/**
 * RTMP bitrate regulator based on socket write saturation rather than packet loss.
 *
 * RTMP runs over TCP: [RtmpMetrics.messagesSendDropped] only increments once the endpoint's small
 * internal FLV tag queue overflows, which happens long after the connection is actually congested
 * (writes into the OS socket buffer keep "succeeding" even while the real network can't keep up).
 * [RtmpMetrics.sendDuration] is the time actually spent suspended inside socket writes, so the
 * fraction of wall-clock time spent writing (the "fill ratio") is a much earlier and more reliable
 * congestion signal - the TCP equivalent of SRT's send buffer/RTT based regulation.
 *
 * The fill ratio is smoothed with the same dual fast/smooth EMA shape Moblin uses for its SRT/RTMP
 * packets-in-flight signal (see [calcFillRatios]), so decisions react to sustained saturation
 * rather than single noisy polls, while still catching fresh spikes early via a smaller "lazy"
 * decrease.
 *
 * Tuning notes (v2 - aggressive):
 * - EMA upward smoothing 0.90/0.10 (was 0.97/0.03): converges in ~5 polls vs ~33
 * - Decrease threshold 0.35 (was 0.6): triggers before kernel send buffer fully saturates
 * - Decrease percentages doubled: 30% sustained, 15% lazy (was 15% / 5%)
 * - Emergency floor: fastFillRatio > 0.8 → drop to minimum bitrate immediately
 * - Consecutive-decrease escalation: back-to-back decreases multiply the cut
 *
 * Deviation from Moblin: the transport-bitrate cap here ([transportBitrateBps]) is an adapted
 * analogy, not a literal port. Moblin's RTMP path feeds it from `rtmpStream.info.bitrateStats`, a
 * HaishinKit-internal tracker (smoothed with its own `speedChangeRate: 30`) that has no equivalent
 * in our RTMP stack, so we instead reuse Moblin's *SRT* smoothing recipe (0.7/0.3 EMA over raw byte
 * deltas) applied to [RtmpMetrics.totalBytesSent]. The SRT regulator ([MoblinSrtFightBitrateRegulator])
 * does replicate Moblin's real mechanism exactly.
 */
class RtmpSendDurationBitrateRegulator(
    metricsTracker: EndpointMetricsTracker,
    bitrateRegulatorConfig: BitrateRegulatorConfig,
    onVideoTargetBitrateChange: ((Int) -> Unit)
) : RtmpBitrateRegulator(metricsTracker, bitrateRegulatorConfig, onVideoTargetBitrateChange, { /* no audio */ }) {

    companion object {
        private const val TAG = "RtmpSendDurationReg"

        private const val MIN_DECREASE_STEP = 100_000 // b/s
        private const val MAX_INCREASE_STEP = 100_000 // b/s

        private const val MIN_PERCENTAGE_DECREASE = 20 // %, on queue overflow (hard congestion)
        private const val MAX_PERCENTAGE_DECREASE = 85 // %, on queue overflow (hard congestion)
        private const val FILL_RATIO_DECREASE_PERCENTAGE = 30 // %, on sustained write saturation (was 15)
        private const val FILL_RATIO_LAZY_DECREASE_PERCENTAGE = 15 // %, on a fresh spike (was 5)

        // Fraction of wall-clock time spent blocked in socket writes, smoothed (see calcFillRatios).
        private const val FILL_RATIO_DECREASE_THRESHOLD = 0.35 // trigger earlier, before kernel buffer saturates (was 0.6)
        private const val FILL_RATIO_INCREASE_THRESHOLD = 0.10 // require more stability before increasing (was 0.15)
        private const val FILL_RATIO_LAZY_DECREASE_DIFF_THRESHOLD = 0.15 // catch spikes earlier (was 0.3)
        private const val FILL_RATIO_EMERGENCY_THRESHOLD = 0.80 // fastFillRatio above this → emergency drop to minimum

        // Back-to-back decreases get multiplied by this factor each consecutive poll.
        private const val CONSECUTIVE_DECREASE_ESCALATION = 1.5

        // Matches Moblin's adaptiveBitrateTransportMinimum (= adaptiveBitrateStart).
        private const val TRANSPORT_BITRATE_MINIMUM = 1_000_000L
    }

    private var lastRawMetrics: RtmpMetrics? = null
    private var lastPollNs: Long = 0L

    // Smoothed real socket throughput, used to cap the target bitrate (Moblin's limitByTransportBitrate).
    // Adapted analogy, not a literal port - see class doc.
    private var transportBitrateBps: Long = 0L

    // Dual-rate smoothing of fillRatio (Moblin's calcPifs applied to write saturation instead of PIF):
    // smoothFillRatio rises slowly but falls quickly, so a single noisy poll can't trigger a decrease
    // and congestion decisions instead react to sustained saturation. fastFillRatio tracks in near
    // real time so a fresh spike can be caught early via the lazy-decrease branch below.
    private var smoothFillRatio: Double = 0.0
    private var fastFillRatio: Double = 0.0

    // Tracks consecutive decrease polls for escalation.
    private var consecutiveDecreases: Int = 0

    override fun update(currentVideoBitrate: Int, currentAudioBitrate: Int) {
        val metrics = metricsTracker.cumulative as? RtmpEndpointMetrics ?: return
        val raw = metrics.rawMetrics.rtmpMetrics

        val nowNs = System.nanoTime()
        val previousRaw = lastRawMetrics
        val previousNs = lastPollNs
        lastRawMetrics = raw
        lastPollNs = nowNs

        // First sample: nothing to diff against yet.
        if (previousRaw == null) return
        val elapsedNs = nowNs - previousNs
        if (elapsedNs <= 0) return

        val deltaSent = raw.messagesSent - previousRaw.messagesSent
        val deltaDropped = raw.messagesSendDropped - previousRaw.messagesSendDropped
        val deltaSendDurationNs = (raw.sendDuration - previousRaw.sendDuration).inWholeNanoseconds
            .coerceAtLeast(0)
        val instantFillRatio = (deltaSendDurationNs.toDouble() / elapsedNs.toDouble()).coerceIn(0.0, 1.0)
        calcFillRatios(instantFillRatio)

        val deltaBytesSent = raw.totalBytesSent - previousRaw.totalBytesSent
        val instantThroughputBps = (deltaBytesSent * 8_000_000_000L / elapsedNs).coerceAtLeast(0)
        transportBitrateBps = (transportBitrateBps * 0.7 + instantThroughputBps * 0.3).toLong()

        // Escalation multiplier: consecutive decreases get progressively harder.
        val escalation = if (consecutiveDecreases > 1) {
            Math.pow(CONSECUTIVE_DECREASE_ESCALATION, (consecutiveDecreases - 1).toDouble())
        } else 1.0

        when {
            deltaDropped > 0 -> {
                // The internal FLV tag queue actually overflowed: react fast and hard.
                val percentageReduction = (deltaDropped * 100 / max(1L, deltaSent + deltaDropped))
                    .toInt().coerceIn(MIN_PERCENTAGE_DECREASE, MAX_PERCENTAGE_DECREASE)
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * percentageReduction / 100,
                    MIN_DECREASE_STEP
                )
                consecutiveDecreases++
                Log.i(TAG, "Queue overflow: dropped=$deltaDropped, reducing by $percentageReduction% to $newBitrate")
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            fastFillRatio >= FILL_RATIO_EMERGENCY_THRESHOLD -> {
                // Socket is almost fully blocked: emergency drop to minimum bitrate.
                val minBitrate = bitrateRegulatorConfig.videoBitrateRange.lower
                consecutiveDecreases++
                Log.i(TAG, "EMERGENCY: fastFillRatio=$fastFillRatio >= $FILL_RATIO_EMERGENCY_THRESHOLD, dropping to min=$minBitrate")
                onVideoTargetBitrateChange(minBitrate)
            }

            smoothFillRatio >= FILL_RATIO_DECREASE_THRESHOLD -> {
                // Socket writes are blocked most of the time on average: sustained congestion.
                val scaledPercentage = (FILL_RATIO_DECREASE_PERCENTAGE * escalation).toInt().coerceAtMost(80)
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * scaledPercentage / 100,
                    MIN_DECREASE_STEP
                )
                consecutiveDecreases++
                Log.i(TAG, "Write saturation: smoothFillRatio=$smoothFillRatio, esc=${"%.1f".format(escalation)}, reducing by $scaledPercentage% to $newBitrate")
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            fastFillRatio - smoothFillRatio >= FILL_RATIO_LAZY_DECREASE_DIFF_THRESHOLD -> {
                // Fresh spike the smoothed average hasn't caught up with yet.
                val scaledPercentage = (FILL_RATIO_LAZY_DECREASE_PERCENTAGE * escalation).toInt().coerceAtMost(50)
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * scaledPercentage / 100,
                    MIN_DECREASE_STEP
                )
                consecutiveDecreases++
                Log.i(TAG, "Write saturation spike: fast=$fastFillRatio, smooth=$smoothFillRatio, esc=${"%.1f".format(escalation)}, reducing by $scaledPercentage% to $newBitrate")
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            smoothFillRatio <= FILL_RATIO_INCREASE_THRESHOLD &&
                currentVideoBitrate < bitrateRegulatorConfig.videoBitrateRange.upper -> {
                consecutiveDecreases = 0 // Reset escalation on increase
                val newBitrate = min(
                    currentVideoBitrate + MAX_INCREASE_STEP,
                    bitrateRegulatorConfig.videoBitrateRange.upper
                )
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            else -> {
                // Middle zone: hold steady to avoid oscillating the encoder bitrate.
                consecutiveDecreases = 0 // Reset escalation when stable
            }
        }
    }

    /**
     * Dual-rate smoothing of the fillRatio signal, mirrors Moblin's calcPifs: smoothFillRatio only
     * rises slowly (needs sustained saturation to move up) but falls quickly once things improve,
     * while fastFillRatio tracks near real time so a divergence between the two flags a fresh spike.
     */
    private fun calcFillRatios(instantFillRatio: Double) {
        if (instantFillRatio > smoothFillRatio) {
            // Faster upward convergence: ~5 polls to 63% vs ~33 before (was 0.97/0.03)
            smoothFillRatio = smoothFillRatio * 0.90 + instantFillRatio * 0.10
        } else {
            smoothFillRatio = smoothFillRatio * 0.9 + instantFillRatio * 0.1
        }
        fastFillRatio = fastFillRatio * 0.67 + instantFillRatio * 0.33
    }

    /**
     * Don't let the target bitrate run away from what the socket can actually send right now
     * (Moblin's limitByTransportBitrate), so a static scene's low encoder output doesn't get
     * mistaken for spare uplink capacity.
     */
    private fun capByTransportBitrate(bitrate: Int): Int {
        if (transportBitrateBps <= 0) return bitrate
        val maxAllowedByTransport = max(
            transportBitrateBps + TRANSPORT_BITRATE_MINIMUM,
            (17 * transportBitrateBps) / 10
        )
        return min(bitrate.toLong(), maxAllowedByTransport).toInt()
    }

    class Factory : RtmpBitrateRegulator.Factory {
        override fun newBitrateRegulator(
            metricsTracker: EndpointMetricsTracker,
            bitrateRegulatorConfig: BitrateRegulatorConfig,
            onVideoTargetBitrateChange: (Int) -> Unit,
            onAudioTargetBitrateChange: (Int) -> Unit
        ): RtmpBitrateRegulator {
            return RtmpSendDurationBitrateRegulator(
                metricsTracker,
                bitrateRegulatorConfig,
                onVideoTargetBitrateChange
            )
        }
    }
}
