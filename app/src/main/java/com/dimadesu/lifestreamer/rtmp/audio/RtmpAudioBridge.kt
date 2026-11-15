package com.dimadesu.lifestreamer.rtmp.audio

import android.media.AudioFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat as Media3AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.min

/**
 * Thread-safe bridge between ExoPlayer's [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * audio processor chain and [ExoPlayerAudioSource].
 *
 * Captures decoded PCM before AudioTrack volume/mute is applied, converts sample format,
 * channel layout, and sample rate in a single pass outside the ring-buffer lock to match
 * StreamPack's configured [AudioSourceConfig], and buffers into a low-latency circular buffer.
 */
@OptIn(UnstableApi::class)
open class RtmpAudioBridgeCore {
    companion object {
        private const val TAG = "RtmpAudioBridge"
        private const val RING_CAPACITY_MS = 500
        private const val MAX_BUFFER_MS = 160

        private fun logI(msg: String) {
            try {
                Log.i(TAG, msg)
            } catch (_: Throwable) {
            }
        }

        private fun logW(msg: String) {
            try {
                Log.w(TAG, msg)
            } catch (_: Throwable) {
            }
        }
    }

    // Protects ring buffer state, target config, and input format metadata.
    // Kept separate from conversionLock so CPU resampling never blocks fillAudioFrame().
    private val lock = Any()
    private val conversionLock = Any()

    // Target format expected by ExoPlayerAudioSource / StreamPack encoder
    private var targetSampleRate: Int = 48000
    private var targetChannelCount: Int = 2
    private var targetBytesPerFrame: Int = 4 // targetChannelCount * 2 (16-bit PCM)
    private var formatGeneration: Long = 0L

    // Incoming ExoPlayer format (before conversion)
    @Volatile
    var inputSampleRate: Int = 0
        private set

    @Volatile
    var inputChannelCount: Int = 0
        private set

    @Volatile
    var inputEncoding: Int = Format.NO_VALUE
        private set

    @Volatile
    var hasActiveInput: Boolean = false
        private set

    // Single-pass converter / resampler state (protected exclusively by conversionLock)
    private var lastConversionGeneration: Long = -1L
    private var inFramesScratch: ShortArray = ShortArray(4096)
    private var outBytesScratch: ByteArray = ByteArray(16384)
    private var resamplePos: Double = 1.0
    private var hasPrevFrame: Boolean = false

    // Ring buffer state (protected by lock)
    private var ringBuffer: ByteArray = ByteArray(((48000L * RING_CAPACITY_MS) / 1000L).toInt() * 4)
    private var maxFillBytes: Int = ((48000L * MAX_BUFFER_MS) / 1000L).toInt() * 4
    private var readPos: Int = 0
    private var writePos: Int = 0
    private var availableBytes: Int = 0
    private var zeroScratch: ByteArray = ByteArray(8192)

    @Volatile
    private var activeTapProcessor: TapAudioProcessor? = null

    fun setTargetConfig(sampleRate: Int, channelConfig: Int, byteFormat: Int) {
        val channelCount = when (channelConfig) {
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_OUT_MONO, 1 -> 1
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_OUT_STEREO, 2 -> 2
            else -> 2
        }
        val bytesPerSample = when (byteFormat) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> 2
        }
        val bytesPerFrame = (channelCount * bytesPerSample).coerceAtLeast(2)

        synchronized(lock) {
            if (targetSampleRate != sampleRate ||
                targetChannelCount != channelCount ||
                targetBytesPerFrame != bytesPerFrame
            ) {
                targetSampleRate = sampleRate
                targetChannelCount = channelCount
                targetBytesPerFrame = bytesPerFrame
                val capacityFrames = ((sampleRate.toLong() * RING_CAPACITY_MS) / 1000L).toInt().coerceAtLeast(1024)
                val capacityBytes = capacityFrames * bytesPerFrame
                val maxFrames = ((sampleRate.toLong() * MAX_BUFFER_MS) / 1000L).toInt().coerceAtLeast(256)
                ringBuffer = ByteArray(capacityBytes)
                maxFillBytes = (maxFrames * bytesPerFrame).coerceAtMost(capacityBytes)
                readPos = 0
                writePos = 0
                availableBytes = 0
                formatGeneration++
                logFormatConfigLocked()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            readPos = 0
            writePos = 0
            availableBytes = 0
            hasActiveInput = false
            formatGeneration++
        }
    }

