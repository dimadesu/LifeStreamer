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

        // Fraction of wall-clock time spent blocked in socket writes.
        private const val FILL_RATIO_DECREASE_THRESHOLD = 0.6
        private const val FILL_RATIO_INCREASE_THRESHOLD = 0.15
    }

    private var lastRawMetrics: RtmpMetrics? = null
    private var lastPollNs: Long = 0L

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
        val fillRatio = (deltaSendDurationNs.toDouble() / elapsedNs.toDouble()).coerceIn(0.0, 1.0)

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
                onVideoTargetBitrateChange(newBitrate)
            }

            fillRatio >= FILL_RATIO_DECREASE_THRESHOLD -> {
                // Socket writes are blocked most of the time: sustained congestion without any drop yet.
                val newBitrate = currentVideoBitrate - max(
                    currentVideoBitrate * FILL_RATIO_DECREASE_PERCENTAGE / 100,
                    MIN_DECREASE_STEP
                )
                Log.i(TAG, "Write saturation: fillRatio=$fillRatio, reducing to $newBitrate")
                onVideoTargetBitrateChange(newBitrate)
            }

            fillRatio <= FILL_RATIO_INCREASE_THRESHOLD &&
                currentVideoBitrate < bitrateRegulatorConfig.videoBitrateRange.upper -> {
                val newBitrate = min(
                    currentVideoBitrate + MAX_INCREASE_STEP,
                    bitrateRegulatorConfig.videoBitrateRange.upper
                )
                onVideoTargetBitrateChange(newBitrate)
            }

            else -> {
                // Middle zone: hold steady to avoid oscillating the encoder bitrate.
            }
        }
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
