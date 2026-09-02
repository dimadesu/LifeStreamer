package com.dimadesu.lifestreamer.utils

import android.util.Log
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import kotlinx.coroutines.flow.first

class StreamConfigurationHelper(private val storageRepository: DataStoreRepository) {

    suspend fun applySafeInitialBitrate(streamer: IVideoSingleStreamer?, tag: String) {
        if (streamer == null) return
        
        val regulatorConfig = storageRepository.bitrateRegulatorConfigFlow.first()
        if (regulatorConfig != null) {
            try {
                val startBitrate = 1_000_000.coerceIn(
                    regulatorConfig.videoBitrateRange.lower,
                    regulatorConfig.videoBitrateRange.upper
                )
                streamer.videoEncoder?.bitrate = startBitrate
                Log.i(tag, "applySafeInitialBitrate: Restored initial video config bitrate to regulator safe start target (${startBitrate/1000}kbps) directly on encoder")
            } catch (e: Exception) {
                Log.w(tag, "applySafeInitialBitrate: Failed to restore video config: ${e.message}")
            }
        } else {
            storageRepository.videoConfigFlow.first()?.let { config ->
                try {
                    streamer.videoEncoder?.bitrate = config.startBitrate
                    Log.i(tag, "applySafeInitialBitrate: Restored initial video config bitrate directly on encoder")
                } catch (e: Exception) {
                    Log.w(tag, "applySafeInitialBitrate: Failed to restore video config: ${e.message}")
                }
            }
        }
    }
}
