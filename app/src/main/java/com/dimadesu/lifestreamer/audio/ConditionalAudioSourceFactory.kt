package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal

class ConditionalAudioSourceFactory : IAudioSourceInternal.Factory {
    private val TAG = "ConditionalAudioSourceFactory"

    override suspend fun create(context: Context): IAudioSourceInternal {
        // Use SmartMicrophoneSource which automatically detects USB audio and selects
        // the optimal audio source type:
        // - USB audio → UNPROCESSED (raw capture, no DSP)
        // - Built-in mic → DEFAULT (with system noise cancellation)
        // 
        // SCO/Bluetooth is negotiated asynchronously by the service and will call
        // `setAudioSource(...)` to switch to BluetoothAudioSource only after SCO is confirmed.
        Log.i(TAG, "Conditional factory invoked: creating SmartMicrophoneSource (USB-aware, deferred BT switch)")
        return SmartMicrophoneSourceFactory().create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // If source is already a microphone/AudioRecord/SmartMicrophone source, no need to recreate
        // This allows BT switching to work since BT sources won't match
        val sourceName = source.javaClass.simpleName
        return sourceName.contains("AudioRecord", ignoreCase = true) ||
               sourceName.contains("Microphone", ignoreCase = true) ||
               sourceName.contains("SmartMicrophone", ignoreCase = true)
    }
}
