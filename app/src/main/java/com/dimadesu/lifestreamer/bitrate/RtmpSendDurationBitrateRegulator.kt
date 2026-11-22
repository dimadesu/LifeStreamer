package com.dimadesu.lifestreamer.bitrate

import io.github.komedia.komuxer.rtmp.util.metrics.RtmpMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetricsTracker
import io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpointMetrics
import io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.rtmpMetrics
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
 * - EMA upward smoothing 0.90/0.10 (was 0.97/0.03): converges in ~10 polls vs ~33
 * - EMA downward smoothing 0.70/0.30 (was 0.90/0.10): converges in ~3 polls vs ~10
 * - Decrease threshold 0.35 (was 0.6): triggers before kernel send buffer fully saturates
 * - Decrease percentages doubled: 30% sustained, 15% lazy (was 15% / 5%)
 * - Emergency floor: fastFillRatio >= 0.8 → drop to minimum bitrate immediately
 * - Consecutive-decrease escalation: back-to-back decreases multiply the cut
 *
 * Deviation from Moblin: the transport-bitrate cap here (using [TransportBitrateEstimator]) is an adapted
 * analogy, not a literal port. Moblin's RTMP path feeds it from `rtmpStream.info.bitrateStats`, a
 * HaishinKit-internal tracker (smoothed with its own `speedChangeRate: 30`) that has no equivalent
 * in our RTMP stack, so we instead reuse Moblin's *SRT* smoothing recipe (time-scaled EMA over raw byte
 * deltas) applied to [RtmpMetrics.totalBytesSent]. The SRT regulator ([MoblinSrtFightBitrateRegulator])
 * does replicate Moblin's real mechanism exactly.
 *
 * [TransportBitrateEstimator] scales its 0.7/0.3 retention by actual elapsed time rather than assuming
 * a fixed 1s tick, which matters here because the controller polls every 200ms (matching Moblin's
 * `is200MsTick` cadence), not 1s - without the time scaling the EMA would decay per-second at
 * 0.7^5 = 0.168 instead of the intended 0.7, making the estimate needlessly noisy/reactive.
 */
