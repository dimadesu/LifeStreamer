package com.dimadesu.lifestreamer.rtmp.audio

import android.media.AudioFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat as Media3AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thread-safe bridge between ExoPlayer's [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * audio processor chain and [ExoPlayerAudioSource].
 *
 * Captures decoded PCM before AudioTrack volume/mute is applied, converts sample format,
 * channel layout, and sample rate via Media3's audio processors outside the ring-buffer lock
 * to match StreamPack's configured [AudioSourceConfig], and buffers into a low-latency
 * circular buffer whose target depth dynamically tracks ExoPlayer's AudioSink playout lag.
 */
@OptIn(UnstableApi::class)
open class RtmpAudioBridgeCore {
    companion object {
        private const val TAG = "RtmpAudioBridge"
        internal const val RING_CAPACITY_MS = 1000
        internal const val DEFAULT_TARGET_BUFFER_MS = 235
        internal const val MIN_TARGET_BUFFER_MS = 140
        internal const val MAX_TARGET_BUFFER_MS = 650
        internal const val OVERFLOW_HEADROOM_MS = 85
        internal const val DEFAULT_MAX_BUFFER_MS = DEFAULT_TARGET_BUFFER_MS + OVERFLOW_HEADROOM_MS
        internal const val MAX_STAGED_BUFFER_MS = MAX_TARGET_BUFFER_MS + OVERFLOW_HEADROOM_MS
        internal const val SONIC_INTERNAL_LATENCY_MS = 20
        internal const val TARGET_UPDATE_DEADBAND_MS = 15
        private const val CUSHION_REFILL_DEFICIT_MS = 75
        private const val SINK_LAG_EMA_ALPHA = 0.25
        private const val MIN_PLAUSIBLE_SINK_LAG_MS = 80
        private const val MAX_PLAUSIBLE_SINK_LAG_MS = 1200
        private const val MIN_ADVANCING_PLAYOUT_DELTA_US = 20_000L

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
    private var targetBufferMs: Int = DEFAULT_TARGET_BUFFER_MS
    private var maxBufferMs: Int = DEFAULT_MAX_BUFFER_MS
    private var targetFillBytes: Int = ((48000L * DEFAULT_TARGET_BUFFER_MS) / 1000L).toInt() * 4
    private var maxFillBytes: Int = ((48000L * DEFAULT_MAX_BUFFER_MS) / 1000L).toInt() * 4
    private var hasMeasuredSinkLag: Boolean = false
    private var smoothedSinkLagMs: Double = DEFAULT_TARGET_BUFFER_MS.toDouble()
    private var needsCushionRefill: Boolean = false
    private var readPos: Int = 0
    private var writePos: Int = 0
    private var availableBytes: Int = 0
    private var zeroScratch: ByteArray = ByteArray(8192)

    private val tapSelectionLock = Any()

    @Volatile
    private var activeTapProcessor: TapAudioProcessor? = null

    @Volatile
    private var pendingTapProcessor: TapAudioProcessor? = null

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
                ringBuffer = ByteArray(capacityBytes)
                recomputeTargetFillBytesLocked(forceUpdate = true, allowOverflowTrim = false)
                readPos = 0
                writePos = 0
                availableBytes = 0
                needsCushionRefill = false
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
            hasMeasuredSinkLag = false
            smoothedSinkLagMs = DEFAULT_TARGET_BUFFER_MS.toDouble()
            needsCushionRefill = false
            recomputeTargetFillBytesLocked(forceUpdate = true, allowOverflowTrim = false)
            formatGeneration++
        }
    }

    /**
     * Promotes a staged [TapAudioProcessor] once the warming-up ExoPlayer reaches STATE_READY,
     * atomically switching active ownership and replaying its buffered pre-fill without
     * letting teardown frames from any previous player contaminate the bridge.
     */
    fun promotePendingTap() {
        val pending = synchronized(tapSelectionLock) {
            val current = pendingTapProcessor ?: return
            pendingTapProcessor = null
            current
        }
        if (pending.promoteToActive()) {
            trimToTargetBuffer()
        }
    }

    /**
     * Discards buffered audio without clearing format metadata or active input state.
     */
    fun flushRingBuffer() {
        synchronized(lock) {
            readPos = 0
            writePos = 0
            availableBytes = 0
            needsCushionRefill = false
        }
    }

    /**
     * Target ring buffer fill level in milliseconds used to stay aligned with AudioTrack playback.
     */
    fun targetBufferDurationMs(): Int {
        synchronized(lock) {
            return targetBufferMs
        }
    }

    /**
     * Maximum ring buffer fill level in milliseconds before hysteresis snap-back trims to target.
     */
    fun maxBufferDurationMs(): Int {
        synchronized(lock) {
            return maxBufferMs
        }
    }

    /**
     * Atomically consumes any pending request for [ExoPlayerAudioSource] to re-enter prebuffering
     * when a dynamic latency jump (e.g. Bluetooth A2DP routing) raises the target cushion
     * significantly above the currently buffered audio.
     */
    fun consumeCushionRefillRequest(): Boolean {
        synchronized(lock) {
            if (!needsCushionRefill) return false
            needsCushionRefill = false
            return availableBytes < targetFillBytes
        }
    }

    /**
     * Trims any buffered audio older than [targetBufferDurationMs] while keeping the newest
     * target cushion of audio intact so RtmpAudioBridge stays aligned with the
     * audio currently in-flight inside ExoPlayer's AudioSink + HAL pipeline.
     */
    fun trimToTargetBuffer() {
        synchronized(lock) {
            trimToTargetBufferLocked()
        }
    }

    private fun trimToTargetBufferLocked() {
        val frameSize = targetBytesPerFrame.coerceAtLeast(2)
        val excess = availableBytes - targetFillBytes
        if (excess > 0) {
            val dropBytes = ((excess / frameSize) * frameSize).coerceAtMost(availableBytes)
            val capacity = ringBuffer.size
            if (capacity > 0 && dropBytes > 0) {
                readPos = (readPos + dropBytes) % capacity
                availableBytes -= dropBytes
            }
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

    fun onAudioSinkLagMeasured(sinkLagMs: Int) {
        onSinkLagMeasuredInternal(sinkLagMs, sourceTap = null)
    }

    private fun onSinkLagMeasuredInternal(sinkLagMs: Int, sourceTap: TapAudioProcessor?) {
        if (sinkLagMs < MIN_PLAUSIBLE_SINK_LAG_MS || sinkLagMs > MAX_PLAUSIBLE_SINK_LAG_MS) {
            return
        }
        synchronized(lock) {
            if (sourceTap != null && activeTapProcessor !== sourceTap) return
            val isFirstMeasurement = !hasMeasuredSinkLag
            if (isFirstMeasurement) {
                smoothedSinkLagMs = sinkLagMs.toDouble()
                hasMeasuredSinkLag = true
            } else {
                smoothedSinkLagMs += SINK_LAG_EMA_ALPHA * (sinkLagMs.toDouble() - smoothedSinkLagMs)
            }
            recomputeTargetFillBytesLocked(
                forceUpdate = isFirstMeasurement,
                allowOverflowTrim = true
            )
        }
    }

    private fun recomputeTargetFillBytesLocked(
        forceUpdate: Boolean,
        allowOverflowTrim: Boolean
    ) {
        val rawLagMs = if (hasMeasuredSinkLag) {
            smoothedSinkLagMs.roundToInt()
        } else {
            DEFAULT_TARGET_BUFFER_MS
        }
        val conversionLatencyMs = if (
            hasMeasuredSinkLag &&
            inputSampleRate > 0 &&
            targetSampleRate > 0 &&
            inputSampleRate != targetSampleRate
        ) {
            SONIC_INTERNAL_LATENCY_MS
        } else {
            0
        }
        val desiredTargetMs = (rawLagMs - conversionLatencyMs)
            .coerceIn(MIN_TARGET_BUFFER_MS, MAX_TARGET_BUFFER_MS)

        if (!forceUpdate && abs(desiredTargetMs - targetBufferMs) < TARGET_UPDATE_DEADBAND_MS) {
            return
        }

        val previousTargetMs = targetBufferMs
        targetBufferMs = desiredTargetMs
        maxBufferMs = (desiredTargetMs + OVERFLOW_HEADROOM_MS).coerceAtMost(RING_CAPACITY_MS)

        val capacityBytes = ringBuffer.size
        val maxFrames = ((targetSampleRate.toLong() * maxBufferMs) / 1000L).toInt().coerceAtLeast(256)
        val targetFrames = ((targetSampleRate.toLong() * targetBufferMs) / 1000L).toInt().coerceAtLeast(256)
        maxFillBytes = (maxFrames * targetBytesPerFrame).coerceAtMost(capacityBytes)
        targetFillBytes = (targetFrames * targetBytesPerFrame).coerceAtMost(maxFillBytes)

        if (desiredTargetMs > previousTargetMs && hasActiveInput) {
            val refillDeficitFrames = ((targetSampleRate.toLong() * CUSHION_REFILL_DEFICIT_MS) / 1000L).toInt()
            val refillDeficitBytes = refillDeficitFrames * targetBytesPerFrame
            if (targetFillBytes - availableBytes >= refillDeficitBytes) {
                needsCushionRefill = true
            }
        }
        if (allowOverflowTrim && availableBytes > maxFillBytes) {
            trimToTargetBufferLocked()
        }
    }

    fun onExoPlayerFormatChanged(inputFormat: Media3AudioFormat) {
        onExoPlayerFormatChangedInternal(inputFormat, sourceTap = null)
    }

    private fun onExoPlayerFormatChangedInternal(
        inputFormat: Media3AudioFormat,
        sourceTap: TapAudioProcessor?
    ) {
        synchronized(lock) {
            if (sourceTap != null && activeTapProcessor !== sourceTap) return
            inputSampleRate = inputFormat.sampleRate
            inputChannelCount = inputFormat.channelCount
            inputEncoding = inputFormat.encoding
            recomputeTargetFillBytesLocked(forceUpdate = true, allowOverflowTrim = false)
            formatGeneration++
            logFormatConfigLocked()
        }
    }

    fun onExoPlayerAudioInput(inputBuffer: ByteBuffer) {
        onExoPlayerAudioInputInternal(inputBuffer, sourceTap = null)
    }

    private fun onExoPlayerAudioInputInternal(
        inputBuffer: ByteBuffer,
        sourceTap: TapAudioProcessor?
    ) {
        if (!inputBuffer.hasRemaining()) return
        val slice = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        val inRate: Int
        val inChannels: Int
        val inEnc: Int
        val outRate: Int
        val outChannels: Int
        val generation: Long

        synchronized(lock) {
            if (sourceTap != null && activeTapProcessor !== sourceTap) return
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
                    "out=${targetSampleRate}Hz/${targetChannelCount}ch (targetBuffer=${targetBufferMs}ms, maxBuffer=${maxBufferMs}ms)"
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
            // Snap back to targetFillBytes on overflow to avoid continuous per-frame drops at the ceiling.
            val excessOverTarget = (availableBytes + bytesToWrite) - targetFillBytes
            val dropBytes = (((excessOverTarget + frameSize - 1) / frameSize) * frameSize)
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
        if (availableBytes >= targetFillBytes) {
            needsCushionRefill = false
        }
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
                putSilenceLocked(dest, remainingSilence)
            }
        }

        // CRITICAL: StreamPack's RawFramePullPush / Effects / MediaCodecEncoder read from position() to limit()
        dest.position(0)
        dest.limit(bytesToFill)
        return bytesToFill
    }

    /**
     * Fills [dest] with [requestedBytes] (frame-aligned) of silence without consuming from the ring buffer.
     */
    fun fillSilence(dest: ByteBuffer, requestedBytes: Int): Int {
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
            putSilenceLocked(dest, bytesToFill)
        }
        dest.position(0)
        dest.limit(bytesToFill)
        return bytesToFill
    }

    private fun putSilenceLocked(dest: ByteBuffer, silenceBytes: Int) {
        if (zeroScratch.size < silenceBytes) {
            zeroScratch = ByteArray(silenceBytes)
        } else {
            Arrays.fill(zeroScratch, 0, silenceBytes, 0.toByte())
        }
        dest.put(zeroScratch, 0, silenceBytes)
    }

    class TapSession internal constructor(
        private val tapProcessor: TapAudioProcessor
    ) {
        val audioProcessor: AudioProcessor
            get() = tapProcessor

        fun wrapAudioSink(delegate: AudioSink): AudioSink {
            return SinkLagTrackingAudioSink(delegate, tapProcessor)
        }
    }

    fun createTapSession(startStaged: Boolean = false): TapSession {
        val processor = TapAudioProcessor(this, startStaged)
        if (startStaged) {
            synchronized(tapSelectionLock) {
                pendingTapProcessor?.markSuperseded()
                pendingTapProcessor = processor
            }
        } else {
            activeTapProcessor?.markSuperseded()
            activeTapProcessor = processor
        }
        return TapSession(processor)
    }

    fun createTapAudioProcessor(startStaged: Boolean = false): AudioProcessor {
        return createTapSession(startStaged).audioProcessor
    }

    private fun clearPendingIfSame(tap: TapAudioProcessor) {
        synchronized(tapSelectionLock) {
            if (pendingTapProcessor === tap) {
                pendingTapProcessor = null
            }
        }
    }

    private class SinkLagTrackingAudioSink(
        delegate: AudioSink,
        private val tapProcessor: TapAudioProcessor
    ) : ForwardingAudioSink(delegate) {

        private var configuredSampleRate: Int = 0
        private var inputBytesPerFrame: Int = 0
        private var isSinkPlaying: Boolean = false
        private var baselinePlayoutPosUs: Long = C.TIME_UNSET

        private fun resetAdvancingBaseline() {
            baselinePlayoutPosUs = C.TIME_UNSET
        }

        override fun setListener(listener: AudioSink.Listener) {
            super.setListener(object : AudioSink.Listener {
                override fun onPositionDiscontinuity() {
                    resetAdvancingBaseline()
                    listener.onPositionDiscontinuity()
                }

                override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                    listener.onPositionAdvancing(playoutStartSystemTimeMs)
                }

                override fun onUnderrun(
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long
                ) {
                    resetAdvancingBaseline()
                    listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
                }

                override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
                    listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
                }

                override fun onOffloadBufferEmptying() {
                    listener.onOffloadBufferEmptying()
                }

                override fun onOffloadBufferFull() {
                    listener.onOffloadBufferFull()
                }

                override fun onAudioSinkError(audioSinkError: Exception) {
                    listener.onAudioSinkError(audioSinkError)
                }

                override fun onAudioCapabilitiesChanged() {
                    resetAdvancingBaseline()
                    listener.onAudioCapabilitiesChanged()
                }

                override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                    resetAdvancingBaseline()
                    listener.onAudioTrackInitialized(audioTrackConfig)
                }

                override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                    resetAdvancingBaseline()
                    listener.onAudioTrackReleased(audioTrackConfig)
                }

                override fun onSilenceSkipped() {
                    listener.onSilenceSkipped()
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    listener.onAudioSessionIdChanged(audioSessionId)
                }
            })
        }

        override fun configure(
            inputFormat: Format,
            specifiedBufferSize: Int,
            outputChannels: IntArray?
        ) {
            resetAdvancingBaseline()
            val isLinearPcm = inputFormat.sampleMimeType == null ||
                MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
            val encoding = if (inputFormat.pcmEncoding != Format.NO_VALUE) {
                inputFormat.pcmEncoding
            } else {
                C.ENCODING_PCM_16BIT
            }
            val bytesPerSample = if (isLinearPcm) pcmBytesPerSample(encoding) else 0
            if (bytesPerSample > 0 && inputFormat.sampleRate > 0 && inputFormat.channelCount > 0) {
                configuredSampleRate = inputFormat.sampleRate
                inputBytesPerFrame = bytesPerSample * inputFormat.channelCount
            } else {
                configuredSampleRate = 0
                inputBytesPerFrame = 0
            }
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }

        override fun play() {
            isSinkPlaying = true
            super.play()
        }

        override fun pause() {
            isSinkPlaying = false
            resetAdvancingBaseline()
            super.pause()
        }

        override fun handleDiscontinuity() {
            resetAdvancingBaseline()
            super.handleDiscontinuity()
        }

        override fun flush() {
            resetAdvancingBaseline()
            super.flush()
        }

        override fun reset() {
            isSinkPlaying = false
            resetAdvancingBaseline()
            super.reset()
        }

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            val bytesBefore = buffer.remaining()
            val fullyHandled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            if (fullyHandled &&
                bytesBefore > 0 &&
                isSinkPlaying &&
                presentationTimeUs != C.TIME_UNSET &&
                configuredSampleRate > 0 &&
                inputBytesPerFrame > 0
            ) {
                val playoutPosUs = getCurrentPositionUs(false)
                if (playoutPosUs != AudioSink.CURRENT_POSITION_NOT_SET) {
                    val baseline = baselinePlayoutPosUs
                    if (baseline == C.TIME_UNSET || playoutPosUs < baseline) {
                        baselinePlayoutPosUs = playoutPosUs
                    } else if (playoutPosUs - baseline >= MIN_ADVANCING_PLAYOUT_DELTA_US) {
                        val frames = bytesBefore / inputBytesPerFrame
                        val bufferDurationUs = (frames.toLong() * 1_000_000L) / configuredSampleRate
                        val writeHeadPtsUs = presentationTimeUs + bufferDurationUs
                        val sinkLagMs = ((writeHeadPtsUs - playoutPosUs) / 1000L).toInt()
                        tapProcessor.onSinkLagMeasured(sinkLagMs)
                    }
                }
            }
            return fullyHandled
        }

        private fun pcmBytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }

    internal class TapAudioProcessor(
        private val bridge: RtmpAudioBridgeCore,
        startStaged: Boolean
    ) : BaseAudioProcessor() {

        private val stagingLock = Any()
        @Volatile
        private var isPromoted = !startStaged
        @Volatile
        private var isSuperseded = false
        private var isDiscarded = false
        private var stagedFormat: Media3AudioFormat? = null
        private val stagedBuffers = kotlin.collections.ArrayDeque<ByteBuffer>()
        private var stagedBytes = 0
        private var stagedSinkLagMs: Int? = null

        fun markSuperseded() {
            isSuperseded = true
        }

        fun onSinkLagMeasured(sinkLagMs: Int) {
            if (sinkLagMs < MIN_PLAUSIBLE_SINK_LAG_MS || sinkLagMs > MAX_PLAUSIBLE_SINK_LAG_MS) {
                return
            }
            synchronized(stagingLock) {
                if (isSuperseded || isDiscarded) return
                if (isPromoted) {
                    if (bridge.activeTapProcessor === this) {
                        bridge.onSinkLagMeasuredInternal(sinkLagMs, this)
                    }
                } else {
                    stagedSinkLagMs = sinkLagMs
                }
            }
        }

        fun promoteToActive(): Boolean {
            synchronized(stagingLock) {
                if (isPromoted || isDiscarded || isSuperseded) return false
                isPromoted = true
                bridge.activeTapProcessor?.let { previous ->
                    if (previous !== this) {
                        previous.markSuperseded()
                    }
                }
                bridge.activeTapProcessor = this
                bridge.clear()
                stagedFormat?.let { bridge.onExoPlayerFormatChangedInternal(it, this) }
                // Apply the staged sink lag BEFORE replaying pre-fill so the ring's target/max
                // already reflect high-latency sinks (e.g. Bluetooth A2DP); otherwise the replay
                // would overflow-trim against the default cushion and force a refill gap.
                stagedSinkLagMs?.let { bridge.onSinkLagMeasuredInternal(it, this) }
                while (stagedBuffers.isNotEmpty()) {
                    val chunk = stagedBuffers.removeFirst()
                    bridge.onExoPlayerAudioInputInternal(chunk, this)
                }
                stagedBytes = 0
                stagedFormat = null
                stagedSinkLagMs = null
                return true
            }
        }

        override fun onConfigure(inputAudioFormat: Media3AudioFormat): Media3AudioFormat {
            synchronized(stagingLock) {
                if (isSuperseded) return inputAudioFormat
                isDiscarded = false
                if (isPromoted) {
                    bridge.activeTapProcessor = this
                    bridge.onExoPlayerFormatChangedInternal(inputAudioFormat, this)
                } else {
                    stagedFormat = inputAudioFormat
                    stagedBuffers.clear()
                    stagedBytes = 0
                    stagedSinkLagMs = null
                }
            }
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            synchronized(stagingLock) {
                if (!isSuperseded) {
                    if (isPromoted) {
                        if (bridge.activeTapProcessor === this) {
                            bridge.onExoPlayerAudioInputInternal(inputBuffer, this)
                        }
                    } else {
                        stageInputLocked(inputBuffer)
                    }
                }
            }
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
        }

        private fun stageInputLocked(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            val copy = ByteBuffer.allocate(remaining).order(inputBuffer.order())
            copy.put(inputBuffer.duplicate())
            copy.flip()
            stagedBuffers.addLast(copy)
            stagedBytes += remaining

            val fmt = stagedFormat
            val frameSize = (fmt?.bytesPerFrame ?: 0).takeIf { it > 0 } ?: 4
            val rate = (fmt?.sampleRate ?: 0).takeIf { it > 0 } ?: 48000
            // Retain enough pre-fill to satisfy the largest dynamic target (plus headroom) so a
            // high-latency sink measured during warm-up can be promoted without a silence gap.
            // promotePendingTap() trims back down to the actual target after replay.
            val maxBytes = ((rate.toLong() * MAX_STAGED_BUFFER_MS) / 1000L).toInt() * frameSize
            var excess = stagedBytes - maxBytes
            while (excess > 0 && stagedBuffers.isNotEmpty()) {
                val head = stagedBuffers.first()
                val headRemaining = head.remaining()
                if (excess >= headRemaining) {
                    stagedBuffers.removeFirst()
                    stagedBytes -= headRemaining
                    excess -= headRemaining
                } else {
                    val alignedDrop = (excess / frameSize) * frameSize
                    if (alignedDrop > 0) {
                        head.position(head.position() + alignedDrop)
                        stagedBytes -= alignedDrop
                    }
                    break
                }
            }
        }

        override fun onFlush() {
            synchronized(stagingLock) {
                stagedBuffers.clear()
                stagedBytes = 0
                stagedSinkLagMs = null
                if (isPromoted && !isSuperseded && bridge.activeTapProcessor === this) {
                    // Keep target config and conversion pipeline; clear stale buffered samples on seek/flush
                    bridge.clear()
                }
            }
        }

        override fun onReset() {
            synchronized(stagingLock) {
                stagedBuffers.clear()
                stagedBytes = 0
                stagedFormat = null
                stagedSinkLagMs = null
                isDiscarded = true
                if (isPromoted && bridge.activeTapProcessor === this) {
                    bridge.clear()
                    bridge.activeTapProcessor = null
                }
            }
            bridge.clearPendingIfSame(this)
        }
    }
}

object RtmpAudioBridge : RtmpAudioBridgeCore()
