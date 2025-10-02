package com.dimadesu.lifestreamer.rtmp.audio.buffer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Custom AudioTrackProvider that creates FakeAudioTrack instances instead of real AudioTracks.
 * This allows intercepting all PCM data that would normally go to the platform AudioTrack
 * and routing it into CircularPcmBuffer for the StreamPack encoder.
 */
class CircularBufferAudioTrackProvider(
    private val audioBuffer: CircularPcmBuffer
) : DefaultAudioSink.AudioTrackProvider {

    private val TAG = "CircularBufferAudioTrackProvider"


    override fun getAudioTrack(p0: AudioSink.AudioTrackConfig, p1: AudioAttributes, p2: Int, p3: Context?): AudioTrack {
        Log.d(TAG, "Creating FakeAudioTrack: " +
            "encoding=${p0.encoding}, " +
            "sampleRate=${p0.sampleRate}, " +
            "channelConfig=${p0.channelConfig}, " +
            "bufferSize=${p0.bufferSize}")

        // Update the circular buffer with the correct audio format
        audioBuffer.updateFormat(
            sampleRate = p0.sampleRate,
            channelCount = audioTrackConfigToChannelCount(p0.channelConfig),
            bytesPerSample = encodingToBytesPerSample(p0.encoding)
        )

        // Convert AudioTrackConfig to AudioFormat
        val audioFormat = AudioFormat.Builder()
            .setEncoding(p0.encoding)
            .setSampleRate(p0.sampleRate)
            .setChannelMask(p0.channelConfig)
            .build()

        // Convert Media3 AudioAttributes to Android AudioAttributes
        val androidAudioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(p1.usage)
            .setContentType(p1.contentType)
            .build()

        return FakeAudioTrack(
            audioBuffer = audioBuffer,
            audioAttributes = androidAudioAttributes,
            audioFormat = audioFormat,
            bufferSizeInBytes = p0.bufferSize,
            mode = AudioTrack.MODE_STREAM,
            sessionId = p2
        )
    }

    /**
     * Convert channel configuration to channel count
     */
    private fun audioTrackConfigToChannelCount(channelConfig: Int): Int {
        return when (channelConfig) {
            AudioFormat.CHANNEL_OUT_MONO -> 1
            AudioFormat.CHANNEL_OUT_STEREO -> 2
            AudioFormat.CHANNEL_OUT_5POINT1 -> 6
            AudioFormat.CHANNEL_OUT_7POINT1 -> 8
            else -> {
                // For other configurations, use bit counting
                Integer.bitCount(channelConfig)
            }
        }
    }

    /**
     * Convert audio encoding to bytes per sample
     */
    private fun encodingToBytesPerSample(encoding: Int): Int {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2 // Default to 16-bit
        }
    }
}