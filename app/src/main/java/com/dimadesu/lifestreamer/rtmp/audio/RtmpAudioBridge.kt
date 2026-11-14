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
 * channel layout, and sample rate to match StreamPack's configured [AudioSourceConfig],
 * and buffers into a frame-aligned circular buffer drained at real-time pace.
 */
@OptIn(UnstableApi::class)
open class RtmpAudioBridgeCore {
    companion object {
        private const val TAG = "RtmpAudioBridge"

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

    private val lock = Any()

    // Target format expected by ExoPlayerAudioSource / StreamPack encoder
    private var targetSampleRate: Int = 48000
    private var targetChannelCount: Int = 2
    private var targetBytesPerFrame: Int = 4 // targetChannelCount * 2 (16-bit PCM)

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

    // Conversion pipeline state
    private var activeProcessors: List<AudioProcessor> = emptyList()
    private var pipelineConfigured: Boolean = false

    // Ring buffer state (protected by lock)
    private var ringBuffer: ByteArray = ByteArray(48000 * 4) // 1s at 48kHz stereo 16-bit
    private var maxFillBytes: Int = (ringBuffer.size * 3) / 4
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
                val capacityFrames = sampleRate // 1000 ms capacity
                val capacityBytes = (capacityFrames * bytesPerFrame).coerceAtLeast(bytesPerFrame * 1024)
                ringBuffer = ByteArray(capacityBytes)
                maxFillBytes = ((capacityBytes * 3) / 4 / bytesPerFrame) * bytesPerFrame
                readPos = 0
                writePos = 0
                availableBytes = 0
                if (inputSampleRate > 0 && inputChannelCount > 0 && inputEncoding != Format.NO_VALUE) {
                    rebuildConversionPipelineLocked()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            readPos = 0
            writePos = 0
            availableBytes = 0
            hasActiveInput = false
        }
    }

    fun onExoPlayerFormatChanged(inputFormat: Media3AudioFormat) {
        synchronized(lock) {
            inputSampleRate = inputFormat.sampleRate
            inputChannelCount = inputFormat.channelCount
            inputEncoding = inputFormat.encoding
            rebuildConversionPipelineLocked()
        }
    }

    fun onExoPlayerAudioInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val slice = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        synchronized(lock) {
            if (!pipelineConfigured && inputSampleRate > 0 && inputChannelCount > 0) {
                rebuildConversionPipelineLocked()
            }
            hasActiveInput = true
            if (activeProcessors.isEmpty()) {
                writeToRingLocked(slice)
            } else {
                processThroughChainLocked(slice, 0)
            }
        }
    }

    private fun processThroughChainLocked(buffer: ByteBuffer, index: Int) {
        if (index >= activeProcessors.size) {
            if (buffer.hasRemaining()) {
                writeToRingLocked(buffer)
            }
            return
        }
        val processor = activeProcessors[index]
        while (buffer.hasRemaining()) {
            val prevPos = buffer.position()
            processor.queueInput(buffer)
            val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            if (out.hasRemaining()) {
                processThroughChainLocked(out, index + 1)
            }
            while (true) {
                val moreOut = processor.output.order(ByteOrder.LITTLE_ENDIAN)
                if (!moreOut.hasRemaining()) break
                processThroughChainLocked(moreOut, index + 1)
            }
            if (buffer.position() == prevPos && buffer.hasRemaining()) {
                logW("AudioProcessor ${processor.javaClass.simpleName} did not advance input buffer; breaking to avoid loop")
                break
            }
        }
    }

    private fun rebuildConversionPipelineLocked() {
        activeProcessors.forEach {
            try {
                it.reset()
            } catch (_: Exception) {
            }
        }
        activeProcessors = emptyList()
        pipelineConfigured = false

        if (inputSampleRate <= 0 || inputChannelCount <= 0 || inputEncoding == Format.NO_VALUE) {
            return
        }

        try {
            var currentFormat = Media3AudioFormat(inputSampleRate, inputChannelCount, inputEncoding)
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

            // 2. Mix channels if inputChannelCount != targetChannelCount
            if (currentFormat.channelCount != targetChannelCount) {
                val channelMixer = Pcm16ChannelMixingProcessor(targetChannelCount)
                currentFormat = channelMixer.configure(currentFormat)
                channelMixer.flush()
                if (channelMixer.isActive()) {
                    chain.add(channelMixer)
                }
            }

            // 3. Resample if sampleRate != targetSampleRate
            if (currentFormat.sampleRate != targetSampleRate) {
                val sonic = SonicAudioProcessor()
                sonic.setOutputSampleRateHz(targetSampleRate)
                currentFormat = sonic.configure(currentFormat)
                sonic.flush()
                if (sonic.isActive()) {
                    chain.add(sonic)
                }
            }

            activeProcessors = chain
            pipelineConfigured = true
            logI(
                "Configured audio bridge pipeline: in=${inputSampleRate}Hz/${inputChannelCount}ch/enc=$inputEncoding -> " +
                    "out=${targetSampleRate}Hz/${targetChannelCount}ch (${chain.size} active processors)"
            )
        } catch (e: Exception) {
            logE("Failed to configure audio conversion pipeline", e)
            activeProcessors = emptyList()
            pipelineConfigured = false
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

    /**
     * Pure-Kotlin 16-bit PCM channel mixer (Mono <-> Stereo and multi-channel downmix)
     * with zero Android framework dependencies so it runs identically on device and in JVM unit tests.
     */
    private class Pcm16ChannelMixingProcessor(
        private val targetChannels: Int
    ) : BaseAudioProcessor() {

        override fun onConfigure(inputAudioFormat: Media3AudioFormat): Media3AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
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
