package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

/**
 * Audio source factory that creates microphone audio source with DEFAULT audio source and effects (AEC+NS).
 * 
 * @param forceDefault When true, forces recreation (isSourceEquals returns false).
 *                     Used when transitioning between audio sources to ensure fresh AudioRecord.
 */
class ConditionalAudioSourceFactory(
    private val forceDefault: Boolean = false
) : IAudioSourceInternal.Factory {
    
    companion object {
        private const val TAG = "ConditionalAudioSrcFact"
    }

    override suspend fun create(context: Context): IAudioSourceInternal {
        // Always use DEFAULT audio source with effects (AEC+NS)
        Log.i(TAG, "Creating microphone source with DEFAULT audio source and effects (forceDefault=$forceDefault)")
        return MicrophoneSourceFactory(unprocessed = false).create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // When forced, always recreate to get fresh AudioRecord
        if (forceDefault) return false
        
        // If source is already a microphone/AudioRecord source, no need to recreate
        val sourceName = source.javaClass.simpleName
        return sourceName.contains("AudioRecord", ignoreCase = true) ||
               sourceName.contains("Microphone", ignoreCase = true)
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceDefault=$forceDefault)"
    }
}
