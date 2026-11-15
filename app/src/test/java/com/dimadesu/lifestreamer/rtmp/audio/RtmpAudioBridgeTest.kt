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
    fun ringBufferOverflowCapsFillLevelAndKeepsNewestFrameAlignedData() {
        val bridge = RtmpAudioBridgeCore()
        val sampleRate = 48000
        bridge.setTargetConfig(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))

        // maxFillBytes = 160ms at 48kHz stereo 16-bit = (48000 * 160 / 1000) * 4 = 30,720 bytes (7,680 frames)
        // Push 320ms (15,360 frames) in 6 blocks of 2,560 frames (53.33ms, 10,240 bytes each) tagged by block index
        val framesPerBlock = 2560
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

        // First 160ms read should contain blocks 4, 5, 6 (newest 7,680 frames) without corruption
        val blockBytes = framesPerBlock * 4 // 10,240 bytes
        val expectedMarkers = intArrayOf(4, 5, 6)
        for (expectedBlock in expectedMarkers) {
            val readBuf = ByteBuffer.allocateDirect(blockBytes)
            val bytesRead = bridge.readPcm(readBuf, blockBytes)
            assertEquals(blockBytes, bytesRead)
            assertEquals((expectedBlock * 1000).toShort(), readBuf.getShort(0))
            assertEquals((-expectedBlock * 1000).toShort(), readBuf.getShort(2))
            assertEquals((expectedBlock * 1000).toShort(), readBuf.getShort(blockBytes - 4))
            assertEquals((-expectedBlock * 1000).toShort(), readBuf.getShort(blockBytes - 2))
        }

        // Next read after draining 160ms should be silence (0)
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
