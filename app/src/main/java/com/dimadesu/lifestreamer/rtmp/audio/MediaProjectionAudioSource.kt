package com.dimadesu.lifestreamer.rtmp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig

/**
 * App-local MediaProjection-based audio source that captures only app audio by UID filter.
 * Automatically detects and handles audio format conversion when RTMP stream format
 * differs from encoder format (similar to Moblin's AVAudioConverter approach).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionAudioSource(
    private val mediaProjection: MediaProjection
) : AudioRecordSource(), Releasable {

    companion object {
        private const val TAG = "MediaProjectionAudioSource"
    }

    private var audioConverter: AudioConverter? = null
    private var conversionCounter = 0L

    override suspend fun configure(config: AudioSourceConfig) {
        // Let parent configure the AudioRecord
        super.configure(config)
        
        // Check if we need format conversion by inspecting the actual AudioRecord format
        // vs the requested encoder format
        val audioRecord = getAudioRecordForInspection()
        if (audioRecord != null) {
            val captureRate = audioRecord.sampleRate
            val captureChannels = audioRecord.channelCount
            val encoderRate = config.sampleRate
            val encoderChannels = AudioConfig.getNumberOfChannels(config.channelConfig)
            
            if (captureRate != encoderRate || captureChannels != encoderChannels) {
                Logger.i(TAG, "Format mismatch detected - creating audio converter")
                Logger.i(TAG, "  Capture: $captureRate Hz, $captureChannels ch")
                Logger.i(TAG, "  Encoder: $encoderRate Hz, $encoderChannels ch")
                
                audioConverter = AudioConverter(
                    inputSampleRate = captureRate,
                    inputChannels = captureChannels,
                    outputSampleRate = encoderRate,
                    outputChannels = encoderChannels
                )
            } else {
                Logger.d(TAG, "Capture and encoder formats match ($captureRate Hz, $captureChannels ch) - no conversion needed")
                audioConverter = null
            }
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        // Get the original audio frame from parent
        val originalFrame = super.fillAudioFrame(frame)
        
        // If no conversion needed, just return it
        val converter = audioConverter ?: return originalFrame
        
        try {
            // Extract audio data from the frame buffer
            val buffer = originalFrame.rawBuffer
            buffer.rewind()
            val inputData = ByteArray(buffer.remaining())
            buffer.get(inputData)
            
            // Convert the audio
            val outputData = converter.convert(inputData)
            
            // Put converted data back into the frame
            buffer.clear()
            buffer.put(outputData)
            buffer.flip()
            
            conversionCounter++
            if (conversionCounter % 500 == 0L) {
                Logger.d(TAG, "Converted audio frame #$conversionCounter (${inputData.size} bytes -> ${outputData.size} bytes)")
            }
            
            return originalFrame
        } catch (e: Exception) {
            Logger.e(TAG, "Error converting audio frame: ${e.message}")
            throw e
        }
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val converter = audioConverter
        if (converter == null) {
            // No conversion needed, use parent implementation
            return super.getAudioFrame(frameFactory)
        }
        
        // Calculate the buffer size needed for output format
        val inputBufferSize = getBufferSizeForInspection() ?: 0
        val outputBufferSize = if (inputBufferSize > 0) {
            converter.getOutputBufferSize(inputBufferSize)
        } else {
            inputBufferSize
        }
        
        // Create frame with output buffer size
        val frame = frameFactory.create(outputBufferSize, 0)
        return fillAudioFrame(frame)
    }

    // let AudioRecordSource.configure handle buffer sizing and processor setup
    override fun buildAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(config.byteFormat)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelConfig)
            .build()

        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(
                AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUid(Process.myUid())
                    .build()
            )
            .build()
    }

    // Helper to access AudioRecord for inspection (uses reflection to access private field)
    private fun getAudioRecordForInspection(): AudioRecord? {
        return try {
            val field = AudioRecordSource::class.java.getDeclaredField("audioRecord")
            field.isAccessible = true
            field.get(this) as? AudioRecord
        } catch (e: Exception) {
            Logger.w(TAG, "Could not access AudioRecord for inspection: ${e.message}")
            null
        }
    }

    private fun getBufferSizeForInspection(): Int? {
        return try {
            val field = AudioRecordSource::class.java.getDeclaredField("bufferSize")
            field.isAccessible = true
            field.get(this) as? Int
        } catch (e: Exception) {
            null
        }
    }

    // Behavior (configure/start/stop) is provided by AudioRecordSource.
}

@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionAudioSourceFactory(
    private val mediaProjection: MediaProjection
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return MediaProjectionAudioSource(mediaProjection)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is MediaProjectionAudioSource
    }
}
