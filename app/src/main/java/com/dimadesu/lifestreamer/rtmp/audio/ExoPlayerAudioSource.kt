package com.dimadesu.lifestreamer.rtmp.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Audio source that extracts PCM audio directly from ExoPlayer.
 * This bypasses MediaProjection and gives us direct access to decoded RTMP audio
 * with automatic format conversion.
 * 
 * Note: This is a simplified implementation that relies on ExoPlayer's built-in
 * resampling capabilities when audio is routed through the Android audio system.
 */
class ExoPlayerAudioSource(
    val exoPlayer: ExoPlayer,
    val audioProcessor: ExoPlayerAudioProcessor
) : IAudioSourceInternal {

    private var config: AudioSourceConfig? = null
    private var bufferSize: Int = 0
    
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()
    
    private var frameCounter = 0L

    override suspend fun configure(config: AudioSourceConfig) {
        this.config = config
        
        // Calculate buffer size (typical: 1024 samples * 2 bytes per sample * channels)
        val channelCount = when (config.channelConfig) {
            android.media.AudioFormat.CHANNEL_IN_MONO -> 1
            android.media.AudioFormat.CHANNEL_IN_STEREO -> 2
            else -> 2
        }
        val bytesPerSample = when (config.byteFormat) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        bufferSize = 1024 * bytesPerSample * channelCount
        
        // Configure the audio processor with target format
        audioProcessor.configure(config)
        
        Logger.i(TAG, "[DEBUG] Configured ExoPlayer audio source: ${config.sampleRate}Hz, $channelCount channels, buffer=$bufferSize bytes")
        Logger.i(TAG, "[DEBUG] Format details - byteFormat: ${config.byteFormat}, channelConfig: ${config.channelConfig}")
    }

    override suspend fun startStream() {
        if (_isStreamingFlow.value) {
            Logger.d(TAG, "Already streaming")
            return
        }
        
        audioProcessor.start()
        _isStreamingFlow.tryEmit(true)
        Logger.i(TAG, "[DEBUG] Started ExoPlayer audio stream - processor active: ${audioProcessor.isActive()}")
    }

    override suspend fun stopStream() {
        if (!_isStreamingFlow.value) {
            Logger.d(TAG, "Not streaming")
            return
        }
        
        audioProcessor.stop()
        _isStreamingFlow.tryEmit(false)
        Logger.i(TAG, "Stopped ExoPlayer audio stream")
    }

    override fun release() {
        audioProcessor.reset()
        _isStreamingFlow.tryEmit(false)
        config = null
        Logger.i(TAG, "Released ExoPlayer audio source")
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        requireNotNull(config) { "Audio source is not configured" }
        if (!_isStreamingFlow.value) {
            frame.close()
            throw IllegalStateException("Audio source is not streaming")
        }

        // Get audio data from the processor's buffer queue
        val audioData = try {
            audioProcessor.getNextAudioFrame(bufferSize)
        } catch (e: InterruptedException) {
            frame.close()
            Logger.e(TAG, "[DEBUG] Audio frame retrieval interrupted: ${e.message}")
            throw IllegalArgumentException("Audio frame retrieval interrupted: ${e.message}")
        }

        if (audioData != null) {
            val buffer = frame.rawBuffer
            buffer.put(audioData)
            buffer.flip()
            frame.timestampInUs = TimeUtils.currentTime()
            
            // Log every 100 frames to avoid spam
            if (frameCounter % 100 == 0L) {
                Logger.d(TAG, "[DEBUG] Got audio frame #$frameCounter: ${audioData.size} bytes, queue size: ${audioProcessor.getQueueSize()}")
            }
            frameCounter++
            
            return frame
        } else {
            frame.close()
            Logger.w(TAG, "[DEBUG] No audio data available from processor queue (timeout)")
            throw IllegalArgumentException("No audio data available")
        }
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        requireNotNull(config) { "Audio source is not configured" }
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }

    companion object {
        private const val TAG = "ExoPlayerAudioSource"
    }
}

/**
 * AudioProcessor that extracts PCM audio from ExoPlayer and buffers it for StreamPack.
 * This processor sits in ExoPlayer's audio rendering pipeline and intercepts decoded PCM audio.
 */
class ExoPlayerAudioProcessor : AudioProcessor {
        private var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
        
        // Initialize with default format (48kHz stereo PCM16) - will be updated by StreamPack if needed
        private var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat(
            48000, // 48kHz sample rate
            2,     // Stereo
            C.ENCODING_PCM_16BIT
        )
        
        private var targetConfig: AudioSourceConfig? = null
        private var processingActive = false
        
        // Buffer queue to hold audio frames
        private val audioQueue = ArrayBlockingQueue<ByteArray>(100)
        
        // Output buffer for queueInput/getOutput cycle
        private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
        private val outputBufferMutex = Mutex()
        
        private var queueInputCounter = 0L
        
        fun getQueueSize(): Int = audioQueue.size
        
