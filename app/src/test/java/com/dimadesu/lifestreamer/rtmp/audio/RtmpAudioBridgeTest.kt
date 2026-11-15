package com.dimadesu.lifestreamer.rtmp.audio

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat as Media3AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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

        // Promoting replaces the active stream, mixes mono->stereo, and trims pre-fill to TARGET_BUFFER_MS (140ms)
        bridge.promotePendingTap()
        assertEquals(140, bridge.availableDurationMs())
        val postPromoteRead = ByteBuffer.allocateDirect(probeBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(probeBytes, bridge.readPcm(postPromoteRead, probeBytes))
        assertEquals(2222.toShort(), postPromoteRead.getShort(0))
        assertEquals(2222.toShort(), postPromoteRead.getShort(2))
        assertEquals(120, bridge.availableDurationMs())

        // Late frames or reset from the superseded active tap are ignored
        val lateBuf = ByteBuffer.allocateDirect(1920 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until 1920) {
            lateBuf.putShort(1111)
            lateBuf.putShort(-1111)
        }
        lateBuf.flip()
        activeTap.queueInput(lateBuf)
        activeTap.reset()
        assertEquals(120, bridge.availableDurationMs())

        // Abandoned staged tap reset without promotion cannot disturb live stream or promote later
        val abandonedTap = bridge.createTapAudioProcessor(startStaged = true)
        abandonedTap.configure(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        abandonedTap.flush()
        val abandonedBuf = ByteBuffer.allocateDirect(4800 * 4).order(ByteOrder.LITTLE_ENDIAN)
        abandonedBuf.limit(4800 * 4)
        abandonedTap.queueInput(abandonedBuf)
        abandonedTap.reset()
        bridge.promotePendingTap()
        assertEquals(120, bridge.availableDurationMs())
    }

    @Test
    fun ringBufferOverflowCapsFillLevelAndKeepsNewestFrameAlignedData() {
        val bridge = RtmpAudioBridgeCore()
        val sampleRate = 48000
        bridge.setTargetConfig(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))

        // maxFillBytes = 220ms at 48kHz stereo 16-bit = (48000 * 220 / 1000) * 4 = 42,240 bytes (10,560 frames)
        // Push 3 blocks of 3,520 frames (220ms exact) to verify fill up to maxFillBytes without overflow,
        // then verify trimToTargetBuffer() drops back to TARGET_BUFFER_MS (140ms).
        val framesPerBlock = 3520
        for (block in 1..3) {
            val buf = ByteBuffer.allocateDirect(framesPerBlock * 4).order(ByteOrder.LITTLE_ENDIAN)
            val marker = (block * 1000).toShort()
            for (f in 0 until framesPerBlock) {
                buf.putShort(marker)
                buf.putShort((-block * 1000).toShort())
            }
            buf.flip()
            bridge.onExoPlayerAudioInput(buf)
        }
        assertEquals(220, bridge.availableDurationMs())
        bridge.trimToTargetBuffer()
        assertEquals(140, bridge.availableDurationMs())

        // Flush and push 6 blocks (440ms) to verify hysteresis overflow trim snaps >220ms back to 140ms
        // keeping the newest 6,720 frames: 3,200 tail frames of block 5 + 3,520 frames of block 6.
        bridge.flushRingBuffer()
        for (block in 1..6) {
            val buf = ByteBuffer.allocateDirect(framesPerBlock * 4).order(ByteOrder.LITTLE_ENDIAN)
            val marker = (block * 1000).toShort()
            for (f in 0 until framesPerBlock) {
                buf.putShort(marker)
                buf.putShort((-block * 1000).toShort())
            }
            buf.flip()
            bridge.onExoPlayerAudioInput(buf)
        }
        assertEquals(140, bridge.availableDurationMs())

        val block5TailBytes = 3200 * 4
        val block5Buf = ByteBuffer.allocateDirect(block5TailBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(block5TailBytes, bridge.readPcm(block5Buf, block5TailBytes))
        assertEquals(5000.toShort(), block5Buf.getShort(0))
        assertEquals((-5000).toShort(), block5Buf.getShort(2))
        assertEquals(5000.toShort(), block5Buf.getShort(block5TailBytes - 4))
        assertEquals((-5000).toShort(), block5Buf.getShort(block5TailBytes - 2))

        val blockBytes = framesPerBlock * 4 // 14,080 bytes
        val block6Buf = ByteBuffer.allocateDirect(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(blockBytes, bridge.readPcm(block6Buf, blockBytes))
        assertEquals(6000.toShort(), block6Buf.getShort(0))
        assertEquals((-6000).toShort(), block6Buf.getShort(2))
        assertEquals(6000.toShort(), block6Buf.getShort(blockBytes - 4))
        assertEquals((-6000).toShort(), block6Buf.getShort(blockBytes - 2))

        // Next read after draining 140ms should be silence (0)
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
}
