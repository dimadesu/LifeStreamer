/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.rtmp.audio

import android.Manifest
import android.content.Context
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.annotation.RequiresPermission
// use AudioRecordEffect methods via the class
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.IAudioRecordSource
import io.github.thibaultbee.streampack.core.elements.utils.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * App-local copy of AudioRecordSource from StreamPack with public visibility so we can adapt buildAudioRecord.
 */
open class AudioRecordSource : IAudioSourceInternal, IAudioRecordSource {
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int? = null

    private var processor: EffectProcessor? = null
    private var pendingAudioEffects = mutableListOf<UUID>()

    private val isRunning: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    protected val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private val audioTimestamp = AudioTimestamp()

    protected open fun buildAudioRecord(
        config: AudioSourceConfig,
        bufferSize: Int
    ): AudioRecord {
        throw NotImplementedError("Subclass must implement buildAudioRecord")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configure(config: AudioSourceConfig) {
        /**
         * [configure] might be called multiple times.
         * If audio source is already running, we need to prevent reconfiguration.
         */
        audioRecord?.let {
            if (it.state == AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Audio source is already running")
            } else {
                release()
            }
        }

        bufferSize = getMinBufferSize(config)

        audioRecord = buildAudioRecord(config, bufferSize!!).also {
            val previousEffects = processor?.getAll() ?: emptyList()
            processor?.clear()

            // Add effects
            processor = EffectProcessor(it.audioSessionId).apply {
                (previousEffects + pendingAudioEffects).forEach { effectType ->
                    try {
                        add(effectType)
                    } catch (t: Throwable) {
                        Logger.e(TAG, "Failed to add effect: $effectType: ${t.message}")
                    }
                }
                pendingAudioEffects.clear()
            }

            if (it.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalArgumentException("Failed to initialized audio source with config: $config")
            }
        }
    }

    override suspend fun startStream() {
        if (isRunning) {
            Logger.d(TAG, "Already running")
            return
        }
        val audioRecord = requireNotNull(audioRecord)

        processor?.setEnabled(true)

        audioRecord.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        if (!isRunning) {
            Logger.d(TAG, "Not running")
            return
        }

        // Stop audio record
        audioRecord?.stop()

        processor?.setEnabled(false)
        _isStreamingFlow.tryEmit(false)
    }

    override fun release() {
        _isStreamingFlow.tryEmit(false)
        processor?.clear()
        processor = null

        // Release audio record
        audioRecord?.release()
        audioRecord = null
    }

    private fun getTimestampInUs(audioRecord: AudioRecord): Long {
        // Get timestamp from AudioRecord
        // If we can not get timestamp through getTimestamp, we timestamp audio sample.
        var timestamp: Long = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (audioRecord.getTimestamp(
                    audioTimestamp,
                    AudioTimestamp.TIMEBASE_MONOTONIC
                ) == AudioRecord.SUCCESS
            ) {
                timestamp = audioTimestamp.nanoTime / 1000 // to us
            }
        }

        // Fallback
        if (timestamp < 0) {
            timestamp = TimeUtils.currentTime()
        }

        return timestamp
    }


    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val audioRecord = requireNotNull(audioRecord) { "Audio source is not initialized" }
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Audio source is not recording")
        }

        val buffer = frame.rawBuffer
        val length = audioRecord.read(buffer, buffer.remaining())
        if (length > 0) {
            frame.timestampInUs = getTimestampInUs(audioRecord)
            return frame
        } else {
            frame.close()
            throw IllegalArgumentException(audioRecordErrorToString(length))
        }
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val bufferSize = requireNotNull(bufferSize) { "Buffer size is not initialized" }

        /**
         * Dummy timestamp: it is overwritten later.
         */
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }

    /**
     * Adds and enables an effect to the audio source.
     *
     * Get supported effects with [availableEffect].
     */
    override fun addEffect(effectType: UUID): Boolean {
        require(AudioRecordEffect.isValidUUID(effectType)) { "Unsupported effect type: $effectType" }
        require(AudioRecordEffect.isEffectAvailable(effectType)) { "Effect $effectType is not available" }

        val processor = processor
        return if (processor == null) {
            pendingAudioEffects.add(effectType)
            false
        } else {
            try {
                processor.add(effectType)
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to add effect: $effectType: ${t.message}")
                false
            }
        }
    }

    /**
     * Removes an effect from the audio source.
     */
    override fun removeEffect(effectType: UUID) {
        val processor = processor
        if (processor == null) {
            pendingAudioEffects.remove(effectType)
            return
        } else {
            processor.remove(effectType)
        }
    }

    companion object {
        private const val TAG = "AudioRecordSource"

        /**
         * Gets minimum buffer size for audio capture.
         */
        private fun getMinBufferSize(config: AudioSourceConfig): Int {
            val bufferSize = AudioRecord.getMinBufferSize(
                config.sampleRate,
                config.channelConfig,
                config.byteFormat
            )
            if (bufferSize <= 0) {
                throw IllegalArgumentException(audioRecordErrorToString(bufferSize))
            }
            return bufferSize
        }

        /**
         * Converts audio record error to string.
         */
        private fun audioRecordErrorToString(audioRecordError: Int) = when (audioRecordError) {
            AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord returns an invalid operation error"
            AudioRecord.ERROR_BAD_VALUE -> "AudioRecord returns a bad value error"
            AudioRecord.ERROR_DEAD_OBJECT -> "AudioRecord returns a dead object error"
            else -> "Unknown audio record error: $audioRecordError"
        }
    }

    private class EffectProcessor(private val audioSessionId: Int) {
        private val audioEffects: MutableSet<AudioEffect> = mutableSetOf()

        init {
            require(audioSessionId >= 0) { "Invalid audio session ID: $audioSessionId" }
        }

        fun getAll(): List<UUID> {
            return audioEffects.map { it.descriptor.type }
        }

        fun add(effectType: UUID): Boolean {
            require(AudioRecordEffect.isValidUUID(effectType)) { "Unsupported effect type: $effectType" }

            val previousEffect = audioEffects.firstOrNull { it.descriptor.type == effectType }
            if (previousEffect != null) {
                Logger.w(TAG, "Effect ${previousEffect.descriptor.name} already enabled")
                return false
            }

            val factory = AudioRecordEffect.Factory.getFactoryForEffectType(effectType)
            factory.build(audioSessionId).let {
                audioEffects.add(it)
                return true
            }
        }

        fun setEnabled(enabled: Boolean) {
            audioEffects.forEach { it.enabled = enabled }
        }

        fun remove(effectType: UUID) {
            require(AudioRecordEffect.isValidUUID(effectType)) { "Unknown effect type: $effectType" }

            val effect = audioEffects.firstOrNull { it.descriptor.type == effectType }
            if (effect != null) {
                effect.release()
                audioEffects.remove(effect)
            }
        }

        fun release() {
            audioEffects.forEach { it.release() }
        }

        fun clear() {
            release()
            audioEffects.clear()
        }

        companion object {
            private const val TAG = "EffectProcessor"
        }
    }
}


abstract class AudioRecordSourceFactory(
    private val effects: Set<UUID>
) : IAudioSourceInternal.Factory {
    /**
     * Create an [AudioRecordSource] implementation.
     */
    internal abstract suspend fun createImpl(context: Context): AudioRecordSource

    override suspend fun create(context: Context): IAudioSourceInternal {
        return createImpl(context).apply {
            effects.forEach { effect ->
                if (AudioRecordEffect.isEffectAvailable(effect)) {
                    addEffect(effect)
                }
            }
        }
    }

    companion object {
        val defaultAudioEffects = setOf(
            AudioEffect.EFFECT_TYPE_AEC,
            AudioEffect.EFFECT_TYPE_NS
        )
    }
}