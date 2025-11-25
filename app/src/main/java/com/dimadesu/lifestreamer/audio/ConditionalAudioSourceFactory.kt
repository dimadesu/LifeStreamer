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
        // We prefer to avoid live-swapping audio sources. Return true to indicate the
        // currently configured source is acceptable (the service will handle routing).
        return true
    }
}
