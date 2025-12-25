package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

/**
 * Audio source factory that handles mic-based audio with USB awareness.
 * 
 * @param forceUnprocessed When true, forces UNPROCESSED audio source regardless of USB detection
 *                         AND forces recreation (isSourceEquals returns false).
 *                         When false (default), auto-detects USB audio and chooses appropriately.
 * @param forceDefault When true, forces DEFAULT audio source (with AEC/NS effects) regardless of
 *                     USB detection AND forces recreation. Used when transitioning back from
 *                     UNPROCESSED to DEFAULT after BT toggle off.
 * 
 * Sources used:
 * - forceUnprocessed=true → MicrophoneSource(unprocessed=true) with no effects, forces recreation
 * - forceDefault=true → MicrophoneSource(unprocessed=false) with AEC+NS effects, forces recreation
 * - neither forced + USB detected → MicrophoneSource(unprocessed=true) with no effects
 * - neither forced + no USB → MicrophoneSource(unprocessed=false) with AEC+NS effects
 * 
 * SCO/Bluetooth is negotiated asynchronously by the service and will call
 * `setAudioSource(...)` to switch to BluetoothAudioSource only after SCO is confirmed.
 */
class ConditionalAudioSourceFactory(
    private val forceUnprocessed: Boolean = false,
    private val forceDefault: Boolean = false
) : IAudioSourceInternal.Factory {
    
    companion object {
        private const val TAG = "ConditionalAudioSrcFact"
    }

    override suspend fun create(context: Context): IAudioSourceInternal {
        val useUnprocessed = when {
            forceDefault -> false  // Force DEFAULT (processed) with effects
            forceUnprocessed -> true  // Force UNPROCESSED
            // TEMPORARILY DISABLED: Testing if system switches audio source automatically
            // else -> UsbAudioManager.hasUsbAudioInput(context)  // Auto-detect
            else -> false  // Always use DEFAULT (no auto-switching to UNPROCESSED for testing)
        }
        Log.i(TAG, "Creating microphone source (unprocessed=$useUnprocessed, forceUnprocessed=$forceUnprocessed, forceDefault=$forceDefault) - AUTO-DETECT DISABLED FOR TESTING")
        return MicrophoneSourceFactory(unprocessed = useUnprocessed).create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // When forced, always recreate to get fresh AudioRecord
        if (forceUnprocessed || forceDefault) return false
        
        // If source is already a microphone/AudioRecord source, no need to recreate
        // This allows BT switching to work since BT sources won't match
        val sourceName = source.javaClass.simpleName
        return sourceName.contains("AudioRecord", ignoreCase = true) ||
               sourceName.contains("Microphone", ignoreCase = true)
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceUnprocessed=$forceUnprocessed, forceDefault=$forceDefault)"
    }
}
