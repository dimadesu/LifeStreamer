package com.dimadesu.lifestreamer.rtmp.audio.buffer

import android.content.Context
import android.media.AudioRecord
import com.dimadesu.lifestreamer.ui.main.BufferVisualizerModel
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CustomAudioInput3(
    private var audioRecordWrapper: AudioRecordWrapper3,
    private val bufferVisualizerModel: BufferVisualizerModel
) : IAudioSourceInternal {

    var bufferSize: Int? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    companion object {
        private const val TAG = "CustomAudioInput3"
    }

    override suspend fun configure(config: AudioSourceConfig) {
//        audioRecordWrapper.release()
        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
    }

    override suspend fun startStream() {
        audioRecordWrapper.startRecording()
        bufferVisualizerModel.setStreamingState(true)
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        bufferVisualizerModel.setStreamingState(false)
        audioRecordWrapper.stop()
        _isStreamingFlow.tryEmit(false)
    }

    override fun release() {
        audioRecordWrapper.release()
        _isStreamingFlow.tryEmit(false)
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val frame = frameFactory.create(bufferSize!!, 0)
        audioRecordWrapper.read(frame)
        return frame
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        audioRecordWrapper.read(frame)
        return frame
    }

    class Factory(
        private var audioRecordWrapper: AudioRecordWrapper3,
        private val bufferVisualizerModel: BufferVisualizerModel
    ) : IAudioSourceInternal.Factory {
        override suspend fun create(context: Context): IAudioSourceInternal {
            val customAudioInput = CustomAudioInput3(audioRecordWrapper, bufferVisualizerModel)
            return customAudioInput
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput3
        }
    }
}
