package com.dimadesu.lifestreamer.bitrate

import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.regulator.controllers.BitrateRegulatorController
import io.github.thibaultbee.streampack.core.regulator.controllers.DummyBitrateRegulatorController
import io.github.thibaultbee.streampack.ext.srt.regulator.SrtBitrateRegulator

/**
 * A neutral BitrateRegulatorController implementation that can create either
 * the Moblin SrtFight regulator (fast/slow) or the Belabox regulator depending
 * on the selected `RegulatorMode`.
 */
class AdaptiveSrtBitrateRegulatorController {
    class Factory(
        private val bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
        private val moblinConfig: MoblinSrtFightConfig = MoblinSrtFightConfig(),
        private val delayTimeInMs: Long = 200, // Moblin updates every 200ms
        private val mode: RegulatorMode = RegulatorMode.MOBLIN_FAST
    ) : BitrateRegulatorController.Factory() {
        override fun newBitrateRegulatorController(pipelineOutput: IEncodingPipelineOutput): DummyBitrateRegulatorController {
            require(pipelineOutput is IConfigurableVideoEncodingPipelineOutput) {
                "Pipeline output must be an video encoding output"
            }

            val videoEncoder = requireNotNull(pipelineOutput.videoEncoder) {
                "Video encoder must be set"
            }

            val audioEncoder = if (pipelineOutput is IConfigurableAudioEncodingPipelineOutput) {
                pipelineOutput.audioEncoder
            } else {
                null
            }

            // Choose factory based on selected mode
            val factory: SrtBitrateRegulator.Factory = when (mode) {
                RegulatorMode.BELABOX -> object : SrtBitrateRegulator.Factory {
                    override fun newBitrateRegulator(
                        bitrateRegulatorConfig: BitrateRegulatorConfig,
                        onVideoTargetBitrateChange: (Int) -> Unit,
                        onAudioTargetBitrateChange: (Int) -> Unit
                    ): SrtBitrateRegulator {
                        return BelaboxSrtBelaRegulator(bitrateRegulatorConfig, onVideoTargetBitrateChange)
                    }
                }
                RegulatorMode.MOBLIN_FAST, RegulatorMode.MOBLIN_SLOW -> object : SrtBitrateRegulator.Factory {
                    override fun newBitrateRegulator(
                        bitrateRegulatorConfig: BitrateRegulatorConfig,
                        onVideoTargetBitrateChange: (Int) -> Unit,
                        onAudioTargetBitrateChange: (Int) -> Unit
                    ): SrtBitrateRegulator {
                        val regulator = MoblinSrtFightBitrateRegulator(
                            bitrateRegulatorConfig = bitrateRegulatorConfig,
                            moblinConfig = moblinConfig,
                            onVideoTargetBitrateChange = onVideoTargetBitrateChange
                        )
                        regulator.setSettings(mode == RegulatorMode.MOBLIN_FAST)
                        return regulator
                    }
                }
            }

            return DummyBitrateRegulatorController(
                audioEncoder,
                videoEncoder,
                pipelineOutput.endpoint,
                factory,
                bitrateRegulatorConfig,
                delayTimeInMs
            )
        }
    }
}
