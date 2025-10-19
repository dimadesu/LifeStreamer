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
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal

/**
 * Factory for creating AudioPlaybackCaptureSource instances.
 * This captures the app's own audio playback without requiring MediaProjection.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioPlaybackCaptureSourceFactory(
    private val context: Context
) : IAudioSourceInternal.Factory {

    override suspend fun create(context: Context): IAudioSourceInternal {
        return AudioPlaybackCaptureSource(this.context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is AudioPlaybackCaptureSource
    }
}
