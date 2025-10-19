/*
 * Copyright (C) 2025 LifeStreamer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.rtmp.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable

/**
 * Audio source that captures app's own audio playback using AudioPlaybackCaptureConfiguration.
 * This avoids the MediaProjection permission popup while still capturing ExoPlayer audio.
 * 
 * Requires Android 10+ (API 29) and RECORD_AUDIO permission.
 * 
 * @param context Application context for UID matching
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioPlaybackCaptureSource(
    private val context: Context
) : AudioRecordSource(), Releasable {

    override fun buildAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        // Build AudioPlaybackCaptureConfiguration to capture only this app's audio
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(context)
            .addMatchingUid(context.applicationInfo.uid) // Only capture our own app's audio
            .build()

        // Convert StreamPack's AudioSourceConfig to Android's AudioFormat
        val audioFormat = AudioFormat.Builder()
            .setEncoding(config.byteFormat)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelConfig)
            .build()

        // Build AudioRecord with playback capture configuration
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }
}
