package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import kotlinx.coroutines.flow.first

/**
 * Audio source factory that creates microphone audio source based on settings from DataStore.
 * Reads audio source type from user preferences.
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
        // Read settings from DataStore
        val dataStoreRepository = DataStoreRepository(context, context.dataStore)
        
        val audioSourceType = dataStoreRepository.audioSourceTypeFlow.first()
        
        Log.i(TAG, "Creating microphone source with audioSourceType=$audioSourceType (forceDefault=$forceDefault)")
        
        // Explicitly pass emptySet() to disable effects (MicrophoneSourceFactory enables AEC+NS by default)
        return MicrophoneSourceFactory(
            audioSourceType = audioSourceType,
            effects = emptySet()
        ).create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // When forced, always recreate to get fresh AudioRecord
        if (forceDefault) return false
        
        // Always return false to force recreation when settings may have changed
        // This ensures the new audio source type is applied
        return false
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceDefault=$forceDefault)"
    }
}