class RtmpSendDurationBitrateRegulator(
    override val metricsTracker: EndpointMetricsTracker,
    initialVideoBitrateRange: BitrateRange,
    private val onVideoTargetBitrateChange: ((Int) -> Unit),
    private val nanoTimeProvider: () -> Long = System::nanoTime
) : DynamicBitrateRegulator {

    companion object {
        private const val TAG = "RtmpSendDurationReg"

        private const val MIN_DECREASE_STEP = 100_000 // b/s
        private const val MIN_INCREASE_STEP = 100_000 // b/s
        private const val MAX_INCREASE_STEP = 500_000 // b/s
        private const val INCREASE_PERCENTAGE = 5 // %, scales recovery speed with current bitrate
        private const val INCREASE_COOLDOWN_NS = 1_000_000_000L // 1 second cooldown after increase

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

        // Allows a flat overshoot buffer when throughput is extremely low (< 143 kbps).
        // Reduced from Moblin's 1_000_000L (which works for SRT's large buffers but fatal for RTMP).
        private const val TRANSPORT_BITRATE_MINIMUM = 100_000L
    }

    private data class Sample(
        val messagesSent: Long,
        val messagesSendDropped: Long,
        val sendDurationNs: Long
    )

    private var videoBitrateRange: BitrateRange = initialVideoBitrateRange
    private var lastSample: Sample? = null
    private var lastPollNs: Long = 0L

    // Smoothed real socket throughput, used to cap the target bitrate (Moblin's limitByTransportBitrate).
    // Adapted analogy, not a literal port - see class doc.
    private val transportBitrateEstimator = TransportBitrateEstimator(nanoTimeProvider)
    private val transportBitrateBps: Long
        get() = transportBitrateEstimator.bitrateBps

    // Two-stage bitrate state modeled on Moblin's AdaptiveBitrateSrtFight:
    // - currentMaximumBitrate tracks the sustained network capacity envelope (climbing when stable,
    //   stepping down on congestion, and clamped by real socket throughput).
    // - currentBitrate is modulated down from currentMaximumBitrate during transient write-saturation
    //   spikes (fastFillRatio > smoothFillRatio) and rebounds back toward currentMaximumBitrate as
    //   soon as the spike clears, avoiding a slow multi-second climb from minimum bitrate after
    //   a brief cellular hiccup.
    private var currentMaximumBitrate: Int = 0
    private var currentBitrate: Int = 0
    private var previousEmittedBitrate: Int = 0

    // Dual-rate smoothing of fillRatio (Moblin's calcPifs applied to write saturation instead of PIF):
    // smoothFillRatio rises slowly but falls quickly, so a single noisy poll can't trigger a decrease
    // and congestion decisions instead react to sustained saturation. fastFillRatio tracks in near
    // real time so a fresh spike can be caught early via the lazy-decrease branch below.
    private var smoothFillRatio: Double = 0.0
    private var fastFillRatio: Double = 0.0

    // Tracks consecutive decrease polls for escalation.
    private var consecutiveDecreases: Int = 0

    // Cooldown tracker to prevent sawtooth oscillation
    private var nextIncreaseAllowedNs: Long = 0L

    override fun updateVideoBitrateRange(range: BitrateRange) {
        videoBitrateRange = range
        if (currentMaximumBitrate > 0) {
            currentMaximumBitrate = range.coerceIn(currentMaximumBitrate)
            val clampedBitrate = range.coerceIn(currentBitrate).coerceAtMost(currentMaximumBitrate)
            if (clampedBitrate != currentBitrate) {
                currentBitrate = clampedBitrate
                emitVideoTargetBitrate(currentBitrate)
            }
        }
    }

    internal fun getSmoothFillRatio(): Double = smoothFillRatio

    internal fun getFastFillRatio(): Double = fastFillRatio

    internal fun getTransportBitrateBps(): Long = transportBitrateBps

    internal fun getCurrentMaximumBitrate(): Int = currentMaximumBitrate

    internal fun getCurrentBitrate(): Int = currentBitrate

    private fun emitVideoTargetBitrate(bitrate: Int) {
        val clamped = videoBitrateRange.coerceIn(bitrate)
        previousEmittedBitrate = clamped
        onVideoTargetBitrateChange(clamped)
    }

    override fun update(currentVideoBitrate: Int, currentAudioBitrate: Int) {
        val metrics = metricsTracker.cumulative as? RtmpEndpointMetrics ?: return
        val raw = metrics.rawMetrics.rtmpMetrics
        updateWithMetrics(
            currentVideoBitrate = currentVideoBitrate,
            messagesSent = raw.messagesSent,
            messagesSendDropped = raw.messagesSendDropped,
            sendDurationNs = raw.sendDuration.inWholeNanoseconds,
            totalBytesSent = raw.totalBytesSent
        )
    }

    internal fun updateWithMetrics(
        currentVideoBitrate: Int,
        messagesSent: Long,
        messagesSendDropped: Long,
        sendDurationNs: Long,
        totalBytesSent: Long
    ) {
        val nowNs = nanoTimeProvider()
        val currentSample = Sample(messagesSent, messagesSendDropped, sendDurationNs)
        val previousSample = lastSample
        val previousNs = lastPollNs
        lastSample = currentSample
        lastPollNs = nowNs

        // First sample: seed the capacity envelope from the encoder's current bitrate.
        if (previousSample == null) {
            val seeded = videoBitrateRange.coerceIn(currentVideoBitrate)
            currentMaximumBitrate = seeded
            currentBitrate = seeded
            previousEmittedBitrate = seeded
            return
        }
        val elapsedNs = nowNs - previousNs
        if (elapsedNs <= 0) return

        if (currentMaximumBitrate <= 0) {
            val seeded = videoBitrateRange.coerceIn(currentVideoBitrate)
            currentMaximumBitrate = seeded
            currentBitrate = seeded
            previousEmittedBitrate = seeded
        } else {
            currentMaximumBitrate = videoBitrateRange.coerceIn(currentMaximumBitrate)
        }

        val deltaSent = currentSample.messagesSent - previousSample.messagesSent
        val deltaDropped = currentSample.messagesSendDropped - previousSample.messagesSendDropped
        val deltaSendDurationNs = (currentSample.sendDurationNs - previousSample.sendDurationNs)
            .coerceAtLeast(0)
        // komuxer's MetricsSocket only adds elapsed write duration to sendDuration AFTER a socket
        // write returns. If a write is currently suspended in-flight for the entire polling window
        // (and streaming has already started, i.e. messagesSent > 0), deltaSent and
        // deltaSendDurationNs will both be 0 even though the socket has been 100% blocked for the
        // entire interval. Treat a 0-completion poll on an active stream as full saturation (1.0).
        val instantFillRatio = if (deltaSent == 0L && currentSample.messagesSent > 0L) {
            1.0
        } else {
            (deltaSendDurationNs.toDouble() / elapsedNs.toDouble()).coerceIn(0.0, 1.0)
        }
        calcFillRatios(instantFillRatio)

        transportBitrateEstimator.update(totalBytesSent)

        // Escalation multiplier: consecutive decreases get progressively harder.
        val escalation = if (consecutiveDecreases > 1) {
            Math.pow(CONSECUTIVE_DECREASE_ESCALATION, (consecutiveDecreases - 1).toDouble())
        } else 1.0

        when {
            deltaDropped > 0 -> {
                // The internal FLV tag queue actually overflowed: react fast and hard on the ceiling.
                val percentageReduction = (deltaDropped * 100 / max(1L, deltaSent + deltaDropped))
                    .toInt().coerceIn(MIN_PERCENTAGE_DECREASE, MAX_PERCENTAGE_DECREASE)
                val decrease = max(
                    currentMaximumBitrate * percentageReduction / 100,
                    MIN_DECREASE_STEP
                )
                currentMaximumBitrate = capByTransportBitrate(
                    max(videoBitrateRange.lower, currentMaximumBitrate - decrease)
                )
                consecutiveDecreases++
                BitrateRegulatorLog.i(TAG, "Queue overflow: dropped=$deltaDropped, reducing ceiling by $percentageReduction% to $currentMaximumBitrate")
            }

            fastFillRatio >= FILL_RATIO_EMERGENCY_THRESHOLD -> {
                // Socket is almost fully blocked: trim the capacity ceiling modestly while
                // calculateCurrentBitrate() immediately drops the active encoder bitrate to minimum
                // so the TCP send buffer can drain without permanently crushing the ceiling.
                val scaledPercentage = (FILL_RATIO_LAZY_DECREASE_PERCENTAGE * escalation).toInt().coerceAtMost(50)
                val decrease = max(
                    currentMaximumBitrate * scaledPercentage / 100,
                    MIN_DECREASE_STEP
                )
                currentMaximumBitrate = capByTransportBitrate(
                    max(videoBitrateRange.lower, currentMaximumBitrate - decrease)
                )
                consecutiveDecreases++
                BitrateRegulatorLog.i(
                    TAG,
                    "EMERGENCY: fastFillRatio=$fastFillRatio >= $FILL_RATIO_EMERGENCY_THRESHOLD, " +
                        "dropping active to min=${videoBitrateRange.lower} (ceiling=$currentMaximumBitrate)"
                )
            }

            smoothFillRatio >= FILL_RATIO_DECREASE_THRESHOLD -> {
                // Socket writes are blocked most of the time on average: sustained congestion.
                val scaledPercentage = (FILL_RATIO_DECREASE_PERCENTAGE * escalation).toInt().coerceAtMost(80)
                val decrease = max(
                    currentMaximumBitrate * scaledPercentage / 100,
                    MIN_DECREASE_STEP
                )
                currentMaximumBitrate = capByTransportBitrate(
                    max(videoBitrateRange.lower, currentMaximumBitrate - decrease)
                )
                consecutiveDecreases++
                BitrateRegulatorLog.i(TAG, "Write saturation: smoothFillRatio=$smoothFillRatio, esc=${"%.1f".format(escalation)}, reducing ceiling by $scaledPercentage% to $currentMaximumBitrate")
            }

            fastFillRatio - smoothFillRatio >= FILL_RATIO_LAZY_DECREASE_DIFF_THRESHOLD -> {
                // Fresh spike the smoothed average hasn't caught up with yet: trim the ceiling
                // slightly while calculateCurrentBitrate() pulls the active bitrate down proportionally.
                // Note: do not escalate consecutiveDecreases here, otherwise recovery ticks that
                // immediately follow an emergency/sustained cut would inherit its high escalation
                // multiplier and keep crushing the ceiling while fastFillRatio is already falling.
                val decrease = max(
                    currentMaximumBitrate * FILL_RATIO_LAZY_DECREASE_PERCENTAGE / 100,
                    MIN_DECREASE_STEP
                )
                currentMaximumBitrate = capByTransportBitrate(
                    max(videoBitrateRange.lower, currentMaximumBitrate - decrease)
                )
                BitrateRegulatorLog.i(TAG, "Write saturation spike: fast=$fastFillRatio, smooth=$smoothFillRatio, reducing ceiling by $FILL_RATIO_LAZY_DECREASE_PERCENTAGE% to $currentMaximumBitrate")
            }

            smoothFillRatio <= FILL_RATIO_INCREASE_THRESHOLD &&
                currentMaximumBitrate < videoBitrateRange.upper &&
                nowNs >= nextIncreaseAllowedNs -> {
                consecutiveDecreases = 0 // Reset escalation on increase
                val increaseStep = (currentMaximumBitrate * INCREASE_PERCENTAGE / 100)
                    .coerceIn(MIN_INCREASE_STEP, MAX_INCREASE_STEP)
                val newMaxBitrate = min(
                    currentMaximumBitrate + increaseStep,
                    videoBitrateRange.upper
                )
                nextIncreaseAllowedNs = nowNs + INCREASE_COOLDOWN_NS
                currentMaximumBitrate = capByTransportBitrate(newMaxBitrate)
            }

            else -> {
                // Middle zone: hold the ceiling steady while active bitrate tracks spike decay.
                consecutiveDecreases = 0 // Reset escalation when stable
            }
        }

        currentMaximumBitrate = videoBitrateRange.coerceIn(currentMaximumBitrate)
        val newBitrate = calculateCurrentBitrate(deltaDropped)
        currentBitrate = newBitrate
        if (newBitrate != previousEmittedBitrate || newBitrate != currentVideoBitrate) {
            emitVideoTargetBitrate(newBitrate)
        }
    }

    /**
     * Computes the instantaneous encoder bitrate from the [currentMaximumBitrate] ceiling,
     * mirroring Moblin's `calculateCurrentBitrate`:
     * - Under steady conditions (no spike above [smoothFillRatio]), returns [currentMaximumBitrate].
     * - During an emergency stall ([fastFillRatio] >= [FILL_RATIO_EMERGENCY_THRESHOLD]), drops
     *   temporarily to [BitrateRange.lower] so the TCP send buffer can drain rapidly.
     * - During a moderate write-saturation spike ([fastFillRatio] - [smoothFillRatio] above the
     *   noise floor), scales [currentMaximumBitrate] down proportionally to the spike and
     *   automatically rebounds back to [currentMaximumBitrate] as [fastFillRatio] converges.
     */
    private fun calculateCurrentBitrate(deltaDropped: Long): Int {
        if (fastFillRatio >= FILL_RATIO_EMERGENCY_THRESHOLD) {
            return videoBitrateRange.lower
        }
        if (deltaDropped > 0) {
            return currentMaximumBitrate
        }
        val spikeDiff = (fastFillRatio - smoothFillRatio).coerceIn(0.0, 1.0)
        // Ignore small jitter <= 1/3 of the lazy-decrease diff threshold (analogous to Moblin's
        // `pifSpikeDiff <= settings.packetsInFlight / 5` deadzone).
        val effectiveSpike = if (spikeDiff <= FILL_RATIO_LAZY_DECREASE_DIFF_THRESHOLD / 3.0) {
            0.0
        } else {
            spikeDiff
        }
        val scaledBitrate = (currentMaximumBitrate.toDouble() * (1.0 - effectiveSpike)).toInt()
        return videoBitrateRange.coerceIn(scaledBitrate)
    }

    /**
     * Dual-rate smoothing of the fillRatio signal, mirrors Moblin's calcPifs: smoothFillRatio only
     * rises slowly (needs sustained saturation to move up) but falls more quickly once things
     * improve, while fastFillRatio tracks near real time (and decays faster than smoothFillRatio)
     * so a divergence between the two flags a fresh spike and clears promptly once writes recover.
     */
    private fun calcFillRatios(instantFillRatio: Double) {
        if (instantFillRatio > smoothFillRatio) {
            // Slow rise: ~10 polls to 63%, prevents single noisy polls from triggering decreases
            smoothFillRatio = smoothFillRatio * 0.90 + instantFillRatio * 0.10
        } else {
            // Moderate fall: ~6 polls to 63%, while fastFillRatio (0.67/0.33, ~2.5 polls to 63%)
            // decays faster so transient spikes (fastFillRatio > smoothFillRatio) clear without
            // leaving a lingering fake positive diff during recovery.
            smoothFillRatio = smoothFillRatio * 0.85 + instantFillRatio * 0.15
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
}
