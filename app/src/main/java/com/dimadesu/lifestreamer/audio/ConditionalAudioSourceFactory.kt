package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal

/**
 * Audio source factory that handles mic-based audio with USB awareness.
 * 
 * @param forceUnprocessed When true, forces UNPROCESSED mode regardless of USB detection.
 *                         Use this when switching to USB video to force audio reinitialization.
 *                         When false (default), auto-detects USB audio and chooses appropriately.
 */
class ConditionalAudioSourceFactory(
    private val forceUnprocessed: Boolean = false
) : IAudioSourceInternal.Factory {
    private val TAG = "ConditionalAudioSourceFactory"

    override suspend fun create(context: Context): IAudioSourceInternal {
        // Use SmartMicrophoneSource which automatically detects USB audio and selects
        // the optimal audio source type:
        // - USB audio → UNPROCESSED (raw capture, no DSP)
        // - Built-in mic → DEFAULT (with system noise cancellation)
        // 
        // When forceUnprocessed=true, bypasses auto-detection and forces UNPROCESSED.
        // This is needed when switching to USB video to force audio reinitialization.
        // 
        // SCO/Bluetooth is negotiated asynchronously by the service and will call
        // `setAudioSource(...)` to switch to BluetoothAudioSource only after SCO is confirmed.
        return if (forceUnprocessed) {
            Log.i(TAG, "Creating SmartMicrophoneSource with FORCED UNPROCESSED (USB video mode)")
            SmartMicrophoneSource(context, MediaRecorder.AudioSource.UNPROCESSED)
        } else {
            Log.i(TAG, "Creating SmartMicrophoneSource (auto-detect mode, deferred BT switch)")
            SmartMicrophoneSource(context)
        }
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // Check if source is SmartMicrophoneSource and matches our mode
        if (source is SmartMicrophoneSource) {
            // Forced mode only matches forced sources, auto-detect only matches auto-detect
            return source.isForced == forceUnprocessed
        }
        
        // For backward compatibility with other mic sources (only in auto-detect mode)
        if (!forceUnprocessed) {
            val sourceName = source.javaClass.simpleName
            return sourceName.contains("AudioRecord", ignoreCase = true) ||
                   sourceName.contains("Microphone", ignoreCase = true)
        }
        
        return false
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceUnprocessed=$forceUnprocessed)"
    }
}
