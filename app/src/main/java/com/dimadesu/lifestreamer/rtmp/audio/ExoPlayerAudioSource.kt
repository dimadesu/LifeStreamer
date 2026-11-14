package com.dimadesu.lifestreamer.rtmp.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioFrameSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.time.TimeUtils
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

/**
 * StreamPack audio source that pulls decoded, format-converted PCM audio from [RtmpAudioBridge]
 * at a real-time wall-clock pace and pads with silence whenever ExoPlayer is buffering or idle.
 */
@OptIn(InternalStreamPackApi::class)
class ExoPlayerAudioSource(
    private val bridge: RtmpAudioBridgeCore = RtmpAudioBridge
) : IAudioSourceInternal, IAudioFrameSourceInternal, Releasable {

    companion object {
        private const val TAG = "ExoPlayerAudioSource"
        private const val MAX_DRIFT_NS = 200_000_000L // 200 ms resync threshold
    }

    private var _bufferSize: Int = 3840 // default 20ms at 48kHz stereo 16-bit
    override val minBufferSize: Int
        get() = _bufferSize

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    var configuredConfig: AudioSourceConfig? = null
        private set

    private var sampleRate: Int = 48000
    private var bytesPerFrame: Int = 4
    private val isRunning = AtomicBoolean(false)
    private var nextReadTimeNs: Long = 0L

    override suspend fun configure(config: AudioSourceConfig) {
        configuredConfig = config
        sampleRate = config.sampleRate.coerceAtLeast(8000)

        val channelCount = when (config.channelConfig) {
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_OUT_MONO -> 1
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_OUT_STEREO -> 2
            else -> 2
        }
        val bytesPerSample = when (config.byteFormat) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> 2
        }
        bytesPerFrame = (channelCount * bytesPerSample).coerceAtLeast(2)

        // 20 ms frame chunk (50 chunks/sec)
        val framesPer20Ms = (sampleRate / 50).coerceAtLeast(160)
        val bytesPer20Ms = framesPer20Ms * bytesPerFrame

        val systemMinBuffer = try {
            AudioRecord.getMinBufferSize(config.sampleRate, config.channelConfig, config.byteFormat)
        } catch (_: Throwable) {
            -1
        }
        _bufferSize = if (systemMinBuffer > 0) {
            val chosen = max(bytesPer20Ms, systemMinBuffer)
            (chosen / bytesPerFrame) * bytesPerFrame
        } else {
            bytesPer20Ms
        }

        bridge.setTargetConfig(config.sampleRate, config.channelConfig, config.byteFormat)
        try {
            Log.i(
                TAG,
                "Configured ExoPlayerAudioSource: sampleRate=${config.sampleRate}, " +
                    "channels=$channelCount, bufferSize=$_bufferSize, bytesPer20Ms=$bytesPer20Ms"
            )
        } catch (_: Throwable) {
        }
    }

    override suspend fun startStream() {
        if (isRunning.getAndSet(true)) return
        nextReadTimeNs = System.nanoTime()
        _isStreamingFlow.tryEmit(true)
        try {
            Log.i(TAG, "ExoPlayerAudioSource started")
        } catch (_: Throwable) {
        }
    }

    override suspend fun stopStream() {
        if (!isRunning.getAndSet(false)) return
        nextReadTimeNs = 0L
        _isStreamingFlow.tryEmit(false)
        try {
            Log.i(TAG, "ExoPlayerAudioSource stopped")
        } catch (_: Throwable) {
        }
    }

    override fun release() {
        isRunning.set(false)
        nextReadTimeNs = 0L
        _isStreamingFlow.tryEmit(false)
    }

    override fun fillAudioFrame(buffer: ByteBuffer): Long {
        if (!isRunning.get()) {
            throw IllegalStateException("ExoPlayerAudioSource is not running")
        }

        val frameSize = bytesPerFrame.coerceAtLeast(2)
        // Prefer reading ~20ms chunks so pacing and latency stay tight even if _bufferSize is larger
        val targetChunkBytes = ((sampleRate / 50).coerceAtLeast(160)) * frameSize
        val maxWritable = buffer.remaining().let { if (it > 0) it else buffer.capacity() }
        val bytesToFill = (min(targetChunkBytes, maxWritable) / frameSize) * frameSize
        val framesToRead = (bytesToFill / frameSize).coerceAtLeast(1)
        val frameDurationNs = (framesToRead * 1_000_000_000L) / sampleRate

        val nowNs = System.nanoTime()
        val scheduledNs = nextReadTimeNs
        if (scheduledNs == 0L || nowNs - scheduledNs > MAX_DRIFT_NS) {
            nextReadTimeNs = nowNs + frameDurationNs
        } else {
            val waitNs = scheduledNs - nowNs
            if (waitNs > 0L) {
                LockSupport.parkNanos(waitNs)
            }
            nextReadTimeNs = scheduledNs + frameDurationNs
        }

        bridge.readPcm(buffer, bytesToFill)
        return TimeUtils.currentTime()
    }
}

@OptIn(InternalStreamPackApi::class)
class ExoPlayerAudioSourceFactory : IAudioSource.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return ExoPlayerAudioSource()
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is ExoPlayerAudioSource
    }
}
