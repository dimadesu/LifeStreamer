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

        // Feed ~200 ms of 44.1 kHz stereo 440Hz sine wave in 20ms chunks
        val framesPerChunk = 882 // 20ms at 44100Hz
        val totalChunks = 10
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
        }

        // Expect ~200ms at 48kHz stereo = 38400 bytes (allowing slight Sonic framing tail)
        val readBuf = ByteBuffer.allocateDirect(3840 * 8) // 160ms read
        val bytesRead = bridge.readPcm(readBuf, readBuf.capacity())
        assertEquals(readBuf.capacity(), bytesRead)

        var maxAbsSample = 0
        while (readBuf.remaining() >= 2) {
            val v = abs(readBuf.short.toInt())
            if (v > maxAbsSample) maxAbsSample = v
        }
        assertTrue("Resampled audio should contain strong non-zero signal, max=$maxAbsSample", maxAbsSample > 5000)
    }

    @Test
    fun ringBufferOverflowCapsFillLevelAndKeepsNewestFrameAlignedData() {
        val bridge = RtmpAudioBridgeCore()
        val sampleRate = 48000
        bridge.setTargetConfig(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        bridge.onExoPlayerFormatChanged(Media3AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))

        // maxFillBytes = 750ms at 48kHz stereo 16-bit = 48000 * 4 * 3 / 4 = 144,000 bytes (36,000 frames)
        // Push 1.5 seconds (72,000 frames) in 12,000-frame (250ms) blocks tagged by block index
        val framesPerBlock = 12000
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

        // First 750ms read should contain blocks 4, 5, 6 (newest 36,000 frames) without corruption
        val blockBytes = framesPerBlock * 4 // 48,000 bytes
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

        // Next read after draining 750ms should be silence (0)
        val silenceBuf = ByteBuffer.allocateDirect(1920)
        val silenceBytes = bridge.readPcm(silenceBuf, 1920)
        assertEquals(1920, silenceBytes)
        for (i in 0 until 1920) {
            assertEquals(0.toByte(), silenceBuf.get(i))
        }
    }
}
