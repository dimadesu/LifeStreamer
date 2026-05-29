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
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.IMediaProjectionSource

/**
 * App-local MediaProjection-based audio source.
 *
 * When [captureFullPhone] is false (default): captures only this app's own audio via UID filter.
 * When [captureFullPhone] is true: captures all phone audio (media, games, unknown usage).
 *
 * Implements IMediaProjectionSource so USB audio manager can detect when screen audio is in use.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionAudioSource(
    override val mediaProjection: MediaProjection,
    private val captureFullPhone: Boolean = false
) : AudioRecordSource(), Releasable, IMediaProjectionSource {

    override fun buildAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(config.byteFormat)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelConfig)
            .build()

        val captureConfig = if (captureFullPhone) {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } else {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUid(Process.myUid())
                .build()
        }

        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionAudioSourceFactory(
    private val mediaProjection: MediaProjection,
    private val captureFullPhone: Boolean = false
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return MediaProjectionAudioSource(mediaProjection, captureFullPhone)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // Always return false to force AudioRecord recreation.
        // When ExoPlayer is replaced (RTMP reconnect), Android's AudioPlaybackCapture
        // routing may not update for the existing AudioRecord, causing permanent silence.
        // Recreating the AudioRecord guarantees fresh capture routing.
        return false
    }
}
