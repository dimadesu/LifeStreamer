package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal

/**
 * Audio source factory that handles mic-based audio with USB awareness.
 * 
 * @param forceUnprocessed When true, forces UnprocessedAudioSource regardless of USB detection.
 *                         Use this when switching to USB video to force audio reinitialization.
 *                         When false (default), auto-detects USB audio and chooses appropriately.
 * 
 * Sources used:
 * - forceUnprocessed=true → UnprocessedAudioSource (our custom, UNPROCESSED mode)
 * - forceUnprocessed=false + USB detected → UnprocessedAudioSource
 * - forceUnprocessed=false + no USB → MicrophoneSource (StreamPack, DEFAULT mode)
 * 
 * SCO/Bluetooth is negotiated asynchronously by the service and will call
 * `setAudioSource(...)` to switch to BluetoothAudioSource only after SCO is confirmed.
 */
class ConditionalAudioSourceFactory(
    private val forceUnprocessed: Boolean = false
) : IAudioSourceInternal.Factory {
    private val TAG = "ConditionalAudioSourceFactory"

    override suspend fun create(context: Context): IAudioSourceInternal {
        return if (forceUnprocessed) {
            Log.i(TAG, "Forced UNPROCESSED mode → creating UnprocessedAudioSource")
            UnprocessedAudioSourceFactory().create(context)
        } else {
            // Delegate to SmartMicrophoneSourceFactory which auto-detects USB
            Log.i(TAG, "Auto-detect mode → delegating to SmartMicrophoneSourceFactory")
            SmartMicrophoneSourceFactory().create(context)
        }
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        if (forceUnprocessed) {
            // Forced mode only matches UnprocessedAudioSource
            return source is UnprocessedAudioSource
        } else {
            // Auto-detect mode matches either MicrophoneSource (StreamPack) or UnprocessedAudioSource
            // MicrophoneSource is internal in StreamPack, so check by class name
            val className = source.javaClass.simpleName
            return source is UnprocessedAudioSource || 
                   className == "MicrophoneSource" ||
                   className.contains("AudioRecord", ignoreCase = true)
        }
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceUnprocessed=$forceUnprocessed)"
    }
}