    /**
     * Discards buffered audio without clearing format metadata or active input state.
     * Used when attaching the video surface or starting audio streaming to drop pre-buffered backlog.
     */
    fun flushRingBuffer() {
        synchronized(lock) {
            readPos = 0
            writePos = 0
            availableBytes = 0
        }
    }

    /**
     * Returns the duration of audio currently queued in the ring buffer, in milliseconds.
     */
    fun availableDurationMs(): Int {
        synchronized(lock) {
            val bytesPerSec = (targetSampleRate.toLong() * targetBytesPerFrame).coerceAtLeast(1L)
            return ((availableBytes.toLong() * 1000L) / bytesPerSec).toInt()
        }
    }

    fun onExoPlayerFormatChanged(inputFormat: Media3AudioFormat) {
        synchronized(lock) {
            inputSampleRate = inputFormat.sampleRate
            inputChannelCount = inputFormat.channelCount
            inputEncoding = inputFormat.encoding
            formatGeneration++
            logFormatConfigLocked()
        }
    }

    fun onExoPlayerAudioInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val slice = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        val inRate: Int
        val inChannels: Int
        val inEnc: Int
        val outRate: Int
        val outChannels: Int
        val generation: Long

        synchronized(lock) {
            hasActiveInput = true
            inRate = inputSampleRate
            inChannels = inputChannelCount
            inEnc = inputEncoding
            outRate = targetSampleRate
            outChannels = targetChannelCount
            generation = formatGeneration

            if (inEnc == C.ENCODING_PCM_16BIT && inChannels == outChannels && inRate == outRate) {
                writeToRingLocked(slice)
                return
            }
        }

        if (inRate <= 0 || inChannels <= 0 || inEnc == Format.NO_VALUE) return

