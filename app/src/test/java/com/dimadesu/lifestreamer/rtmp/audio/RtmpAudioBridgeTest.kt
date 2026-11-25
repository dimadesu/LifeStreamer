package com.dimadesu.lifestreamer.rtmp.audio

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor.AudioFormat as Media3AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@OptIn(UnstableApi::class)
class RtmpAudioBridgeTest {

    @Test
    fun underflowReadsSilenceAndSetsStreamPackBufferContract() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        val dest = ByteBuffer.allocateDirect(4096).order(ByteOrder.BIG_ENDIAN)
        // Simulate stale dirty position/bytes in pooled buffer
        dest.putInt(0x12345678)
        dest.position(123)

        val bytesRead = bridge.readPcm(dest, 1920)
        assertEquals(1920, bytesRead)
        assertEquals(0, dest.position())
        assertEquals(1920, dest.limit())
        assertEquals(1920, dest.remaining())
        assertEquals(ByteOrder.LITTLE_ENDIAN, dest.order())

        for (i in 0 until 1920) {
            assertEquals("Byte at $i should be zero silence", 0.toByte(), dest.get(i))
        }
    }

    @Test
    fun passthroughPreservesSamplesAndCallerInputPosition() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))

        val samples = shortArrayOf(1234, -5678, 32000, -32000, 42, -99)
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { input.putShort(it) }
        input.flip()

        bridge.onExoPlayerAudioInput(input)
        // Caller's input buffer position must remain at 0 because bridge duplicates slice
        assertEquals(0, input.position())

        val out = ByteBuffer.allocateDirect(samples.size * 2)
        val readBytes = bridge.readPcm(out, samples.size * 2)
        assertEquals(samples.size * 2, readBytes)
        assertEquals(0, out.position())
        assertEquals(samples.size * 2, out.limit())

        for (i in samples.indices) {
            assertEquals(samples[i], out.short)
        }
    }

    @Test
    fun channelMixingMonoToStereoDuplicatesSamples() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))

        val monoInput = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        monoInput.putShort(1000)
        monoInput.putShort(-2000)
        monoInput.flip()

        bridge.onExoPlayerAudioInput(monoInput)

        val out = ByteBuffer.allocateDirect(8)
        val readBytes = bridge.readPcm(out, 8)
        assertEquals(8, readBytes)
        assertEquals(0, out.position())
        assertEquals(8, out.limit())

        assertEquals(1000.toShort(), out.short)
        assertEquals(1000.toShort(), out.short)
        assertEquals((-2000).toShort(), out.short)
        assertEquals((-2000).toShort(), out.short)
    }

    @Test
    fun channelMixingStereoToMonoAveragesChannels() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))

        val stereoInput = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        stereoInput.putShort(1000)
        stereoInput.putShort(3000)
        stereoInput.putShort(-1000)
        stereoInput.putShort(-3000)
        stereoInput.flip()

        bridge.onExoPlayerAudioInput(stereoInput)

        val out = ByteBuffer.allocateDirect(4)
        val readBytes = bridge.readPcm(out, 4)
        assertEquals(4, readBytes)
        assertEquals(0, out.position())
        assertEquals(4, out.limit())

        assertEquals(2000.toShort(), out.short)
        assertEquals((-2000).toShort(), out.short)
    }

    @Test
    fun sampleRateConversion44100To48000ProducesNonZeroOutput() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))

        // Feed 8 chunks of 20ms (160ms total, stays within 160ms cap) of 44.1 kHz stereo 440Hz sine wave
        val framesPerChunk = 882 // 20ms at 44100Hz
        val totalChunks = 8
        var sampleIdx = 0
        for (chunk in 0 until totalChunks) {
            val buf = ByteBuffer.allocateDirect(framesPerChunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until framesPerChunk) {
                val sample = (sin(2.0 * PI * 440.0 * sampleIdx / 44100.0) * 16000.0).toInt().toShort()
                buf.putShort(sample)
                buf.putShort(sample)
                sampleIdx++
            }
            buf.flip()
            bridge.onExoPlayerAudioInput(buf)

            // SonicAudioProcessor has a ~30ms internal WSOLA window, so output starts by the second 20ms chunk
            if (chunk == 1) {
                assertTrue(
                    "Resampled output should start once Sonic's ~30ms window fills, was ${bridge.availableDurationMs()}ms",
                    bridge.availableDurationMs() >= 10
                )
            }
        }

        // Read 100ms at 48kHz stereo 16-bit (19,200 bytes)
        val readBuf = ByteBuffer.allocateDirect(3840 * 5)
        val bytesRead = bridge.readPcm(readBuf, readBuf.capacity())
        assertEquals(readBuf.capacity(), bytesRead)

        var maxAbsSample = 0
        var maxDelta = 0
        var prevLeft = 0
        var firstFrame = true
        while (readBuf.remaining() >= 4) {
            val left = readBuf.short.toInt()
            val right = readBuf.short.toInt()
            assertEquals("Stereo channels should match for identical L/R sine input", left, right)
            val v = abs(left)
            if (v > maxAbsSample) maxAbsSample = v
            if (!firstFrame) {
                val delta = abs(left - prevLeft)
                if (delta > maxDelta) maxDelta = delta
            }
            prevLeft = left
            firstFrame = false
        }
        assertTrue("Resampled audio should contain strong non-zero signal, max=$maxAbsSample", maxAbsSample > 10000)
        assertTrue("Resampled signal should stay continuous across chunks, maxDelta=$maxDelta", maxDelta < 2500)
    }

    @Test
    fun downsamplingFloatMono48000ToStereo44100ProducesConvertedOutput() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT))

        val framesPerChunk = 960 // 20ms at 48kHz
        val totalChunks = 8 // 160ms
        var sampleIdx = 0
        for (chunk in 0 until totalChunks) {
            val buf = ByteBuffer.allocateDirect(framesPerChunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until framesPerChunk) {
                val sample = (sin(2.0 * PI * 440.0 * sampleIdx / 48000.0) * 0.5).toFloat()
                buf.putFloat(sample)
                sampleIdx++
            }
            buf.flip()
            bridge.onExoPlayerAudioInput(buf)

            if (chunk == 1) {
                assertTrue(
                    "Converted float output should start once Sonic's ~30ms window fills, was ${bridge.availableDurationMs()}ms",
                    bridge.availableDurationMs() >= 10
                )
            }
        }

        // Read 100ms at 44.1kHz stereo 16-bit = 4410 frames = 17,640 bytes
        val readBuf = ByteBuffer.allocateDirect(4410 * 4)
        val bytesRead = bridge.readPcm(readBuf, readBuf.capacity())
        assertEquals(readBuf.capacity(), bytesRead)

        var maxAbsSample = 0
        var maxDelta = 0
        var prevLeft = 0
        var firstFrame = true
        while (readBuf.remaining() >= 4) {
            val left = readBuf.short.toInt()
            val right = readBuf.short.toInt()
            assertEquals("Mono float input should duplicate to L and R", left, right)
            val v = abs(left)
            if (v > maxAbsSample) maxAbsSample = v
            if (!firstFrame) {
                val delta = abs(left - prevLeft)
                if (delta > maxDelta) maxDelta = delta
            }
            prevLeft = left
            firstFrame = false
        }
        assertTrue("Downsampled float audio should contain strong signal, max=$maxAbsSample", maxAbsSample > 10000)
        assertTrue("Downsampled signal should remain continuous across chunks, maxDelta=$maxDelta", maxDelta < 2500)
    }

    @Test
    fun stagedTapAudioProcessorDoesNotDisturbActiveStreamUntilPromoted() {
        val bridge = RtmpAudioBridgeCore()
        val sampleRate = 48000
        bridge.setTargetConfig(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        // Active stream pushes 100ms of stereo PCM
        val activeTap = bridge.createTapAudioProcessor(startStaged = false)
        activeTap.configure(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        activeTap.flush()
        val activeFrames = 4800 // 100ms
        val activeBuf = ByteBuffer.allocateDirect(activeFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until activeFrames) {
            activeBuf.putShort(1111)
            activeBuf.putShort(-1111)
        }
        activeBuf.flip()
        activeTap.queueInput(activeBuf)
        assertEquals(activeFrames * 4, activeTap.output.remaining())
        assertEquals(100, bridge.availableDurationMs())

        // Warming-up RTMP stream pushes 180ms of mono PCM while staged
        val stagedTap = bridge.createTapAudioProcessor(startStaged = true)
        stagedTap.configure(Media3AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
        stagedTap.flush()
        val stagedFrames = 8640 // 180ms
        val stagedBuf = ByteBuffer.allocateDirect(stagedFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until stagedFrames) {
            stagedBuf.putShort(2222)
        }
        stagedBuf.flip()
        stagedTap.queueInput(stagedBuf)

        // Downstream ExoPlayer AudioTrack receives the full buffer untouched, while live bridge stays at 100ms
        assertEquals(stagedFrames * 2, stagedTap.output.remaining())
        assertEquals(100, bridge.availableDurationMs())

        val probeBytes = 960 * 4 // 20ms stereo
        val prePromoteRead = ByteBuffer.allocateDirect(probeBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(probeBytes, bridge.readPcm(prePromoteRead, probeBytes))
        assertEquals(1111.toShort(), prePromoteRead.getShort(0))
        assertEquals((-1111).toShort(), prePromoteRead.getShort(2))
        assertEquals(80, bridge.availableDurationMs())

        // Promoting replaces the active stream, mixes mono->stereo, and preserves all 180ms of pre-fill
        // intact because 180ms <= DEFAULT_TARGET_BUFFER_MS (235ms) without clipping initial startup samples.
        bridge.promotePendingTap()
        assertEquals(180, bridge.availableDurationMs())
        val postPromoteRead = ByteBuffer.allocateDirect(probeBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(probeBytes, bridge.readPcm(postPromoteRead, probeBytes))
        assertEquals(2222.toShort(), postPromoteRead.getShort(0))
        assertEquals(2222.toShort(), postPromoteRead.getShort(2))
        assertEquals(160, bridge.availableDurationMs())

        // Late frames or reset from the superseded active tap are ignored
        val lateBuf = ByteBuffer.allocateDirect(1920 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until 1920) {
            lateBuf.putShort(1111)
            lateBuf.putShort(-1111)
        }
        lateBuf.flip()
        activeTap.queueInput(lateBuf)
        activeTap.reset()
        assertEquals(160, bridge.availableDurationMs())

        // Abandoned staged tap reset without promotion cannot disturb live stream or promote later
        val abandonedTap = bridge.createTapAudioProcessor(startStaged = true)
        abandonedTap.configure(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        abandonedTap.flush()
        val abandonedBuf = ByteBuffer.allocateDirect(4800 * 4).order(ByteOrder.LITTLE_ENDIAN)
        abandonedBuf.limit(4800 * 4)
        abandonedTap.queueInput(abandonedBuf)
        abandonedTap.reset()
        bridge.promotePendingTap()
        assertEquals(160, bridge.availableDurationMs())

        // Oversized staged pre-fill (280ms) trims down to DEFAULT_TARGET_BUFFER_MS (235ms) on promotion
        val oversizedStagedTap = bridge.createTapAudioProcessor(startStaged = true)
        oversizedStagedTap.configure(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        oversizedStagedTap.flush()
        val oversizedFrames = 13440 // 280ms
        val oversizedBuf = ByteBuffer.allocateDirect(oversizedFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until oversizedFrames) {
            oversizedBuf.putShort(3333)
            oversizedBuf.putShort(-3333)
        }
        oversizedBuf.flip()
        oversizedStagedTap.queueInput(oversizedBuf)
        bridge.promotePendingTap()
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.availableDurationMs())
    }

    @Test
    fun ringBufferOverflowCapsFillLevelAndKeepsNewestFrameAlignedData() {
        val bridge = RtmpAudioBridgeCore()
        val sampleRate = 48000
        bridge.setTargetConfig(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))

        // maxFillBytes = 320ms at 48kHz stereo 16-bit = (48000 * 320 / 1000) * 4 = 61,440 bytes (15,360 frames)
        // Push 4 blocks of 3,840 frames (80ms each = 320ms exact) to verify fill up to maxFillBytes without overflow,
        // then verify trimToTargetBuffer() drops back to DEFAULT_TARGET_BUFFER_MS (235ms = 11,280 frames).
        val framesPerBlock = 3840
        for (block in 1..4) {
            val buf = ByteBuffer.allocateDirect(framesPerBlock * 4).order(ByteOrder.LITTLE_ENDIAN)
            val marker = (block * 1000).toShort()
            for (f in 0 until framesPerBlock) {
                buf.putShort(marker)
                buf.putShort((-block * 1000).toShort())
            }
            buf.flip()
            bridge.onExoPlayerAudioInput(buf)
        }
        assertEquals(RtmpAudioBridgeCore.DEFAULT_MAX_BUFFER_MS, bridge.availableDurationMs())
        bridge.trimToTargetBuffer()
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.availableDurationMs())

        // Flush and push 7 blocks (560ms): block 5 overflows 320ms -> snaps to 235ms, block 6 reaches 315ms,
        // block 7 reaches 395ms -> snaps to 235ms (11,280 frames: 3,600 tail frames of block 5 + 3,840 of block 6 + 3,840 of block 7).
        bridge.flushRingBuffer()
        for (block in 1..7) {
            val buf = ByteBuffer.allocateDirect(framesPerBlock * 4).order(ByteOrder.LITTLE_ENDIAN)
            val marker = (block * 1000).toShort()
            for (f in 0 until framesPerBlock) {
                buf.putShort(marker)
                buf.putShort((-block * 1000).toShort())
            }
            buf.flip()
            bridge.onExoPlayerAudioInput(buf)
        }
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.availableDurationMs())

        val block5TailBytes = 3600 * 4
        val block5Buf = ByteBuffer.allocateDirect(block5TailBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(block5TailBytes, bridge.readPcm(block5Buf, block5TailBytes))
        assertEquals(5000.toShort(), block5Buf.getShort(0))
        assertEquals((-5000).toShort(), block5Buf.getShort(2))
        assertEquals(5000.toShort(), block5Buf.getShort(block5TailBytes - 4))
        assertEquals((-5000).toShort(), block5Buf.getShort(block5TailBytes - 2))

        val blockBytes = framesPerBlock * 4 // 15,360 bytes
        val block6Buf = ByteBuffer.allocateDirect(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(blockBytes, bridge.readPcm(block6Buf, blockBytes))
        assertEquals(6000.toShort(), block6Buf.getShort(0))
        assertEquals((-6000).toShort(), block6Buf.getShort(2))
        assertEquals(6000.toShort(), block6Buf.getShort(blockBytes - 4))
        assertEquals((-6000).toShort(), block6Buf.getShort(blockBytes - 2))

        val block7Buf = ByteBuffer.allocateDirect(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(blockBytes, bridge.readPcm(block7Buf, blockBytes))
        assertEquals(7000.toShort(), block7Buf.getShort(0))
        assertEquals((-7000).toShort(), block7Buf.getShort(2))
        assertEquals(7000.toShort(), block7Buf.getShort(blockBytes - 4))
        assertEquals((-7000).toShort(), block7Buf.getShort(blockBytes - 2))

        // Next read after draining 235ms should be silence (0)
        val silenceBuf = ByteBuffer.allocateDirect(1920)
        val silenceBytes = bridge.readPcm(silenceBuf, 1920)
        assertEquals(1920, silenceBytes)
        for (i in 0 until 1920) {
            assertEquals(0.toByte(), silenceBuf.get(i))
        }

        // Verify flushRingBuffer resets buffered data while preserving format metadata
        val extraBuf = ByteBuffer.allocateDirect(framesPerBlock * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until framesPerBlock) {
            extraBuf.putShort(1234)
            extraBuf.putShort(-1234)
        }
        extraBuf.flip()
        bridge.onExoPlayerAudioInput(extraBuf)
        assertTrue(bridge.availableDurationMs() > 0)
        bridge.flushRingBuffer()
        assertEquals(0, bridge.availableDurationMs())
        assertTrue(bridge.hasActiveInput)
        assertEquals(48000, bridge.inputSampleRate)
    }

    @kotlin.OptIn(InternalStreamPackApi::class)
    @Test
    fun forwardingAudioSinkTracksSteadyStateLagAndBluetoothCushionExpansion() = runBlocking {
        val bridge = RtmpAudioBridgeCore()
        val sampleRate = 48000
        val source = ExoPlayerAudioSource(bridge)
        source.configure(
            AudioSourceConfig(
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                sampleRate = sampleRate,
                byteFormat = AudioFormat.ENCODING_PCM_16BIT
            )
        )

        val fakeDelegate = FakeAudioSink()
        val session = bridge.createTapSession(startStaged = false)
        val wrappedSink = session.wrapAudioSink(fakeDelegate)
        val tap = session.audioProcessor

        val pcmFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(sampleRate)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        wrappedSink.configure(pcmFormat, 0, null)
        tap.configure(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        tap.flush()

        fun feedChunk(ptsUs: Long, sampleVal: Short = 4000, acceptInSink: Boolean = true) {
            val frames = 960 // 20ms
            val chunk = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frames) {
                chunk.putShort(sampleVal)
                chunk.putShort(sampleVal)
            }
            chunk.flip()
            fakeDelegate.acceptNextBuffer = acceptInSink
            fakeDelegate.onHandleAccepted = { buf ->
                if (acceptInSink) {
                    tap.queueInput(buf)
                }
            }
            wrappedSink.handleBuffer(chunk, ptsUs, 1)
        }

        // Pre-fill 8 chunks (160ms, PTS = 5_000_000 .. 5_160_000) before play() / advancing playhead
        val startPtsUs = 5_000_000L
        fakeDelegate.currentPositionUs = startPtsUs
        for (i in 0 until 8) {
            feedChunk(startPtsUs + i * 20_000L)
        }
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.targetBufferDurationMs())
        assertEquals(160, bridge.availableDurationMs())

        // Start playback; while playhead is still stationary at startPtsUs during HAL traversal,
        // target remains 235ms and ExoPlayerAudioSource prebuffers with silence instead of clipping PTS=0.
        wrappedSink.play()
        source.startStream()

        val outBuf = ByteBuffer.allocateDirect(960 * 4).order(ByteOrder.LITTLE_ENDIAN)
        source.fillAudioFrame(outBuf)
        assertEquals(0.toShort(), outBuf.getShort(0))
        // Still 160ms untouched in bridge because prebuffering padded silence
        assertEquals(160, bridge.availableDurationMs())

        // Feed 4 more chunks (PTS 5_160_000 .. 5_240_000) while playhead still at 5_000_000 -> bridge reaches 240ms >= 235ms
        for (i in 8 until 12) {
            feedChunk(startPtsUs + i * 20_000L)
        }
        assertEquals(240, bridge.availableDurationMs())
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.targetBufferDurationMs())

        // Playout position now advances by 20ms (5_020_000), while write head reaches 5_280_000 -> 260ms speaker lag!
        fakeDelegate.currentPositionUs = startPtsUs + 20_000L
        feedChunk(startPtsUs + 260_000L) // writeHead = 5_280_000, lag = 260ms
        assertEquals(260, bridge.targetBufferDurationMs())
        assertFalse("Small +25ms target refinement should not force mid-stream silence refill", bridge.consumeCushionRefillRequest())

        // Simulate route change / flush to Bluetooth A2DP with 430ms lag
        wrappedSink.flush()
        tap.flush()
        val btStartPtsUs = 10_000_000L
        fakeDelegate.currentPositionUs = btStartPtsUs
        for (i in 0 until 12) {
            feedChunk(btStartPtsUs + i * 20_000L, sampleVal = 7000)
        }
        // Bridge has 240ms (>= 235ms default), so fillAudioFrame starts reading real audio (7000)
        source.fillAudioFrame(outBuf)
        assertEquals(7000.toShort(), outBuf.getShort(0))
        assertEquals(220, bridge.availableDurationMs())

        // Once Bluetooth playhead advances by 20ms, sink reports 430ms lag (writeHead = 10_450_000, playout = 10_020_000)
        fakeDelegate.currentPositionUs = btStartPtsUs + 20_000L
        feedChunk(btStartPtsUs + 430_000L, sampleVal = 7000)
        assertEquals(430, bridge.targetBufferDurationMs())
        assertEquals(430 + RtmpAudioBridgeCore.OVERFLOW_HEADROOM_MS, bridge.maxBufferDurationMs())

        // Deficit (430ms - 240ms = 190ms >= 75ms) triggers cushion refill: ExoPlayerAudioSource outputs silence until 430ms fills
        source.fillAudioFrame(outBuf)
        assertEquals(0.toShort(), outBuf.getShort(0))
        assertEquals(240, bridge.availableDurationMs())

        // Top up 10 more 20ms chunks (200ms -> 440ms >= 430ms target) and verify real audio resumes cleanly
        for (i in 0 until 10) {
            fakeDelegate.currentPositionUs = btStartPtsUs + (40_000L + i * 20_000L)
            feedChunk(btStartPtsUs + (450_000L + i * 20_000L), sampleVal = 7000)
        }
        assertTrue("Buffered audio should meet 430ms target, was ${bridge.availableDurationMs()}ms", bridge.availableDurationMs() >= 430)
        source.fillAudioFrame(outBuf)
        assertEquals(7000.toShort(), outBuf.getShort(0))

        source.stopStream()
        source.release()
    }

    @Test
    fun dynamicTargetSubtractsSonicInternalLatencyWhenResampling() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        val fakeDelegate = FakeAudioSink()
        val session = bridge.createTapSession(startStaged = false)
        val wrappedSink = session.wrapAudioSink(fakeDelegate)
        val tap = session.audioProcessor

        // Input is 44.1 kHz -> output is 48 kHz (SonicAudioProcessor active)
        val pcm441Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(44100)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        wrappedSink.configure(pcm441Format, 0, null)
        tap.configure(Media3AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        tap.flush()
        wrappedSink.play()

        val chunkFrames = 882 // 20ms at 44.1 kHz
        fun feed441Chunk(ptsUs: Long) {
            val buf = ByteBuffer.allocateDirect(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.limit(chunkFrames * 4)
            fakeDelegate.onHandleAccepted = { tap.queueInput(it) }
            wrappedSink.handleBuffer(buf, ptsUs, 1)
        }

        // Establish baseline at 1_000_000 us
        fakeDelegate.currentPositionUs = 1_000_000L
        feed441Chunk(1_245_000L)
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.targetBufferDurationMs())

        // Advance playhead by 20ms -> writeHead = 1_265_000 + 20_000 = 1_285_000, playout = 1_020_000 => rawLag = 265ms
        // Expected ring buffer target = 265ms - 20ms (Sonic window) = 245ms
        fakeDelegate.currentPositionUs = 1_020_000L
        feed441Chunk(1_265_000L)
        assertEquals(265 - RtmpAudioBridgeCore.SONIC_INTERNAL_LATENCY_MS, bridge.targetBufferDurationMs())
    }

    @Test
    fun stagedHighLatencySinkPromotesWithoutCushionRefillGap() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        val delegate = FakeAudioSink()
        val session = bridge.createTapSession(startStaged = true)
        val sink = session.wrapAudioSink(delegate)
        val tap = session.audioProcessor

        val pcmFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(48000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        sink.configure(pcmFormat, 0, null)
        tap.configure(Media3AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        tap.flush()
        sink.play()
        delegate.onHandleAccepted = { tap.queueInput(it) }

        // Warm-up: stage 30 x 20ms = 600ms of pre-fill (well above the old 320ms staging cap)
        val startPtsUs = 2_000_000L
        delegate.currentPositionUs = startPtsUs
        for (i in 0 until 30) {
            if (i == 29) {
                // Playhead advances 20ms while the warm-up sink reports a Bluetooth-like 450ms lag
                delegate.currentPositionUs = startPtsUs + 20_000L
                val buf = ByteBuffer.allocateDirect(960 * 4).apply { limit(960 * 4) }
                sink.handleBuffer(buf, startPtsUs + 450_000L, 1) // writeHead=470ms, lag=450ms
            } else {
                val buf = ByteBuffer.allocateDirect(960 * 4).apply { limit(960 * 4) }
                sink.handleBuffer(buf, startPtsUs + i * 20_000L, 1)
            }
        }
        // Still staged: active target unaffected
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.targetBufferDurationMs())

        bridge.promotePendingTap()
        assertEquals(450, bridge.targetBufferDurationMs())
        // Full 450ms cushion is available immediately from staged pre-fill (no trim to 235/320ms)
        assertEquals(450, bridge.availableDurationMs())
        assertFalse("Staged pre-fill should satisfy the high-latency target", bridge.consumeCushionRefillRequest())
    }

    @Test
    fun supersededTapSessionCannotMutateActiveTargetBuffer() {
        val bridge = RtmpAudioBridgeCore()
        bridge.setTargetConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        val oldDelegate = FakeAudioSink()
        val oldSession = bridge.createTapSession(startStaged = false)
        val oldSink = oldSession.wrapAudioSink(oldDelegate)
        val oldTap = oldSession.audioProcessor

        val pcmFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(48000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        oldSink.configure(pcmFormat, 0, null)
        oldTap.configure(Media3AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        oldTap.flush()
        oldSink.play()

        // Establish baseline on old sink
        oldDelegate.currentPositionUs = 0L
        oldDelegate.onHandleAccepted = { oldTap.queueInput(it) }
        val buf1 = ByteBuffer.allocateDirect(960 * 4).apply { limit(960 * 4) }
        oldSink.handleBuffer(buf1, 220_000L, 1)

        // Promote a new tap session, superseding oldSession
        val newSession = bridge.createTapSession(startStaged = true)
        val newTap = newSession.audioProcessor
        newTap.configure(Media3AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        newTap.flush()
        bridge.promotePendingTap()
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.targetBufferDurationMs())

        // Old sink reports 500ms lag after advancing -> must be ignored!
        oldDelegate.currentPositionUs = 30_000L
        val buf2 = ByteBuffer.allocateDirect(960 * 4).apply { limit(960 * 4) }
        oldSink.handleBuffer(buf2, 510_000L, 1)
        assertEquals(RtmpAudioBridgeCore.DEFAULT_TARGET_BUFFER_MS, bridge.targetBufferDurationMs())
    }

    private class FakeAudioSink : AudioSink {
        var sinkListener: AudioSink.Listener? = null
        var currentPositionUs: Long = AudioSink.CURRENT_POSITION_NOT_SET
        var acceptNextBuffer: Boolean = true
        var onHandleAccepted: ((ByteBuffer) -> Unit)? = null
        private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        private var skipSilence: Boolean = false
        private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT

        override fun setListener(listener: AudioSink.Listener) {
            this.sinkListener = listener
        }

        override fun supportsFormat(format: Format): Boolean = true

        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = currentPositionUs

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}

        override fun play() {}

        override fun handleDiscontinuity() {}

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            if (!acceptNextBuffer) return false
            onHandleAccepted?.invoke(buffer)
            if (buffer.hasRemaining()) {
                buffer.position(buffer.limit())
            }
            return true
        }

        override fun playToEndOfStream() {}

        override fun isEnded(): Boolean = false

        override fun hasPendingData(): Boolean = true

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            this.playbackParameters = playbackParameters
        }

        override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
            this.skipSilence = skipSilenceEnabled
        }

        override fun getSkipSilenceEnabled(): Boolean = skipSilence

        override fun setAudioAttributes(audioAttributes: AudioAttributes) {
            this.audioAttributes = audioAttributes
        }

        override fun getAudioAttributes(): AudioAttributes = audioAttributes

        override fun setAudioSessionId(audioSessionId: Int) {}

        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

        override fun getAudioTrackBufferSizeUs(): Long = 160_000L

        override fun enableTunnelingV21() {}

        override fun disableTunneling() {}

        override fun setVolume(volume: Float) {}

        override fun pause() {}

        override fun flush() {}

        override fun reset() {}
    }
}
