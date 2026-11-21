package com.dimadesu.lifestreamer.bitrate

import io.github.thibaultbee.streampack.core.elements.encoders.EncoderStats
import io.github.thibaultbee.streampack.core.elements.encoders.IEncoder
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.metrics.EmptyEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.createMetricsTracker
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicBitrateRegulatorControllerTest {

    private class FakeEncoder(override var bitrate: Int) : IEncoder {
        override val startBitrate: Int = bitrate
        override val mimeType: String = "video/avc"
        override val info: IEncoder.IEncoderInfo
            get() = throw UnsupportedOperationException("Not needed in unit test")

        override fun requestKeyFrame() = Unit
        override fun getStats(): EncoderStats = EncoderStats(0L, 0f)
    }

    private fun dummyMetricsTracker() = object : WithEndpointMetrics<Any> {
        override val metrics: EndpointMetrics<Any> = EmptyEndpointMetrics
    }.createMetricsTracker()

    @Test
    fun srtController_switchesMoblinFastAndSlowInPlaceOnSameInstance() {
        val encoder = FakeEncoder(2_000_000)
        val controller = DynamicBitrateRegulatorController(
            sinkType = MediaSinkType.SRT,
            videoEncoderProvider = { encoder },
            audioEncoderProvider = { null },
            metricsTracker = dummyMetricsTracker(),
            initialRange = BitrateRange(300_000, 6_000_000),
            initialMode = RegulatorMode.MOBLIN_FAST,
            coroutineDispatcher = Dispatchers.Unconfined
        )

        val initialRegulator = controller.currentRegulator as MoblinSrtFightBitrateRegulator
        assertTrue(initialRegulator.isUsingFastSettings())
        assertEquals(DynamicBitrateRegulatorController.MOBLIN_POLLING_TIME, controller.currentPollingTime)

        controller.updateMode(RegulatorMode.MOBLIN_SLOW)

        assertSame(
            "Fast -> Slow should mutate the existing Moblin regulator in-place",
            initialRegulator,
            controller.currentRegulator
        )
        assertTrue(!initialRegulator.isUsingFastSettings())
        assertEquals(DynamicBitrateRegulatorController.MOBLIN_POLLING_TIME, controller.currentPollingTime)
    }

    @Test
    fun srtController_switchesMoblinToBelaboxWithStateHandoffAndNewPollingInterval() {
        var nowNs = 1_000_000_000L
        val encoder = FakeEncoder(1_000_000)

        val controller = DynamicBitrateRegulatorController(
            sinkType = MediaSinkType.SRT,
            videoEncoderProvider = { encoder },
            audioEncoderProvider = { null },
            metricsTracker = dummyMetricsTracker(),
            initialRange = BitrateRange(300_000, 6_000_000),
            initialMode = RegulatorMode.MOBLIN_FAST,
            coroutineDispatcher = Dispatchers.Unconfined,
            nanoTimeProvider = { nowNs }
        )

        // Train the initial Moblin regulator above 3 Mbps
        val moblin = controller.currentRegulator as MoblinSrtFightBitrateRegulator
        var bytesSent = 0L
        repeat(40) {
            nowNs += 250_000_000L
            bytesSent += 187_500L
            moblin.updateWithStats(rttMs = 38.0, packetsInFlight = 10.0, byteSentTotal = bytesSent)
        }
        val trainedBitrate = moblin.getCurrentBitrate()
        assertTrue("Expected trained bitrate > 3 Mbps, got $trainedBitrate", trainedBitrate > 3_000_000)

        // Switch Moblin -> BelaBox
        controller.updateMode(RegulatorMode.BELABOX)

        val belabox = controller.currentRegulator
        assertNotSame(moblin, belabox)
        assertTrue(belabox is BelaboxSrtBelaRegulator)
        belabox as BelaboxSrtBelaRegulator
        assertEquals(trainedBitrate, belabox.getCurrentBitrate())
        assertEquals(DynamicBitrateRegulatorController.BELABOX_POLLING_TIME, controller.currentPollingTime)
        assertTrue("Expected warm RTT transferred", belabox.getRttAvg() > 35.0)
        assertTrue("Expected warm transport bitrate transferred", belabox.getTransportBitrateBps() > 0L)

        // Switch BelaBox -> Moblin Slow
        controller.updateMode(RegulatorMode.MOBLIN_SLOW)

        val moblinSlow = controller.currentRegulator as MoblinSrtFightBitrateRegulator
        assertTrue(!moblinSlow.isUsingFastSettings())
        assertEquals(trainedBitrate, moblinSlow.getCurrentBitrate())
        assertEquals(DynamicBitrateRegulatorController.MOBLIN_POLLING_TIME, controller.currentPollingTime)
    }

    @Test
    fun rtmpController_updateRangeClampsRunningEncoderImmediatelyAndIgnoresModeChanges() {
        val encoder = FakeEncoder(5_000_000)
        val controller = DynamicBitrateRegulatorController(
            sinkType = MediaSinkType.RTMP,
            videoEncoderProvider = { encoder },
            audioEncoderProvider = { null },
            metricsTracker = dummyMetricsTracker(),
            initialRange = BitrateRange(300_000, 6_000_000),
            coroutineDispatcher = Dispatchers.Default
        )
        val initialRegulator = controller.currentRegulator
        assertTrue(initialRegulator is RtmpSendDurationBitrateRegulator)
        assertEquals(DynamicBitrateRegulatorController.RTMP_POLLING_TIME, controller.currentPollingTime)

        controller.start()
        try {
            // Mode changes are SRT-only and must be a no-op for RTMP
            controller.updateMode(RegulatorMode.BELABOX)
            assertSame(initialRegulator, controller.currentRegulator)
            assertEquals(5_000_000, encoder.bitrate)

            // Lowering max bitrate below current encoder bitrate clamps immediately
            controller.updateVideoBitrateRange(BitrateRange(300_000, 2_500_000))
            assertSame(initialRegulator, controller.currentRegulator)
            assertEquals(2_500_000, encoder.bitrate)

            // Raising min bitrate above current encoder bitrate raises immediately
            controller.updateVideoBitrateRange(BitrateRange(3_000_000, 6_000_000))
            assertEquals(3_000_000, encoder.bitrate)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun controller_canRestartAfterStopAndLooksUpEncoderDynamically() {
        val firstEncoder = FakeEncoder(4_000_000)
        var activeEncoder = firstEncoder
        val controller = DynamicBitrateRegulatorController(
            sinkType = MediaSinkType.RTMP,
            videoEncoderProvider = { activeEncoder },
            audioEncoderProvider = { null },
            metricsTracker = dummyMetricsTracker(),
            initialRange = BitrateRange(300_000, 6_000_000),
            coroutineDispatcher = Dispatchers.Default
        )

        controller.start()
        controller.stop()

        // Swap the encoder instance while stopped, then restart
        val replacementEncoder = FakeEncoder(5_500_000)
        activeEncoder = replacementEncoder
        controller.start()
        try {
            controller.updateVideoBitrateRange(BitrateRange(300_000, 3_000_000))
            assertEquals(4_000_000, firstEncoder.bitrate)
            assertEquals(3_000_000, replacementEncoder.bitrate)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun controller_pollingLoopSurvivesTickExceptions() {
        val encoder = FakeEncoder(2_000_000)
        val providerCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val controller = DynamicBitrateRegulatorController(
            sinkType = MediaSinkType.SRT,
            videoEncoderProvider = {
                // First tick throws; later ticks must still run
                if (providerCalls.incrementAndGet() == 1) throw IllegalStateException("boom")
                encoder
            },
            audioEncoderProvider = { null },
            metricsTracker = dummyMetricsTracker(),
            initialRange = BitrateRange(300_000, 6_000_000),
            initialMode = RegulatorMode.MOBLIN_FAST,
            coroutineDispatcher = Dispatchers.Default
        )

        controller.start()
        try {
            Thread.sleep(DynamicBitrateRegulatorController.MOBLIN_POLLING_TIME.inWholeMilliseconds * 4)
            assertTrue(
                "Expected polling to continue after a failed tick, provider calls=${providerCalls.get()}",
                providerCalls.get() >= 2
            )
        } finally {
            controller.stop()
        }
    }
}
