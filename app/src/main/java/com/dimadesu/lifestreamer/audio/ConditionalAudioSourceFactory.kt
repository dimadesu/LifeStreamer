package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

class ConditionalAudioSourceFactory : IAudioSourceInternal.Factory {
    private val TAG = "ConditionalAudioSourceFactory"

    override suspend fun create(context: Context): IAudioSourceInternal {
        // Always create the system microphone source at streamer creation time.
        // SCO is negotiated asynchronously by the service and the service will
        // call `setAudioSource(...)` to switch to the Bluetooth source only after
        // SCO is confirmed. Choosing Bluetooth at creation time can produce
        // degraded audio if SCO is not yet connected (some devices expose
        // output-only A2DP devices or the SCO path isn't established yet).
        Log.i(TAG, "Conditional factory invoked: creating system microphone source (deferred BT switch)")
        return MicrophoneSourceFactory().create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // If source is already a microphone/AudioRecord source, no need to recreate
        // This allows BT switching to work since BT sources won't match
        val sourceName = source.javaClass.simpleName
        return sourceName.contains("AudioRecord", ignoreCase = true) ||
               sourceName.contains("Microphone", ignoreCase = true)
    }
}
