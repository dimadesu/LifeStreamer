package com.dimadesu.lifestreamer.bitrate

import io.github.thibaultbee.streampack.core.elements.metrics.EmptyEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.createMetricsTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateRegulatorTest {

    private fun dummyMetricsTracker() = object : WithEndpointMetrics<Any> {
        override val metrics: EndpointMetrics<Any> = EmptyEndpointMetrics
    }.createMetricsTracker()

    @Test
    fun transportBitrateEstimator_snapshotAndRestore() {
        var nowNs = 1_000_000_000L
        val estimator = TransportBitrateEstimator { nowNs }

        // First call establishes baseline
        estimator.update(0L)
        assertEquals(0L, estimator.bitrateBps)

        // 1 second at 4 Mbps = 500_000 bytes/s -> 0 * 0.7 + 4_000_000 * 0.3 = 1_200_000
        nowNs += 1_000_000_000L
        estimator.update(500_000L)
        assertEquals(1_200_000L, estimator.bitrateBps)

        val snapshot = estimator.exportSnapshot()

        // Restored estimator continues seamlessly from exact byte & time anchor
        val restored = TransportBitrateEstimator { nowNs }
        restored.restoreSnapshot(snapshot)
        assertEquals(1_200_000L, restored.bitrateBps)

        nowNs += 1_000_000_000L
        restored.update(1_000_000L) // another 4 Mbps second
        // 1_200_000 * 0.7 + 4_000_000 * 0.3 = 840_000 + 1_200_000 = 2_040_000
        assertEquals(2_040_000L, restored.bitrateBps)
    }

    @Test
    fun moblinRegulator_liveRangeUpdate_raisesCeilingWithoutDroppingAndClampsImmediatelyWhenLowered() {
        var nowNs = 1_000_000_000L
        val emitted = mutableListOf<Int>()

        val regulator = MoblinSrtFightBitrateRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 3_000_000),
            onVideoTargetBitrateChange = { emitted.add(it) },
            nanoTimeProvider = { nowNs }
        )

        // Train under healthy network conditions (30 ms RTT, 5 PIF, 5 Mbps sent) until hitting 3 Mbps cap
        var bytesSent = 0L
        repeat(35) {
            nowNs += 250_000_000L
            bytesSent += 156_250L // 5 Mbps * 0.25s / 8
            regulator.updateWithStats(rttMs = 30.0, packetsInFlight = 5.0, byteSentTotal = bytesSent)
        }
        assertEquals(3_000_000, regulator.getCurrentBitrate())
        val trainedRtt = regulator.getAvgRtt()
        val trainedPif = regulator.getSmoothPif()
        assertTrue("Expected trained avgRtt near 30 ms, got $trainedRtt", trainedRtt in 29.0..31.0)
        assertTrue("Expected trained smoothPif > 0, got $trainedPif", trainedPif > 2.0)

        // Raise target ceiling 3 Mbps -> 6 Mbps: should NOT drop to 1 Mbps, and should continue ramping smoothly
        emitted.clear()
        regulator.updateVideoBitrateRange(BitrateRange(300_000, 6_000_000))
        assertEquals(3_000_000, regulator.getCurrentBitrate())
        assertTrue("Raising ceiling should not emit an abrupt bitrate change", emitted.isEmpty())

        nowNs += 250_000_000L
        bytesSent += 156_250L
        regulator.updateWithStats(rttMs = 30.0, packetsInFlight = 5.0, byteSentTotal = bytesSent)
        assertTrue(
            "Expected bitrate to ramp above 3 Mbps on next tick, got ${regulator.getCurrentBitrate()}",
            regulator.getCurrentBitrate() > 3_000_000
        )

        // Lower target ceiling -> 2 Mbps: should clamp immediately and emit 2 Mbps
        emitted.clear()
        regulator.updateVideoBitrateRange(BitrateRange(300_000, 2_000_000))
        assertEquals(2_000_000, regulator.getCurrentBitrate())
        assertEquals(listOf(2_000_000), emitted)

        // Raise min above current -> 2.5 Mbps..4 Mbps: should immediately raise to 2.5 Mbps
        emitted.clear()
        regulator.updateVideoBitrateRange(BitrateRange(2_500_000, 4_000_000))
        assertEquals(2_500_000, regulator.getCurrentBitrate())
        assertEquals(listOf(2_500_000), emitted)
    }

    @Test
    fun belaboxRegulator_liveRangeUpdate_preservesStateAndClampsImmediately() {
        var nowNs = 1_000_000_000L
        val emitted = mutableListOf<Int>()

        val regulator = BelaboxSrtBelaRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 2_500_000),
            onVideoTargetBitrateChange = { emitted.add(it) },
            nanoTimeProvider = { nowNs }
        )

        // Drive BelaBox at 20 ms ticks under clean 35 ms RTT, 8 PIF, 5 Mbps throughput until it reaches 2.5 Mbps
        var bytesSent = 0L
        repeat(400) {
            nowNs += 20_000_000L
            bytesSent += 12_500L // 5 Mbps * 0.02s / 8
            regulator.updateWithStats(rttMs = 35.0, sendBufferSize = 8.0, byteSentTotal = bytesSent)
        }
        assertEquals(2_500_000, regulator.getCurrentBitrate())

        // Raise ceiling 2.5 Mbps -> 5 Mbps: keeps 2.5 Mbps without dropping, then ramps higher
        emitted.clear()
        regulator.updateVideoBitrateRange(BitrateRange(300_000, 5_000_000))
        assertEquals(2_500_000, regulator.getCurrentBitrate())
        assertTrue(emitted.isEmpty())

        repeat(30) {
            nowNs += 20_000_000L
            bytesSent += 12_500L
            regulator.updateWithStats(rttMs = 35.0, sendBufferSize = 8.0, byteSentTotal = bytesSent)
        }
        assertTrue(
            "Expected BelaBox to continue ramping above 2.5 Mbps, got ${regulator.getCurrentBitrate()}",
            regulator.getCurrentBitrate() > 2_500_000
        )

        // Lower ceiling -> 1.8 Mbps: clamps immediately and emits
        emitted.clear()
        regulator.updateVideoBitrateRange(BitrateRange(300_000, 1_800_000))
        assertEquals(1_800_000, regulator.getCurrentBitrate())
        assertEquals(listOf(1_800_000), emitted)
    }

    @Test
    fun srtSnapshotTransfer_moblinToBelaboxAndBack_preservesBitrateAndBaselines() {
        var nowNs = 1_000_000_000L

        val moblin = MoblinSrtFightBitrateRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 6_000_000),
            onVideoTargetBitrateChange = {},
            nanoTimeProvider = { nowNs }
        )

        var bytesSent = 0L
        repeat(40) {
            nowNs += 250_000_000L
            bytesSent += 187_500L // 6 Mbps * 0.25s / 8
            moblin.updateWithStats(rttMs = 42.0, packetsInFlight = 12.0, byteSentTotal = bytesSent)
        }
        val moblinBitrate = moblin.getCurrentBitrate()
        assertTrue("Expected Moblin to ramp well above 1 Mbps, got $moblinBitrate", moblinBitrate > 3_000_000)

        // Handoff Moblin -> BelaBox
        val snapshotToBela = moblin.exportSnapshot()
        val belabox = BelaboxSrtBelaRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 6_000_000),
            onVideoTargetBitrateChange = {},
            initialSnapshot = snapshotToBela,
            nanoTimeProvider = { nowNs }
        )
        assertEquals(moblinBitrate, belabox.getCurrentBitrate())
        assertEquals(snapshotToBela.avgRtt, belabox.getRttAvg(), 0.001)
        assertEquals(snapshotToBela.avgRtt, belabox.getRttMin(), 0.001)
        assertEquals(snapshotToBela.avgPif, belabox.getSendBufferSizeAvg(), 0.001)
        assertEquals(snapshotToBela.transportSnapshot.bitrateBps, belabox.getTransportBitrateBps())

        // Tick BelaBox a few times under the same network conditions — should NOT drop to 1 Mbps
        repeat(25) {
            nowNs += 20_000_000L
            bytesSent += 15_000L // 6 Mbps * 0.02s / 8
            belabox.updateWithStats(rttMs = 42.0, sendBufferSize = 12.0, byteSentTotal = bytesSent)
        }
        assertTrue(
            "Expected BelaBox to stay >= handoff bitrate after warm start, got ${belabox.getCurrentBitrate()} vs $moblinBitrate",
            belabox.getCurrentBitrate() >= moblinBitrate
        )

        // Handoff BelaBox -> Moblin
        val snapshotToMoblin = belabox.exportSnapshot()
        val moblin2 = MoblinSrtFightBitrateRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 6_000_000),
            onVideoTargetBitrateChange = {},
            initialSnapshot = snapshotToMoblin,
            nanoTimeProvider = { nowNs }
        )
        assertEquals(belabox.getCurrentBitrate(), moblin2.getCurrentBitrate())
        assertEquals(snapshotToMoblin.avgRtt, moblin2.getAvgRtt(), 0.001)
        assertEquals(snapshotToMoblin.transportSnapshot.bitrateBps, moblin2.getTransportBitrateBps())
    }

    @Test
    fun rtmpRegulator_liveRangeUpdate_allowsRampingAboveOldMaxWithoutResettingFillRatios() {
        var nowNs = 1_000_000_000L
        val emitted = mutableListOf<Int>()

        val regulator = RtmpSendDurationBitrateRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 2_000_000),
            onVideoTargetBitrateChange = { emitted.add(it) },
            nanoTimeProvider = { nowNs }
        )

        // Seed baseline sample + healthy low-fill polls at 2 Mbps (already at ceiling)
        // Run enough polls (~6s) for the 0.7/s transport bitrate EMA to converge near 2 Mbps.
        var bytesSent = 0L
        var sent = 0L
        var sendDurNs = 0L
        regulator.updateWithMetrics(2_000_000, sent, 0L, sendDurNs, bytesSent)

        repeat(12) {
            nowNs += 500_000_000L
            sent += 15L
            sendDurNs += 20_000_000L // 4% fill ratio
            bytesSent += 125_000L // 2 Mbps
            regulator.updateWithMetrics(2_000_000, sent, 0L, sendDurNs, bytesSent)
        }
        assertTrue("Already at max ceiling, should not emit increases", emitted.isEmpty())
        val smoothBefore = regulator.getSmoothFillRatio()
        val transportBefore = regulator.getTransportBitrateBps()
        assertTrue(smoothBefore > 0.0)
        assertTrue(transportBefore > 0L)

        // Raise ceiling 2 Mbps -> 4 Mbps in-place
        regulator.updateVideoBitrateRange(BitrateRange(300_000, 4_000_000))
        assertEquals(smoothBefore, regulator.getSmoothFillRatio(), 0.0)
        assertEquals(transportBefore, regulator.getTransportBitrateBps())

        // Next healthy poll should immediately increase above 2 Mbps
        nowNs += 500_000_000L
        sent += 15L
        sendDurNs += 20_000_000L
        bytesSent += 125_000L
        regulator.updateWithMetrics(2_000_000, sent, 0L, sendDurNs, bytesSent)
        assertEquals(1, emitted.size)
        assertTrue("Expected bitrate increase > 2 Mbps, got ${emitted.first()}", emitted.first() > 2_000_000)
    }

    @Test
    fun rtmpRegulator_inFlightWriteStallWithZeroCompletedMessages_treatedAsFullSaturation() {
        var nowNs = 1_000_000_000L
        val emitted = mutableListOf<Int>()

        val regulator = RtmpSendDurationBitrateRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 4_000_000),
            onVideoTargetBitrateChange = { emitted.add(it) },
            nanoTimeProvider = { nowNs }
        )

        var sent = 10L
        var sendDurNs = 10_000_000L
        var bytesSent = 100_000L
        // Baseline sample
        regulator.updateWithMetrics(3_000_000, sent, 0L, sendDurNs, bytesSent)

        // One healthy poll (2% fill ratio)
        nowNs += 500_000_000L
        sent += 20L
        sendDurNs += 10_000_000L
        bytesSent += 187_500L
        regulator.updateWithMetrics(3_000_000, sent, 0L, sendDurNs, bytesSent)
        val smoothBeforeStall = regulator.getSmoothFillRatio()
        assertTrue("Expected low smoothFillRatio before stall, got $smoothBeforeStall", smoothBeforeStall < 0.05)

        // Now simulate a socket freeze where komuxer is blocked mid-write:
        // messagesSent, sendDurationNs, and totalBytesSent do not advance while write() is suspended.
        emitted.clear()
        nowNs += 500_000_000L
        regulator.updateWithMetrics(3_000_000, sent, 0L, sendDurNs, bytesSent)

        assertTrue(
            "Expected smoothFillRatio to rise on mid-write stall, was $smoothBeforeStall -> ${regulator.getSmoothFillRatio()}",
            regulator.getSmoothFillRatio() > smoothBeforeStall
        )
        assertTrue("Expected bitrate decrease on mid-write stall, got $emitted", emitted.isNotEmpty() && emitted.last() < 3_000_000)
    }

    @Test
    fun rtmpRegulator_transientSaturationSpike_throttlesImmediatelyAndReboundsToCeilingWithoutSlowCrawl() {
        var nowNs = 1_000_000_000L
        var encoderBitrate = 4_000_000
        val emitted = mutableListOf<Int>()

        val regulator = RtmpSendDurationBitrateRegulator(
            metricsTracker = dummyMetricsTracker(),
            initialVideoBitrateRange = BitrateRange(300_000, 4_000_000),
            onVideoTargetBitrateChange = {
                encoderBitrate = it
                emitted.add(it)
            },
            nanoTimeProvider = { nowNs }
        )

        var sent = 0L
        var sendDurNs = 0L
        var bytesSent = 0L
        regulator.updateWithMetrics(encoderBitrate, sent, 0L, sendDurNs, bytesSent)

        // Warm up at 4 Mbps under clean 2% fill ratio so transportBitrateEstimator converges near 4 Mbps
        repeat(10) {
            nowNs += 500_000_000L
            sent += 20L
            sendDurNs += 10_000_000L // 2% fill ratio
            bytesSent += 250_000L // 4 Mbps
            regulator.updateWithMetrics(encoderBitrate, sent, 0L, sendDurNs, bytesSent)
        }
        assertEquals(4_000_000, regulator.getCurrentMaximumBitrate())
        assertEquals(4_000_000, encoderBitrate)

        // Hit a sudden 1.5-second severe stall (3 polls of 90% fill ratio) that pushes fastFillRatio
        // above the emergency threshold or triggers a deep spike throttle
        repeat(3) {
            nowNs += 500_000_000L
            sent += 5L
            sendDurNs += 450_000_000L // 90% fill ratio
            bytesSent += 125_000L // still trickling at 2 Mbps
            regulator.updateWithMetrics(encoderBitrate, sent, 0L, sendDurNs, bytesSent)
        }
        val throttledBitrate = encoderBitrate
        val ceilingAfterBriefStall = regulator.getCurrentMaximumBitrate()
        assertTrue(
            "Expected transient stall to throttle active encoder bitrate significantly below ceiling ($ceilingAfterBriefStall), got $throttledBitrate",
            throttledBitrate < ceilingAfterBriefStall
        )
        assertTrue(
            "Expected capacity ceiling to remain well above minimum after a brief stall, got $ceilingAfterBriefStall",
            ceilingAfterBriefStall >= 1_800_000
        )

        // Congestion clears immediately (back to 2% fill ratio). Within a few polls,
        // fastFillRatio decays below smoothFillRatio + deadzone and active bitrate rebounds right
        // back up to the preserved ceiling instead of crawling up from the throttled floor at +5%/sec.
        repeat(5) {
            nowNs += 500_000_000L
            sent += 20L
            sendDurNs += 10_000_000L
            bytesSent += 200_000L
            regulator.updateWithMetrics(encoderBitrate, sent, 0L, sendDurNs, bytesSent)
        }
        assertEquals(
            "Expected active bitrate to rebound back to current ceiling",
            regulator.getCurrentMaximumBitrate(),
            encoderBitrate
        )
        assertTrue(
            "Expected rebounded bitrate to remain well above minimum (>= 2 Mbps), got $encoderBitrate",
            encoderBitrate >= 2_000_000
        )
    }
}
