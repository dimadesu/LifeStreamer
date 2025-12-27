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
 * Always forces recreation to ensure fresh AudioRecord with current settings.
 */
class ConditionalAudioSourceFactory : IAudioSourceInternal.Factory {
    
    companion object {
        private const val TAG = "ConditionalAudioSrcFact"
    }

    override suspend fun create(context: Context): IAudioSourceInternal {
        // Read settings from DataStore
        val dataStoreRepository = DataStoreRepository(context, context.dataStore)
        
        val audioSourceType = dataStoreRepository.audioSourceTypeFlow.first()
        
        Log.i(TAG, "Creating microphone source with audioSourceType=$audioSourceType")
        
        // Explicitly pass emptySet() to disable effects (MicrophoneSourceFactory enables AEC+NS by default)
        return MicrophoneSourceFactory(
            audioSourceType = audioSourceType,
            effects = emptySet()
        ).create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // Always return false to force recreation
        // This ensures the current audio source type from settings is applied
        return false
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory()"
    }
}