        val producedBytes: Int
        synchronized(conversionLock) {
            if (lastConversionGeneration != generation) {
                hasPrevFrame = false
                resamplePos = 1.0
                lastConversionGeneration = generation
            }
            producedBytes = convertAndResampleLocked(slice, inRate, inChannels, inEnc, outRate, outChannels)
            if (producedBytes <= 0) return

            synchronized(lock) {
                if (formatGeneration != generation) return
                writeBytesToRingLocked(outBytesScratch, 0, producedBytes)
            }
        }
    }

    private fun logFormatConfigLocked() {
        if (inputSampleRate > 0 && inputChannelCount > 0 && inputEncoding != Format.NO_VALUE) {
            val isPassthrough = inputEncoding == C.ENCODING_PCM_16BIT &&
                inputChannelCount == targetChannelCount &&
                inputSampleRate == targetSampleRate
            val mode = if (isPassthrough) "passthrough" else "single-pass convert/resample"
            logI(
                "Configured audio bridge ($mode): in=${inputSampleRate}Hz/${inputChannelCount}ch/enc=$inputEncoding -> " +
                    "out=${targetSampleRate}Hz/${targetChannelCount}ch (maxBuffer=${MAX_BUFFER_MS}ms)"
            )
        }
    }

    private fun convertAndResampleLocked(
        src: ByteBuffer,
        inRate: Int,
        inChannels: Int,
        inEnc: Int,
        outRate: Int,
        outChannels: Int
    ): Int {
        val bps = bytesPerSampleForEncoding(inEnc)
        if (bps == 0) {
            logW("Unsupported PCM encoding: $inEnc")
            return 0
        }

        val inFrameBytes = inChannels * bps
        val frameCount = src.remaining() / inFrameBytes
        if (frameCount <= 0) return 0

        val needsResample = inRate != outRate
        val dstFrameOffset = if (needsResample) 1 else 0
        val requiredInShorts = (dstFrameOffset + frameCount) * outChannels
        if (inFramesScratch.size < requiredInShorts) {
            inFramesScratch = inFramesScratch.copyOf(requiredInShorts)
        }

        decodeAndMixFrames(src, frameCount, inChannels, inEnc, bps, outChannels, inFramesScratch, dstFrameOffset)

        if (!needsResample) {
            val totalSamples = frameCount * outChannels
            val outByteCount = totalSamples * 2
            if (outBytesScratch.size < outByteCount) {
                outBytesScratch = ByteArray(outByteCount)
            }
            var outIdx = 0
            for (i in 0 until totalSamples) {
                val s = inFramesScratch[i].toInt()
                outBytesScratch[outIdx++] = (s and 0xFF).toByte()
                outBytesScratch[outIdx++] = ((s ushr 8) and 0xFF).toByte()
            }
            return outByteCount
        }

        if (!hasPrevFrame) {
            val firstFrameBase = outChannels
            for (ch in 0 until outChannels) {
                inFramesScratch[ch] = inFramesScratch[firstFrameBase + ch]
            }
            hasPrevFrame = true
            resamplePos = 1.0
        }

        val maxOutFrames = ((frameCount.toLong() * outRate) / inRate).toInt() + 8
        val requiredOutBytes = maxOutFrames * outChannels * 2
        if (outBytesScratch.size < requiredOutBytes) {
            outBytesScratch = ByteArray(requiredOutBytes)
        }

        val step = inRate.toDouble() / outRate.toDouble()
        val endPos = frameCount.toDouble()
        var pos = resamplePos.coerceAtLeast(0.0)
        var outIdx = 0
        val frameOutBytes = outChannels * 2

        while (pos < endPos && outIdx + frameOutBytes <= outBytesScratch.size) {
            val idx = pos.toInt().coerceIn(0, frameCount - 1)
            // Q15 fixed-point keeps (s1 - s0) * frac within signed 32-bit Int bounds
            val frac = ((pos - idx) * 32768.0).toInt().coerceIn(0, 32767)
            val base0 = idx * outChannels
            val base1 = base0 + outChannels
            for (ch in 0 until outChannels) {
                val s0 = inFramesScratch[base0 + ch].toInt()
                val s1 = inFramesScratch[base1 + ch].toInt()
                val sample = (s0 + (((s1 - s0) * frac) shr 15))
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outBytesScratch[outIdx++] = (sample and 0xFF).toByte()
                outBytesScratch[outIdx++] = ((sample ushr 8) and 0xFF).toByte()
            }
            pos += step
        }

        // Preserve the last frame at index 0 for seamless interpolation across the next chunk
        val lastFrameBase = frameCount * outChannels
        for (ch in 0 until outChannels) {
            inFramesScratch[ch] = inFramesScratch[lastFrameBase + ch]
        }
        resamplePos = (pos - endPos).coerceAtLeast(0.0)

        return outIdx
    }

    private fun decodeAndMixFrames(
        src: ByteBuffer,
        frameCount: Int,
        inChannels: Int,
        inEnc: Int,
        bps: Int,
        outChannels: Int,
        dst: ShortArray,
        dstFrameOffset: Int
    ) {
        var srcOffset = src.position()
        var dstIdx = dstFrameOffset * outChannels

        when {
            outChannels == 1 && inChannels == 1 -> {
                for (f in 0 until frameCount) {
                    dst[dstIdx++] = readSampleAsInt(src, srcOffset, inEnc).toShort()
                    srcOffset += bps
                }
            }
            outChannels == 2 && inChannels == 1 -> {
                for (f in 0 until frameCount) {
                    val s = readSampleAsInt(src, srcOffset, inEnc).toShort()
                    dst[dstIdx++] = s
                    dst[dstIdx++] = s
                    srcOffset += bps
                }
            }
            outChannels == 2 && inChannels == 2 -> {
                val stride2 = bps * 2
                for (f in 0 until frameCount) {
                    dst[dstIdx++] = readSampleAsInt(src, srcOffset, inEnc).toShort()
                    dst[dstIdx++] = readSampleAsInt(src, srcOffset + bps, inEnc).toShort()
                    srcOffset += stride2
                }
            }
            outChannels == 1 && inChannels == 2 -> {
                val stride2 = bps * 2
                for (f in 0 until frameCount) {
                    val left = readSampleAsInt(src, srcOffset, inEnc)
                    val right = readSampleAsInt(src, srcOffset + bps, inEnc)
                    dst[dstIdx++] = ((left + right) / 2)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                    srcOffset += stride2
                }
            }
            outChannels == 1 -> {
                for (f in 0 until frameCount) {
                    var sum = 0
                    for (ch in 0 until inChannels) {
                        sum += readSampleAsInt(src, srcOffset, inEnc)
                        srcOffset += bps
                    }
                    dst[dstIdx++] = (sum / inChannels)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
            else -> {
                // Multi-channel (>= 3) downmix to stereo
                for (f in 0 until frameCount) {
                    val ch0 = readSampleAsInt(src, srcOffset, inEnc)
                    val ch1 = readSampleAsInt(src, srcOffset + bps, inEnc)
                    srcOffset += bps * 2
                    var extraSum = 0
                    for (ch in 2 until inChannels) {
                        extraSum += readSampleAsInt(src, srcOffset, inEnc)
                        srcOffset += bps
                    }
                    val extra = extraSum / (inChannels - 1)
                    dst[dstIdx++] = ((ch0 + extra) / 2)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                    dst[dstIdx++] = ((ch1 + extra) / 2)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
        }
    }

    private fun bytesPerSampleForEncoding(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun readSampleAsInt(buf: ByteBuffer, byteIndex: Int, encoding: Int): Int =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> buf.getShort(byteIndex).toInt()
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN ->
                (buf.get(byteIndex).toInt() shl 8) or (buf.get(byteIndex + 1).toInt() and 0xFF)
            C.ENCODING_PCM_FLOAT ->
                (buf.getFloat(byteIndex).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            C.ENCODING_PCM_8BIT ->
                ((buf.get(byteIndex).toInt() and 0xFF) - 128) shl 8
            C.ENCODING_PCM_24BIT ->
                (buf.get(byteIndex + 2).toInt() shl 8) or (buf.get(byteIndex + 1).toInt() and 0xFF)
            C.ENCODING_PCM_32BIT ->
                (buf.get(byteIndex + 3).toInt() shl 8) or (buf.get(byteIndex + 2).toInt() and 0xFF)
            else -> 0
        }

    private fun writeBytesToRingLocked(src: ByteArray, offset: Int, length: Int) {
        writeWithOverflowControlLocked(length) { skip, firstChunk, secondChunk ->
            val srcOffset = offset + skip
            System.arraycopy(src, srcOffset, ringBuffer, writePos, firstChunk)
            if (secondChunk > 0) {
                System.arraycopy(src, srcOffset + firstChunk, ringBuffer, 0, secondChunk)
            }
        }
    }

    private fun writeToRingLocked(src: ByteBuffer) {
        writeWithOverflowControlLocked(src.remaining()) { skip, firstChunk, secondChunk ->
            if (skip > 0) {
                src.position(src.position() + skip)
            }
            src.get(ringBuffer, writePos, firstChunk)
            if (secondChunk > 0) {
                src.get(ringBuffer, 0, secondChunk)
            }
        }
    }

    private inline fun writeWithOverflowControlLocked(
        availableSourceBytes: Int,
        copyChunks: (skipBytes: Int, firstChunk: Int, secondChunk: Int) -> Unit
    ) {
        val frameSize = targetBytesPerFrame.coerceAtLeast(2)
        val totalBytes = (availableSourceBytes / frameSize) * frameSize
        if (totalBytes <= 0) return

        val capacity = ringBuffer.size
        if (capacity <= 0) return

        var skip = 0
        var bytesToWrite = totalBytes
        if (bytesToWrite > maxFillBytes) {
            skip = ((bytesToWrite - maxFillBytes) / frameSize) * frameSize
            bytesToWrite -= skip
        }

        val overflow = (availableBytes + bytesToWrite) - maxFillBytes
        if (overflow > 0) {
            val dropBytes = (((overflow + frameSize - 1) / frameSize) * frameSize)
                .coerceAtMost(availableBytes)
            readPos = (readPos + dropBytes) % capacity
            availableBytes -= dropBytes
        }

        val firstChunk = min(bytesToWrite, capacity - writePos)
        val secondChunk = bytesToWrite - firstChunk
        copyChunks(skip, firstChunk, secondChunk)
        writePos = (writePos + bytesToWrite) % capacity
        availableBytes += bytesToWrite
    }

    /**
     * Reads up to [requestedBytes] of converted PCM into [dest], padding any deficit with silence (0).
     * Leaves [dest] with position = 0, limit = bytesToFill, and ByteOrder.LITTLE_ENDIAN.
     */
    fun readPcm(dest: ByteBuffer, requestedBytes: Int): Int {
        val frameSize = targetBytesPerFrame.coerceAtLeast(2)
        val maxDestBytes = dest.capacity()
        val bytesToFill = (min(requestedBytes, maxDestBytes) / frameSize) * frameSize
        if (bytesToFill <= 0) {
            dest.position(0)
            dest.limit(0)
            return 0
        }

        dest.clear()
        dest.order(ByteOrder.LITTLE_ENDIAN)

        synchronized(lock) {
            val copyBytes = min(bytesToFill, (availableBytes / frameSize) * frameSize)
            if (copyBytes > 0) {
                val capacity = ringBuffer.size
                val firstChunk = min(copyBytes, capacity - readPos)
                dest.put(ringBuffer, readPos, firstChunk)
                val secondChunk = copyBytes - firstChunk
                if (secondChunk > 0) {
                    dest.put(ringBuffer, 0, secondChunk)
                }
                readPos = (readPos + copyBytes) % capacity
                availableBytes -= copyBytes
            }

            val remainingSilence = bytesToFill - copyBytes
            if (remainingSilence > 0) {
                if (zeroScratch.size < remainingSilence) {
                    zeroScratch = ByteArray(remainingSilence)
                } else {
                    Arrays.fill(zeroScratch, 0, remainingSilence, 0.toByte())
                }
                dest.put(zeroScratch, 0, remainingSilence)
            }
        }

        // CRITICAL: StreamPack's RawFramePullPush / Effects / MediaCodecEncoder read from position() to limit()
        dest.position(0)
        dest.limit(bytesToFill)
        return bytesToFill
    }

    fun createTapAudioProcessor(): AudioProcessor = TapAudioProcessor(this)

    private class TapAudioProcessor(
        private val bridge: RtmpAudioBridgeCore
    ) : BaseAudioProcessor() {

        override fun onConfigure(inputAudioFormat: Media3AudioFormat): Media3AudioFormat {
            bridge.activeTapProcessor = this
            bridge.onExoPlayerFormatChanged(inputAudioFormat)
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            bridge.activeTapProcessor = this
            bridge.onExoPlayerAudioInput(inputBuffer)
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
        }

        override fun onFlush() {
            if (bridge.activeTapProcessor === this) {
                // Keep target config and conversion pipeline; clear stale buffered samples on seek/flush
                bridge.clear()
            }
        }

        override fun onReset() {
            if (bridge.activeTapProcessor === this) {
                bridge.clear()
                bridge.activeTapProcessor = null
            }
        }
    }
}

object RtmpAudioBridge : RtmpAudioBridgeCore()
