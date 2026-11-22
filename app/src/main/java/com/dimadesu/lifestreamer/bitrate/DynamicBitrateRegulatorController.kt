package com.dimadesu.lifestreamer.bitrate

import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.encoders.IEncoder
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetricsTracker
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.createMetricsTracker
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * App-managed [IBitrateRegulatorController] for both SRT and RTMP endpoints.
 *
 * Compared to StreamPack's `IntervalBitrateRegulatorController`:
 * - Looks up `videoEncoder` / `audioEncoder` dynamically on each tick so it never holds a stale
 *   encoder reference.
 * - Executes ticks sequentially under a lock (preventing overlapping updates at 20 ms BelaBox cadence).
 * - Supports live [updateConfig] / [updateVideoBitrateRange] without recreating the regulator or
 *   dropping to 1 Mbps.
 * - Supports live [updateMode]: switches Moblin Fast <-> Slow in-place, and transfers warm state
 *   ([SrtRegulatorSnapshot]) when switching between Moblin SrtFight and BelaBox.
 * - Keeps its coroutine scope reusable across [stop] -> [start] transitions.
 */
class DynamicBitrateRegulatorController internal constructor(
    val sinkType: MediaSinkType,
    private val videoEncoderProvider: () -> IEncoder?,
    private val audioEncoderProvider: () -> IEncoder?,
    private val metricsTracker: EndpointMetricsTracker,
    initialRange: BitrateRange,
    initialMode: RegulatorMode = RegulatorMode.MOBLIN_FAST,
    private val moblinConfig: MoblinSrtFightConfig = MoblinSrtFightConfig(),
    coroutineDispatcher: CoroutineDispatcher,
    private val nanoTimeProvider: () -> Long = System::nanoTime
) : IBitrateRegulatorController {

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val stateLock = Any()

    private var currentRange: BitrateRange = initialRange
    private var currentMode: RegulatorMode = initialMode
    private var pollingTime: Duration = pollingTimeFor(sinkType, initialMode)
    private var activeRegulator: DynamicBitrateRegulator = createRegulator(
        sinkType = sinkType,
        mode = initialMode,
        range = initialRange,
        initialSnapshot = null
    )

    private var pollingJob: Job? = null
    private var isRunning: Boolean = false

    internal val currentRegulator: DynamicBitrateRegulator
        get() = synchronized(stateLock) { activeRegulator }

    internal val currentPollingTime: Duration
        get() = synchronized(stateLock) { pollingTime }

    override fun start() {
        synchronized(stateLock) {
            if (isRunning) return
            isRunning = true
            launchPollingLoopLocked()
        }
    }

    override fun stop() {
        synchronized(stateLock) {
            isRunning = false
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    /**
     * Updates the video bitrate range live without resetting trained regulator state.
     * Immediately clamps the encoder bitrate if it falls outside the new range while running.
     */
    fun updateConfig(config: BitrateRegulatorConfig) {
        updateVideoBitrateRange(BitrateRange.from(config))
    }

    fun updateVideoBitrateRange(range: BitrateRange) {
        synchronized(stateLock) {
            if (currentRange == range) return
            currentRange = range
            activeRegulator.updateVideoBitrateRange(range)
            if (isRunning) {
                videoEncoderProvider()?.let { encoder ->
                    val clamped = range.coerceIn(encoder.bitrate)
                    if (clamped != encoder.bitrate) {
                        encoder.bitrate = clamped
                    }
                }
            }
        }
    }

    /**
     * Updates the SRT regulator mode live:
     * - `MOBLIN_FAST <-> MOBLIN_SLOW`: mutates settings in-place on the same regulator instance.
     * - `MOBLIN_* <-> BELABOX`: swaps the regulator with warm [SrtRegulatorSnapshot] transfer and
     *   reschedules the polling loop to the new algorithm's tick interval.
     *
     * No-op for non-SRT sinks or when [newMode] matches the current mode.
     */
    fun updateMode(newMode: RegulatorMode) {
        synchronized(stateLock) {
            if (sinkType != MediaSinkType.SRT || currentMode == newMode) return

            val existing = activeRegulator
            if (existing is MoblinSrtFightBitrateRegulator && newMode != RegulatorMode.BELABOX) {
                existing.setSettings(newMode == RegulatorMode.MOBLIN_FAST)
                currentMode = newMode
                return
            }

            val snapshot = (existing as? SnapshotableSrtRegulator)?.exportSnapshot()
            currentMode = newMode
            activeRegulator = createRegulator(
                sinkType = sinkType,
                mode = newMode,
                range = currentRange,
                initialSnapshot = snapshot
            )
            pollingTime = pollingTimeFor(sinkType, newMode)
            if (isRunning) {
                launchPollingLoopLocked()
            }
        }
    }

    private fun launchPollingLoopLocked() {
        pollingJob?.cancel()
        val interval = pollingTime
        pollingJob = scope.launch {
            while (isActive) {
                delay(interval)
                synchronized(stateLock) {
                    // `synchronized` is not a suspension point, so a job cancelled (e.g. by a mode
                    // switch rescheduling the loop) while blocked on the lock would otherwise still
                    // run one stale tick against the newly installed regulator.
                    if (!isRunning || !isActive) return@synchronized
                    try {
                        runTickLocked()
                    } catch (e: Exception) {
                        // A single failed tick (e.g. stats read on a closing socket) must not kill
                        // the polling loop or escape to the thread's uncaught exception handler.
                        BitrateRegulatorLog.w(TAG, "Bitrate regulator tick failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun runTickLocked() {
        val videoEncoder = videoEncoderProvider() ?: return
        val audioBitrate = audioEncoderProvider()?.bitrate ?: 0
        activeRegulator.update(videoEncoder.bitrate, audioBitrate)
    }

    private fun applyVideoTargetBitrate(targetBitrate: Int) {
        // Called synchronously under stateLock from regulator updates/clamps.
        if (!isRunning) return
        val clamped = currentRange.coerceIn(targetBitrate)
        videoEncoderProvider()?.let { encoder ->
            encoder.bitrate = clamped
        }
    }

    private fun createRegulator(
        sinkType: MediaSinkType,
        mode: RegulatorMode,
        range: BitrateRange,
        initialSnapshot: SrtRegulatorSnapshot?
    ): DynamicBitrateRegulator {
        return when (sinkType) {
            MediaSinkType.SRT -> when (mode) {
                RegulatorMode.BELABOX -> BelaboxSrtBelaRegulator(
                    metricsTracker = metricsTracker,
                    initialVideoBitrateRange = range,
                    onVideoTargetBitrateChange = ::applyVideoTargetBitrate,
                    initialSnapshot = initialSnapshot,
                    nanoTimeProvider = nanoTimeProvider
                )
                RegulatorMode.MOBLIN_FAST, RegulatorMode.MOBLIN_SLOW -> MoblinSrtFightBitrateRegulator(
                    metricsTracker = metricsTracker,
                    initialVideoBitrateRange = range,
                    moblinConfig = moblinConfig,
                    onVideoTargetBitrateChange = ::applyVideoTargetBitrate,
                    initialSnapshot = initialSnapshot,
                    nanoTimeProvider = nanoTimeProvider
                ).apply {
                    setSettings(mode == RegulatorMode.MOBLIN_FAST)
                }
            }
            MediaSinkType.RTMP -> RtmpSendDurationBitrateRegulator(
                metricsTracker = metricsTracker,
                initialVideoBitrateRange = range,
                onVideoTargetBitrateChange = ::applyVideoTargetBitrate,
                nanoTimeProvider = nanoTimeProvider
            )
            else -> throw IllegalArgumentException("Unsupported sink type for bitrate regulation: $sinkType")
        }
    }

    companion object {
        private const val TAG = "DynamicBitrateCtrl"

        // iOS calls BelaBox on every ~20ms tick (internal timers gate decisions),
        // while SrtFight and RTMP are gated to every 200ms (is200MsTick = updateTickCount % 10 == 0).
        // Match those intervals so the EMA filters behave as designed.
        internal val BELABOX_POLLING_TIME: Duration = 20L.milliseconds
        internal val MOBLIN_POLLING_TIME: Duration = 200L.milliseconds
        internal val RTMP_POLLING_TIME: Duration = 200L.milliseconds

        internal fun pollingTimeFor(sinkType: MediaSinkType, mode: RegulatorMode): Duration =
            when (sinkType) {
                MediaSinkType.SRT -> when (mode) {
                    RegulatorMode.BELABOX -> BELABOX_POLLING_TIME
                    RegulatorMode.MOBLIN_FAST, RegulatorMode.MOBLIN_SLOW -> MOBLIN_POLLING_TIME
                }
                MediaSinkType.RTMP -> RTMP_POLLING_TIME
                else -> RTMP_POLLING_TIME
            }
    }

    class Factory(
        val sinkType: MediaSinkType,
        private val bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
        private val initialMode: RegulatorMode = RegulatorMode.MOBLIN_FAST,
        private val moblinConfig: MoblinSrtFightConfig = MoblinSrtFightConfig()
    ) : IBitrateRegulatorController.Factory {

        /**
         * The controller created when this factory was assigned to `streamer.bitrateRegulatorControllerFactory`.
         * Stored on the factory so any component with access to the streamer can push live config/mode
         * updates without replacing the factory or resetting regulator state.
         *
         * Note: each [Factory] instance tracks a single controller and must only be attached to
         * one pipeline output at a time.
         */
        @Volatile
        var activeController: DynamicBitrateRegulatorController? = null
            private set

        override fun newBitrateRegulatorController(
            pipelineOutput: IEncodingPipelineOutput,
            coroutineDispatcher: CoroutineDispatcher
        ): IBitrateRegulatorController {
            require(pipelineOutput is IConfigurableVideoEncodingPipelineOutput) {
                "Pipeline output must be a video encoding output"
            }

            val audioOutput = pipelineOutput as? IConfigurableAudioEncodingPipelineOutput
            val metricsProvider = pipelineOutput.endpoint as WithEndpointMetrics<*>

            return DynamicBitrateRegulatorController(
                sinkType = sinkType,
                videoEncoderProvider = { pipelineOutput.videoEncoder },
                audioEncoderProvider = { audioOutput?.audioEncoder },
                metricsTracker = metricsProvider.createMetricsTracker(),
                initialRange = BitrateRange.from(bitrateRegulatorConfig),
                initialMode = initialMode,
                moblinConfig = moblinConfig,
                coroutineDispatcher = coroutineDispatcher
            ).also {
                activeController = it
            }
        }
    }
}
