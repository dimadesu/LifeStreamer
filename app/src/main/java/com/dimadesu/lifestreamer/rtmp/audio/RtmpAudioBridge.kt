package com.dimadesu.lifestreamer.rtmp.audio

import android.media.AudioFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat as Media3AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
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
 * channel layout, and sample rate via Media3's audio processors outside the ring-buffer lock
 * to match StreamPack's configured [AudioSourceConfig], and buffers into a low-latency
 * circular buffer.
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

        private fun logE(msg: String, t: Throwable? = null) {
            try {
                if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
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

    // Conversion processor chain (protected exclusively by conversionLock)
    private var lastConversionGeneration: Long = -1L
    private var activeProcessors: List<AudioProcessor> = emptyList()

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

        synchronized(conversionLock) {
            if (lastConversionGeneration != generation) {
                rebuildConversionPipelineLocked(
                    inRate = inRate,
                    inChannels = inChannels,
                    inEnc = inEnc,
                    outRate = outRate,
                    outChannels = outChannels
                )
                lastConversionGeneration = generation
            }
            if (activeProcessors.isNotEmpty()) {
                processThroughChainLocked(slice, 0, generation)
            }
        }
    }

    private fun logFormatConfigLocked() {
        if (inputSampleRate > 0 && inputChannelCount > 0 && inputEncoding != Format.NO_VALUE) {
            val isPassthrough = inputEncoding == C.ENCODING_PCM_16BIT &&
                inputChannelCount == targetChannelCount &&
                inputSampleRate == targetSampleRate
            val mode = if (isPassthrough) "passthrough" else "Media3 AudioProcessor pipeline"
            logI(
                "Configured audio bridge ($mode): in=${inputSampleRate}Hz/${inputChannelCount}ch/enc=$inputEncoding -> " +
                    "out=${targetSampleRate}Hz/${targetChannelCount}ch (maxBuffer=${MAX_BUFFER_MS}ms)"
            )
        }
    }

    private fun rebuildConversionPipelineLocked(
        inRate: Int,
        inChannels: Int,
        inEnc: Int,
        outRate: Int,
        outChannels: Int
    ) {
        activeProcessors.forEach {
            try {
                it.reset()
            } catch (_: Exception) {
            }
        }
        activeProcessors = emptyList()

        if (inRate <= 0 || inChannels <= 0 || inEnc == Format.NO_VALUE || outRate <= 0 || outChannels <= 0) {
            return
        }

        try {
            var currentFormat = Media3AudioFormat(inRate, inChannels, inEnc)
            val chain = mutableListOf<AudioProcessor>()

            // 1. Convert non-16-bit PCM (float, 24-bit, 32-bit, 8-bit) to 16-bit PCM
            if (currentFormat.encoding != C.ENCODING_PCM_16BIT) {
                val toInt16 = ToInt16PcmAudioProcessor()
                currentFormat = toInt16.configure(currentFormat)
                toInt16.flush()
                if (toInt16.isActive()) {
                    chain.add(toInt16)
                }
            }

            // 2. Mix channels if input channel count differs from target
            if (currentFormat.channelCount != outChannels) {
                val channelMixer = Pcm16ChannelMixingProcessor(outChannels)
                currentFormat = channelMixer.configure(currentFormat)
                channelMixer.flush()
                if (channelMixer.isActive()) {
                    chain.add(channelMixer)
                }
            }

            // 3. Resample if sample rate differs from target
            if (currentFormat.sampleRate != outRate) {
                val sonic = SonicAudioProcessor()
                sonic.setOutputSampleRateHz(outRate)
                currentFormat = sonic.configure(currentFormat)
                sonic.flush()
                if (sonic.isActive()) {
                    chain.add(sonic)
                }
            }

            activeProcessors = chain
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            logW("Unsupported audio format for conversion: in=${inRate}Hz/${inChannels}ch/enc=$inEnc -> out=${outRate}Hz/${outChannels}ch: ${e.message}")
            activeProcessors = emptyList()
        } catch (e: Exception) {
            logE("Failed to configure audio conversion pipeline", e)
            activeProcessors = emptyList()
        }
    }

    private fun processThroughChainLocked(buffer: ByteBuffer, index: Int, generation: Long) {
        if (!buffer.hasRemaining()) return
        if (index >= activeProcessors.size) {
            synchronized(lock) {
                if (formatGeneration == generation) {
                    writeToRingLocked(buffer)
                }
            }
            return
        }

        val processor = activeProcessors[index]
        var inIterations = 0
        while (buffer.hasRemaining() && inIterations++ < 64) {
            val prevPos = buffer.position()
            processor.queueInput(buffer)
            var outIterations = 0
            while (outIterations++ < 64) {
                val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
                if (!out.hasRemaining()) break
                processThroughChainLocked(out, index + 1, generation)
            }
            if (buffer.position() == prevPos && buffer.hasRemaining()) {
                logW("AudioProcessor ${processor.javaClass.simpleName} did not advance input buffer; breaking to avoid loop")
                break
            }
        }
    }

    private fun writeToRingLocked(src: ByteBuffer) {
        val frameSize = targetBytesPerFrame.coerceAtLeast(2)
        val totalBytes = (src.remaining() / frameSize) * frameSize
        if (totalBytes <= 0) return

        val capacity = ringBuffer.size
        if (capacity <= 0) return

        var bytesToWrite = totalBytes
        if (bytesToWrite > maxFillBytes) {
            val skip = ((bytesToWrite - maxFillBytes) / frameSize) * frameSize
            src.position(src.position() + skip)
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
        src.get(ringBuffer, writePos, firstChunk)
        val secondChunk = bytesToWrite - firstChunk
        if (secondChunk > 0) {
            src.get(ringBuffer, 0, secondChunk)
        }
        writePos = (writePos + bytesToWrite) % capacity
        availableBytes += bytesToWrite
    }

    /**
     * Pure-Kotlin 16-bit PCM channel mixer (Mono <-> Stereo and multi-channel downmix)
     * with zero Android framework dependencies so it runs identically on device and in JVM unit tests.
     */
    private class Pcm16ChannelMixingProcessor(
        private val targetChannels: Int
    ) : BaseAudioProcessor() {

        override fun onConfigure(inputAudioFormat: Media3AudioFormat): Media3AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
                targetChannels <= 0 ||
                inputAudioFormat.channelCount <= 0
            ) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            if (inputAudioFormat.channelCount == targetChannels) {
                return Media3AudioFormat.NOT_SET
            }
            return Media3AudioFormat(
                inputAudioFormat.sampleRate,
                targetChannels,
                C.ENCODING_PCM_16BIT
            )
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val inChannels = inputAudioFormat.channelCount
            val outChannels = outputAudioFormat.channelCount
            val inBytesPerFrame = inChannels * 2
            val outBytesPerFrame = outChannels * 2
            val frames = inputBuffer.remaining() / inBytesPerFrame
            if (frames <= 0) {
                inputBuffer.position(inputBuffer.limit())
                return
            }

            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val outBuf = replaceOutputBuffer(frames * outBytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)

            if (inChannels == 1 && outChannels == 2) {
                for (i in 0 until frames) {
                    val sample = inputBuffer.short
                    outBuf.putShort(sample)
                    outBuf.putShort(sample)
                }
            } else if (inChannels == 2 && outChannels == 1) {
                for (i in 0 until frames) {
                    val left = inputBuffer.short.toInt()
                    val right = inputBuffer.short.toInt()
                    val mono = ((left + right) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    outBuf.putShort(mono)
                }
            } else if (outChannels == 1) {
                for (i in 0 until frames) {
                    var sum = 0
                    for (ch in 0 until inChannels) {
                        sum += inputBuffer.short.toInt()
                    }
                    val mono = (sum / inChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    outBuf.putShort(mono)
                }
            } else {
                // Multi-channel (>=3) to Stereo
                for (i in 0 until frames) {
                    val ch0 = inputBuffer.short.toInt()
                    val ch1 = inputBuffer.short.toInt()
                    var extraSum = 0
                    for (ch in 2 until inChannels) {
                        extraSum += inputBuffer.short.toInt()
                    }
                    val extra = extraSum / (inChannels - 1)
                    val left = ((ch0 + extra) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    val right = ((ch1 + extra) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    outBuf.putShort(left)
                    outBuf.putShort(right)
                }
            }

            // Consume any trailing partial frame bytes if present
            if (inputBuffer.hasRemaining()) {
                inputBuffer.position(inputBuffer.limit())
            }
            outBuf.flip()
        }
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
