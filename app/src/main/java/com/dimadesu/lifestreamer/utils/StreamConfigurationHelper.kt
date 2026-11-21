package com.dimadesu.lifestreamer.utils

import android.util.Log
import com.dimadesu.lifestreamer.bitrate.DynamicBitrateRegulatorController
import com.dimadesu.lifestreamer.bitrate.RegulatorMode
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
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
        val mode = if (sinkType == MediaSinkType.SRT) {
            storageRepository.regulatorModeFlow.first()
        } else {
            RegulatorMode.MOBLIN_FAST
        }
        return DynamicBitrateRegulatorController.Factory(
            sinkType = sinkType,
            bitrateRegulatorConfig = config,
            initialMode = mode
        )
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

    /**
     * Updates the active adaptive bitrate regulator mid-stream when settings change:
     * - If [config] is `null`, detaches any active regulator.
     * - If a [DynamicBitrateRegulatorController] for [sinkType] is already attached, applies
     *   [config] and (for SRT) [mode] in-place without resetting to 1 Mbps or losing trained state.
     * - Otherwise (e.g., adaptive bitrate was toggled ON mid-stream), attaches a fresh controller
     *   starting at the safe initial bitrate.
     */
    suspend fun updateBitrateRegulator(
        streamer: IVideoSingleStreamer?,
        sinkType: MediaSinkType,
        config: BitrateRegulatorConfig?,
        mode: RegulatorMode,
        tag: String
    ) {
        if (streamer == null) return
        if (sinkType != MediaSinkType.SRT && sinkType != MediaSinkType.RTMP) return

        if (config == null) {
            streamer.bitrateRegulatorControllerFactory = null
            // The regulator may have left the encoder anywhere in its range (often near the
            // minimum after congestion). Without regulation nothing would ever raise it again,
            // so restore the user's configured fixed video bitrate.
            applyConfiguredVideoBitrate(streamer, tag)
            Log.i(tag, "Bitrate regulator disabled mid-stream")
            return
        }

        val existingController =
            (streamer.bitrateRegulatorControllerFactory as? DynamicBitrateRegulatorController.Factory)
                ?.activeController

        if (existingController != null && existingController.sinkType == sinkType) {
            existingController.updateConfig(config)
            if (sinkType == MediaSinkType.SRT) {
                existingController.updateMode(mode)
            }
            Log.i(
                tag,
                "Bitrate regulator updated in-place: sink=$sinkType, range=${config.videoBitrateRange.lower / 1000}k-${config.videoBitrateRange.upper / 1000}k, mode=$mode"
            )
            return
        }

        applySafeInitialBitrate(streamer, tag)
        streamer.bitrateRegulatorControllerFactory = DynamicBitrateRegulatorController.Factory(
            sinkType = sinkType,
            bitrateRegulatorConfig = config,
            initialMode = mode
        )
        Log.i(
            tag,
            "Bitrate regulator attached mid-stream: sink=$sinkType, range=${config.videoBitrateRange.lower / 1000}k-${config.videoBitrateRange.upper / 1000}k, mode=$mode"
        )
    }

    suspend fun validateLocalRecordingCompatibility(): String? {
        val endpointType = storageRepository.endpointTypeFlow.first()
        if (!endpointType.isStreaming) return null
        val localConfig = storageRepository.localRecordingConfigFlow.first() ?: return null
        val videoConfig = storageRepository.videoConfigFlow.first()
        val audioConfig = storageRepository.audioConfigFlow.first()
        return LocalRecordingCompatibilityValidator.validate(
            extension = localConfig.extension,
            videoMimeType = videoConfig?.mimeType,
            audioMimeType = audioConfig?.mimeType,
            endpointType = endpointType
        )
    }

    suspend fun applyConfiguredVideoBitrate(streamer: IVideoSingleStreamer?, tag: String) {
        if (streamer == null) return
        storageRepository.videoConfigFlow.first()?.let { config ->
            try {
                streamer.videoEncoder?.bitrate = config.startBitrate
                Log.i(tag, "applyConfiguredVideoBitrate: Applied configured video bitrate (${config.startBitrate / 1000}kbps)")
            } catch (e: Exception) {
                Log.w(tag, "applyConfiguredVideoBitrate: Failed to apply configured video bitrate: ${e.message}")
            }
        }
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
