package com.dimadesu.lifestreamer.utils

import android.util.Log
import com.dimadesu.lifestreamer.bitrate.AdaptiveSrtBitrateRegulatorController
import com.dimadesu.lifestreamer.bitrate.RtmpSendDurationBitrateRegulator
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
import io.github.thibaultbee.streampack.core.regulator.controllers.intervalBitrateRegulatorControllerFactory
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import kotlinx.coroutines.flow.first

class StreamConfigurationHelper(private val storageRepository: DataStoreRepository) {

    /**
     * Builds the adaptive bitrate regulator factory for [sinkType] from stored settings, or
     * `null` if adaptive bitrate is disabled for that sink type (or the sink type isn't SRT/RTMP).
     */
    suspend fun buildBitrateRegulatorFactoryOrNull(sinkType: MediaSinkType): IBitrateRegulatorController.Factory? {
        if (sinkType != MediaSinkType.SRT && sinkType != MediaSinkType.RTMP) return null
        val config = storageRepository.bitrateRegulatorConfigFlow.first() ?: return null
        return if (sinkType == MediaSinkType.SRT) {
            val mode = storageRepository.regulatorModeFlow.first()
            AdaptiveSrtBitrateRegulatorController.Factory(bitrateRegulatorConfig = config, mode = mode)
        } else {
            intervalBitrateRegulatorControllerFactory(
                bitrateRegulatorFactory = RtmpSendDurationBitrateRegulator.Factory(),
                bitrateRegulatorConfig = config
            )
        }
    }

    /**
     * Attaches a fresh adaptive bitrate regulator to [streamer] for [sinkType], resetting the
     * encoder to the safe starting bitrate first so the regulator's first tick ramps up from
     * 1 Mbps instead of cliff-dropping down from whatever bitrate was already active. Detaches
     * any existing regulator (sets factory to `null`) if adaptive bitrate is disabled.
     */
    suspend fun attachBitrateRegulator(streamer: IVideoSingleStreamer?, sinkType: MediaSinkType, tag: String) {
        if (streamer == null) return
        val factory = buildBitrateRegulatorFactoryOrNull(sinkType)
        if (factory != null) {
            applySafeInitialBitrate(streamer, tag)
        }
        streamer.bitrateRegulatorControllerFactory = factory
    }

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