        fun configure(config: AudioSourceConfig) {
            this.targetConfig = config
            
            // Convert AudioSourceConfig to ExoPlayer AudioFormat
            val channelCount = when (config.channelConfig) {
                android.media.AudioFormat.CHANNEL_IN_MONO -> 1
                android.media.AudioFormat.CHANNEL_IN_STEREO -> 2
                else -> 2
            }
            
            val encoding = when (config.byteFormat) {
                android.media.AudioFormat.ENCODING_PCM_16BIT -> C.ENCODING_PCM_16BIT
                android.media.AudioFormat.ENCODING_PCM_8BIT -> C.ENCODING_PCM_8BIT
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT
                else -> C.ENCODING_PCM_16BIT
            }
            
            // Update output format (this happens AFTER ExoPlayer already called configure())
            // But that's okay - ExoPlayer will handle any format differences
            outputAudioFormat = AudioProcessor.AudioFormat(
                config.sampleRate,
                channelCount,
                encoding
            )
            
            Log.i(TAG, "[DEBUG] AudioProcessor re-configured by StreamPack: ${config.sampleRate}Hz, $channelCount ch, encoding=$encoding")
            Log.i(TAG, "[DEBUG] Note: ExoPlayer already configured with initial format, will convert as needed")
        }
        
        fun start() {
            processingActive = true
            audioQueue.clear()
            queueInputCounter = 0L
            Log.i(TAG, "[DEBUG] Audio processor started - ready to receive audio from ExoPlayer")
        }
        
        fun stop() {
            processingActive = false
            audioQueue.clear()
            Log.i(TAG, "[DEBUG] Audio processor stopped - received $queueInputCounter frames total")
        }
        
        fun getNextAudioFrame(bufferSize: Int): ByteArray? {
            if (!processingActive) return null
            
            // Try to get audio from queue with timeout
            return audioQueue.poll(100, TimeUnit.MILLISECONDS)
        }

        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            this.inputAudioFormat = inputAudioFormat
            
            Log.i(TAG, "[DEBUG] ========== ExoPlayer AudioProcessor.configure() called ==========")
            Log.i(TAG, "[DEBUG] ExoPlayer input format: ${inputAudioFormat.sampleRate}Hz, ${inputAudioFormat.channelCount}ch, encoding=${inputAudioFormat.encoding}")
            Log.i(TAG, "[DEBUG] Will return output format: ${outputAudioFormat.sampleRate}Hz, ${outputAudioFormat.channelCount}ch, encoding=${outputAudioFormat.encoding}")
            
            val needsConversion = inputAudioFormat.sampleRate != outputAudioFormat.sampleRate || 
                                 inputAudioFormat.channelCount != outputAudioFormat.channelCount ||
                                 inputAudioFormat.encoding != outputAudioFormat.encoding
            Log.i(TAG, "[DEBUG] Format conversion needed: $needsConversion")
            
            if (needsConversion) {
                Log.i(TAG, "[DEBUG] ExoPlayer will automatically convert ${inputAudioFormat.sampleRate}Hz/${inputAudioFormat.channelCount}ch " +
                          "-> ${outputAudioFormat.sampleRate}Hz/${outputAudioFormat.channelCount}ch")
            }
            
            // Return the output format we want - ExoPlayer will handle resampling/conversion
            return outputAudioFormat
        }

        override fun isActive(): Boolean = processingActive

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!processingActive) {
                Log.w(TAG, "[DEBUG] queueInput called but processor not active")
                return
            }
            
            if (!inputBuffer.hasRemaining()) {
                Log.w(TAG, "[DEBUG] queueInput called with empty buffer")
                return
            }
            
            // Copy audio data from ExoPlayer's buffer
            val size = inputBuffer.remaining()
            val audioData = ByteArray(size)
            inputBuffer.get(audioData)
            
            // Add to queue (drop if queue is full to prevent blocking)
            val queued = audioQueue.offer(audioData)
            
            if (queueInputCounter % 100 == 0L) {
                Log.d(TAG, "[DEBUG] queueInput #$queueInputCounter: $size bytes, queued=$queued, queue size=${audioQueue.size}")
            }
            queueInputCounter++
            
            if (!queued) {
                Log.w(TAG, "[DEBUG] Audio queue full, dropping frame (size=$size)")
            }
            
            // Store as output buffer for getOutput()
            outputBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.nativeOrder())
        }

        override fun queueEndOfStream() {
            // No-op: we don't need to handle EOS for streaming
        }

        override fun getOutput(): ByteBuffer {
            // Return the processed buffer
            val result = outputBuffer
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return result
        }

        override fun isEnded(): Boolean = false

        override fun flush() {
            audioQueue.clear()
            outputBuffer = AudioProcessor.EMPTY_BUFFER
        }

        override fun reset() {
            audioQueue.clear()
            processingActive = false
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
            outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        }

    companion object {
        private const val TAG = "ExoPlayerAudioProcessor"
    }
}

/**
 * Factory for creating ExoPlayerAudioSource instances.
 * Must be created BEFORE the ExoPlayer is prepared, so the audio processor
 * can be attached to the rendering pipeline.
 */
class ExoPlayerAudioSourceFactory(
    private val exoPlayer: ExoPlayer,
    private val audioProcessor: ExoPlayerAudioProcessor
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return ExoPlayerAudioSource(exoPlayer, audioProcessor)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is ExoPlayerAudioSource && source.exoPlayer == exoPlayer
    }
}
