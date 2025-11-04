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

/**
 * App-local MediaProjection-based audio source that captures only app audio by UID filter.
 * This is a minimal implementation compatible with StreamPack's IAudioSourceInternal usage.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionAudioSource(
    val mediaProjection: MediaProjection  // Make public for factory comparison
) : AudioRecordSource(), Releasable {

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

    // Behavior (configure/start/stop/fill/get) is provided by AudioRecordSource.
}

@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionAudioSourceFactory(
    private val mediaProjection: MediaProjection
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        return MediaProjectionAudioSource(mediaProjection)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // Check if it's the same type AND the same MediaProjection instance
        // This is important because MediaProjection tokens expire and need to be replaced
        return source is MediaProjectionAudioSource && source.mediaProjection === mediaProjection
    }
}
