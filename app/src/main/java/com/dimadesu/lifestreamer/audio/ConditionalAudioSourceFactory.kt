package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Audio source factory that creates microphone audio source based on settings from DataStore.
 * Reads audio source type and effects (NS, AEC, AGC) from user preferences.
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
        val enableNs = dataStoreRepository.audioEffectNsFlow.first()
        val enableAec = dataStoreRepository.audioEffectAecFlow.first()
        val enableAgc = dataStoreRepository.audioEffectAgcFlow.first()
        
        // Build effects set based on settings
        val effects = mutableSetOf<UUID>()
        if (enableNs) {
            effects.add(NoiseSuppressor.EFFECT_TYPE_NS)
        }
        if (enableAec) {
            effects.add(AcousticEchoCanceler.EFFECT_TYPE_AEC)
        }
        if (enableAgc) {
            effects.add(AutomaticGainControl.EFFECT_TYPE_AGC)
        }
        
        Log.i(TAG, "Creating microphone source with audioSourceType=$audioSourceType, effects: NS=$enableNs, AEC=$enableAec, AGC=$enableAgc (forceDefault=$forceDefault)")
        
        return MicrophoneSourceFactory(
            audioSourceType = audioSourceType,
            effects = effects
        ).create(context)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // When forced, always recreate to get fresh AudioRecord
        if (forceDefault) return false
        
        // Always return false to force recreation when settings may have changed
        // This ensures the new audio source type and effects are applied
        return false
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceDefault=$forceDefault)"
    }
}
