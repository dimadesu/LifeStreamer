package com.dimadesu.lifestreamer.bitrate

import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.regulator.controllers.BitrateRegulatorController
import io.github.thibaultbee.streampack.core.regulator.controllers.DummyBitrateRegulatorController
import io.github.thibaultbee.streampack.ext.srt.regulator.SrtBitrateRegulator

/**
 * A BitrateRegulatorController implementation for Moblin SrtFight algorithm.
 */
class MoblinSrtFightBitrateRegulatorController {
    class Factory(
        private val bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
        private val moblinConfig: MoblinSrtFightConfig = MoblinSrtFightConfig(),
        private val delayTimeInMs: Long = 200 // Moblin updates every 200ms
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

            // Create the Moblin factory
            val moblinFactory = object : SrtBitrateRegulator.Factory {
                override fun newBitrateRegulator(
                    bitrateRegulatorConfig: BitrateRegulatorConfig,
                    onVideoTargetBitrateChange: (Int) -> Unit,
                    onAudioTargetBitrateChange: (Int) -> Unit
                ): SrtBitrateRegulator {
                    // Only use video bitrate changes, ignore audio changes as requested
                    return MoblinSrtFightBitrateRegulator(
                        bitrateRegulatorConfig = bitrateRegulatorConfig,
                        moblinConfig = moblinConfig,
                        onVideoTargetBitrateChange = onVideoTargetBitrateChange
                    )
                }
            }

            return DummyBitrateRegulatorController(
                audioEncoder,
                videoEncoder,
                pipelineOutput.endpoint,
                moblinFactory,
                bitrateRegulatorConfig,
                delayTimeInMs
            )
        }
    }
}
