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
        private const val FILL_RATIO_DECREASE_PERCENTAGE = 15 // %, on sustained write saturation
        private const val FILL_RATIO_LAZY_DECREASE_PERCENTAGE = 5 // %, on a fresh spike not yet reflected in smoothFillRatio

        // Fraction of wall-clock time spent blocked in socket writes, smoothed (see calcFillRatios).
        private const val FILL_RATIO_DECREASE_THRESHOLD = 0.6
        private const val FILL_RATIO_INCREASE_THRESHOLD = 0.15
        private const val FILL_RATIO_LAZY_DECREASE_DIFF_THRESHOLD = 0.3

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

        when {
            deltaDropped > 0 -> {
                // The internal FLV tag queue actually overflowed: react fast and hard.
                val percentageReduction = (deltaDropped * 100 / max(1L, deltaSent + deltaDropped))
                    .toInt().coerceIn(MIN_PERCENTAGE_DECREASE, MAX_PERCENTAGE_DECREASE)
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * percentageReduction / 100,
                    MIN_DECREASE_STEP
                )
                Log.i(TAG, "Queue overflow: dropped=$deltaDropped, reducing by $percentageReduction% to $newBitrate")
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            smoothFillRatio >= FILL_RATIO_DECREASE_THRESHOLD -> {
                // Socket writes are blocked most of the time on average: sustained congestion without any drop yet.
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * FILL_RATIO_DECREASE_PERCENTAGE / 100,
                    MIN_DECREASE_STEP
                )
                Log.i(TAG, "Write saturation: smoothFillRatio=$smoothFillRatio, reducing to $newBitrate")
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            fastFillRatio - smoothFillRatio >= FILL_RATIO_LAZY_DECREASE_DIFF_THRESHOLD -> {
                // Fresh spike the smoothed average hasn't caught up with yet: react early but gently.
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * FILL_RATIO_LAZY_DECREASE_PERCENTAGE / 100,
                    MIN_DECREASE_STEP
                )
                Log.i(TAG, "Write saturation spike: fast=$fastFillRatio, smooth=$smoothFillRatio, reducing to $newBitrate")
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            smoothFillRatio <= FILL_RATIO_INCREASE_THRESHOLD &&
                currentVideoBitrate < bitrateRegulatorConfig.videoBitrateRange.upper -> {
                val newBitrate = min(
                    currentVideoBitrate + MAX_INCREASE_STEP,
                    bitrateRegulatorConfig.videoBitrateRange.upper
                )
                onVideoTargetBitrateChange(capByTransportBitrate(newBitrate))
            }

            else -> {
                // Middle zone: hold steady to avoid oscillating the encoder bitrate.
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
            smoothFillRatio = smoothFillRatio * 0.97 + instantFillRatio * 0.03
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
