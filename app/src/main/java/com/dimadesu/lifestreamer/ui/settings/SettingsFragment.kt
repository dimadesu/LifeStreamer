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
package com.dimadesu.lifestreamer.ui.settings

import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.data.storage.PreferencesDataStoreAdapter
import com.dimadesu.lifestreamer.models.EndpointFactory
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.models.FileExtension
import com.dimadesu.lifestreamer.utils.DialogUtils
import com.dimadesu.lifestreamer.utils.ProfileLevelDisplay
import com.dimadesu.lifestreamer.utils.StreamerInfoFactory
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.elements.encoders.mediacodec.MediaCodecHelper
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrameRateSupported
import io.github.thibaultbee.streampack.core.streamers.infos.CameraStreamerConfigurationInfo
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import java.io.IOException

class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var streamerInfo: CameraStreamerConfigurationInfo
    private val profileLevelDisplay by lazy { ProfileLevelDisplay(requireContext()) }

    /**
     * Safe preference finder that logs error if preference not found instead of crashing
     */
    private fun <T : androidx.preference.Preference> findPreferenceSafe(keyResId: Int): T? {
        return try {
            this.findPreference(getString(keyResId))
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Preference not found for key: ${getString(keyResId)}", e)
            null
        }
    }

    private val videoEncoderListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.video_encoder_key) ?: error("video_encoder_key not found")
    }

    private val videoResolutionListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.video_resolution_key) ?: error("video_resolution_key not found")
    }

    private val videoFpsListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.video_fps_key) ?: error("video_fps_key not found")
    }

    private val videoBitrateSeekBar: SeekBarPreference by lazy {
        findPreferenceSafe<SeekBarPreference>(R.string.video_bitrate_key) ?: error("video_bitrate_key not found")
    }

    private val videoProfileListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.video_profile_key) ?: error("video_profile_key not found")
    }

    private val videoLevelListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.video_level_key) ?: error("video_level_key not found")
    }

    private val audioEnablePreference: SwitchPreference by lazy {
        findPreferenceSafe<SwitchPreference>(R.string.audio_enable_key) ?: error("audio_enable_key not found")
    }

    private val audioSettingsCategory: PreferenceCategory by lazy {
        findPreferenceSafe<PreferenceCategory>(R.string.audio_settings_key) ?: error("audio_settings_key not found")
    }

    private val audioEncoderListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.audio_encoder_key) ?: error("audio_encoder_key not found")
    }

    private val audioChannelConfigListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.audio_channel_config_key) ?: error("audio_channel_config_key not found")
    }

    private val audioBitrateListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.audio_bitrate_key) ?: error("audio_bitrate_key not found")
    }

    private val audioSampleRateListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.audio_sample_rate_key) ?: error("audio_sample_rate_key not found")
    }

    private val audioByteFormatListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.audio_byte_format_key) ?: error("audio_byte_format_key not found")
    }

    private val audioProfileListPreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.audio_profile_key) ?: error("audio_profile_key not found")
    }

    private val endpointTypePreference: ListPreference by lazy {
        findPreferenceSafe<ListPreference>(R.string.endpoint_type_key) ?: error("endpoint_type_key not found")
    }

    private val rtmpEndpointPreference: PreferenceCategory by lazy {
        findPreferenceSafe<PreferenceCategory>(R.string.rtmp_server_key) ?: error("rtmp_server_key not found")
    }

    private val srtEndpointPreference: PreferenceCategory by lazy {
        findPreferenceSafe<PreferenceCategory>(R.string.srt_server_key) ?: error("srt_server_key not found")
    }

    private val fileEndpointPreference: PreferenceCategory by lazy {
        findPreferenceSafe<PreferenceCategory>(R.string.file_endpoint_key) ?: error("file_endpoint_key not found")
    }

    private val serverIpPreference: EditTextPreference by lazy {
        findPreferenceSafe<EditTextPreference>(R.string.srt_server_ip_key) ?: error("srt_server_ip_key not found")
    }

    private val serverPortPreference: EditTextPreference by lazy {
        findPreferenceSafe<EditTextPreference>(R.string.srt_server_port_key) ?: error("srt_server_port_key not found")
    }

    private val serverLatencyPreference: EditTextPreference by lazy {
        findPreferenceSafe<EditTextPreference>(R.string.srt_server_latency_key) ?: error("srt_server_latency_key not found")
    }

    private val serverEnableBitrateRegulationPreference: SwitchPreference by lazy {
        findPreferenceSafe<SwitchPreference>(R.string.srt_server_enable_bitrate_regulation_key) ?: error("srt_server_enable_bitrate_regulation_key not found")
    }

    private val serverTargetVideoBitratePreference: SeekBarPreference by lazy {
        findPreferenceSafe<SeekBarPreference>(R.string.srt_server_video_target_bitrate_key) ?: error("srt_server_video_target_bitrate_key not found")
    }

    private val serverMinVideoBitratePreference: SeekBarPreference by lazy {
        findPreferenceSafe<SeekBarPreference>(R.string.srt_server_video_min_bitrate_key) ?: error("srt_server_video_min_bitrate_key not found")
    }

    private val fileNamePreference: EditTextPreference by lazy {
        findPreferenceSafe<EditTextPreference>(R.string.file_name_key) ?: error("file_name_key not found")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore =
            PreferencesDataStoreAdapter(requireContext().dataStore, lifecycleScope)

        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        
        // Load preferences after XML is inflated to avoid timing issues
        // where lazy properties are accessed before preferences are ready
        loadPreferences()
    }
    
    private fun roundBitrate(value: Int, step: Int = 500): Int {
        return (value / step) * step
    }

    private fun loadVideoSettings() {
        // Inflates video encoders
        val supportedVideoEncoderName = mapOf(
            MediaFormat.MIMETYPE_VIDEO_AVC to getString(R.string.video_encoder_h264),
            MediaFormat.MIMETYPE_VIDEO_HEVC to getString(R.string.video_encoder_h265),
            MediaFormat.MIMETYPE_VIDEO_H263 to getString(R.string.video_encoder_h263),
            MediaFormat.MIMETYPE_VIDEO_VP9 to getString(R.string.video_encoder_vp9),
            MediaFormat.MIMETYPE_VIDEO_VP8 to getString(R.string.video_encoder_vp8),
            MediaFormat.MIMETYPE_VIDEO_AV1 to getString(R.string.video_encoder_av1)
        )

        val supportedVideoEncoder = streamerInfo.video.supportedEncoders
        val defaultVideoEncoder = when {
            supportedVideoEncoder.isEmpty() -> null
            supportedVideoEncoder.contains(MediaFormat.MIMETYPE_VIDEO_AVC) -> {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }

            else -> supportedVideoEncoder.first()
        }

        videoEncoderListPreference.entryValues = supportedVideoEncoder.toTypedArray()
        videoEncoderListPreference.entries =
            supportedVideoEncoder.map { supportedVideoEncoderName[it] }.toTypedArray()
        if (videoEncoderListPreference.entry == null) {
            videoEncoderListPreference.value = defaultVideoEncoder
        }
        videoEncoderListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadVideoSettings(newValue as String)
            true
        }

        videoEncoderListPreference.value?.let { loadVideoSettings(it) }
    }

    private fun loadVideoSettings(encoder: String) {
        // Inflates video resolutions
        streamerInfo.video.getSupportedResolutions(
            requireContext(), encoder
        ).map { it.toString() }.toTypedArray().run {
            videoResolutionListPreference.entries = this
            videoResolutionListPreference.entryValues = this
        }

        // Inflates video fps
        val supportedFramerates = streamerInfo.video.getSupportedFramerates(
            requireContext(), encoder, requireContext().defaultCameraId
        )
        videoFpsListPreference.entryValues.filter { fps ->
            supportedFramerates.any { it.contains(fps.toString().toIntOrNull() ?: 0) }
        }.toTypedArray().run {
            videoFpsListPreference.entries = this
            videoFpsListPreference.entryValues = this
        }
        videoFpsListPreference.setOnPreferenceChangeListener { _, newValue ->
            val fps = (newValue as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener true
            val unsupportedCameras = requireContext().cameras.filter {
                !requireContext().isFrameRateSupported(it, fps)
            }
            if (unsupportedCameras.isNotEmpty()) {
                DialogUtils.showAlertDialog(
                    requireContext(), getString(R.string.warning), resources.getQuantityString(
                        R.plurals.camera_frame_rate_not_supported,
                        unsupportedCameras.size,
                        unsupportedCameras.joinToString(", "),
                        fps
                    )
                )
            }
            true
        }

        // Inflates video bitrate
        streamerInfo.video.getSupportedBitrates(encoder).run {
            videoBitrateSeekBar.min = maxOf(videoBitrateSeekBar.min, lower / 1000) // to kb/s
            videoBitrateSeekBar.max = minOf(videoBitrateSeekBar.max, upper / 1000) // to kb/s
        }
        
        videoBitrateSeekBar.setOnPreferenceChangeListener { _, newValue ->
            val rounded = roundBitrate(newValue as Int)
            if (rounded != newValue) {
                videoBitrateSeekBar.value = rounded
                false
            } else {
                true
            }
        }

        // Inflates profile
        val profiles = streamerInfo.video.getSupportedAllProfiles(
            requireContext(), encoder, requireContext().defaultCameraId
        )

        val profilesName = profiles.map {
            profileLevelDisplay.getProfileName(
                encoder, it
            )
        }.toTypedArray()

        videoProfileListPreference.entries = profilesName
        videoProfileListPreference.entryValues = profiles.map { it.toString() }.toTypedArray()
        if (videoProfileListPreference.entry == null) {
            videoProfileListPreference.value = VideoConfig.getBestProfile(encoder).toString()
        }
        videoProfileListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadVideoLevel(encoder, (newValue as String).toInt())
            true
        }

        videoProfileListPreference.value?.toIntOrNull()?.let { loadVideoLevel(encoder, it) }
    }

    private fun loadVideoLevel(encoder: String, profile: Int) {
        // Inflates level
        val levels = profileLevelDisplay.getAllLevelSet(encoder)
            .filter { it <= MediaCodecHelper.getMaxLevel(encoder, profile) }
        val levelsName = levels.map {
            profileLevelDisplay.getLevelName(
                encoder, it
            )
        }.toTypedArray()

        videoLevelListPreference.entries = levelsName
        videoLevelListPreference.entryValues = levels.map { it.toString() }.toTypedArray()
        videoLevelListPreference.value = VideoConfig.getBestLevel(encoder, profile).toString()
    }

    private fun loadAudioSettings() {
        // Inflates audio encoders
        val supportedAudioEncoderName = mapOf(
            MediaFormat.MIMETYPE_AUDIO_AAC to getString(R.string.audio_encoder_aac),
            MediaFormat.MIMETYPE_AUDIO_OPUS to getString(R.string.audio_encoder_opus)
        )

        val supportedAudioEncoder = streamerInfo.audio.supportedEncoders
        val defaultAudioEncoder =
            if (supportedAudioEncoder.contains(MediaFormat.MIMETYPE_AUDIO_AAC)) {
                MediaFormat.MIMETYPE_AUDIO_AAC
            } else {
                supportedAudioEncoder.first()
            }
        audioEncoderListPreference.entryValues = supportedAudioEncoder.toTypedArray()
        audioEncoderListPreference.entries =
            supportedAudioEncoder.map { supportedAudioEncoderName[it] }.toTypedArray()
        if (audioEncoderListPreference.entry == null) {
            audioEncoderListPreference.value = defaultAudioEncoder
        }
        audioEncoderListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadAudioSettings(newValue as String)
            true
        }

        loadAudioSettings(audioEncoderListPreference.value)
    }

    private fun loadAudioSettings(encoder: String) {
        audioSettingsCategory.isVisible = audioEnablePreference.isChecked
        audioEnablePreference.setOnPreferenceChangeListener { _, newValue ->
            audioSettingsCategory.isVisible = newValue as Boolean
            true
        }

        // Inflates audio number of channel
        val inputChannelRange = streamerInfo.audio.getSupportedInputChannelRange(encoder)
        audioChannelConfigListPreference.entryValues.filter {
            inputChannelRange.contains(AudioConfig.getNumberOfChannels(it.toString().toInt()))
        }.toTypedArray().run {
            audioChannelConfigListPreference.entryValues = this
        }

        // Inflates audio bitrate
        val bitrateRange = streamerInfo.audio.getSupportedBitrates(encoder)
        audioBitrateListPreference.entryValues.filter {
            bitrateRange.contains(
                it.toString().toInt()
            )
        }.toTypedArray().run {
            audioBitrateListPreference.entries =
                this.map { "${it.toString().toInt() / 1000} Kbps" }.toTypedArray()
            audioBitrateListPreference.entryValues = this
        }
        if (audioBitrateListPreference.entry == null) {
            audioBitrateListPreference.value = "128000"
        }

        // Inflates audio sample rate
        val sampleRates = streamerInfo.audio.getSupportedSampleRates(encoder)
        audioSampleRateListPreference.entries =
            sampleRates.map { "${"%.1f".format(it.toString().toFloat() / 1000)} kHz" }
                .toTypedArray()
        audioSampleRateListPreference.entryValues = sampleRates.map { "$it" }.toTypedArray()
        if (audioSampleRateListPreference.entry == null) {
            audioSampleRateListPreference.value = when {
                sampleRates.contains(44100) -> "44100"
                sampleRates.contains(48000) -> "48000"
                else -> "${sampleRates.first()}"
            }
        }

        // Inflates audio byte format
        val supportedByteFormatName = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to getString(R.string.audio_byte_format_8bit),
            AudioFormat.ENCODING_PCM_16BIT to getString(R.string.audio_byte_format_16bit),
            AudioFormat.ENCODING_PCM_FLOAT to getString(R.string.audio_byte_format_float)
        )
        val byteFormats = streamerInfo.audio.getSupportedByteFormats()
        audioByteFormatListPreference.entries =
            byteFormats.map { supportedByteFormatName[it] }.toTypedArray()
        audioByteFormatListPreference.entryValues = byteFormats.map { "$it" }.toTypedArray()
        if (audioByteFormatListPreference.entry == null) {
            audioByteFormatListPreference.value = "${AudioFormat.ENCODING_PCM_16BIT}"
        }

        // Inflates profile
        audioProfileListPreference.isVisible = encoder == MediaFormat.MIMETYPE_AUDIO_AAC
        val profiles = streamerInfo.audio.getSupportedProfiles(encoder)
        audioProfileListPreference.entries = profiles.map {
            profileLevelDisplay.getProfileName(
                encoder, it
            )
        }.toTypedArray()
        audioProfileListPreference.entryValues = profiles.map { "$it" }.toTypedArray()
        if (audioProfileListPreference.entry == null) {
            val value = if (profiles.contains(MediaCodecInfo.CodecProfileLevel.AACObjectLC)) {
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            } else if (profiles.isNotEmpty()) {
                profiles.first()
            } else {
                0
            }
            audioProfileListPreference.value = value.toString()
        }
    }

    private fun loadEndpoint() {
        // Inflates endpoint
        val supportedEndpoint = EndpointType.entries.map { "${it.id}" }.toTypedArray()
        endpointTypePreference.entryValues = supportedEndpoint
        endpointTypePreference.entries =
            EndpointType.entries.map { getString(it.labelResId) }
                .toTypedArray()
        // Only set default if user has never set a value (check .value not .entry)
        // .entry can be null during initialization even with saved value
        if (endpointTypePreference.value.isNullOrEmpty()) {
            endpointTypePreference.value = "${EndpointType.SRT.id}"
        }
        endpointTypePreference.value?.toIntOrNull()?.let { setEndpointType(it) }
        endpointTypePreference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? String)?.toIntOrNull()?.let { setEndpointType(it) }
            true
        }

        serverIpPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        serverPortPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.filters = arrayOf(InputFilter.LengthFilter(5))
        }

        serverLatencyPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.filters = arrayOf(InputFilter.LengthFilter(5))
        }

        serverTargetVideoBitratePreference.isVisible =
            serverEnableBitrateRegulationPreference.isChecked
        serverMinVideoBitratePreference.isVisible =
            serverEnableBitrateRegulationPreference.isChecked
        serverEnableBitrateRegulationPreference.setOnPreferenceChangeListener { _, newValue ->
            serverTargetVideoBitratePreference.isVisible = newValue as Boolean
            serverMinVideoBitratePreference.isVisible = newValue
            true
        }

        serverTargetVideoBitratePreference.setOnPreferenceChangeListener { _, newValue ->
            val rounded = roundBitrate(newValue as Int)
            if (rounded < serverMinVideoBitratePreference.value) {
                serverMinVideoBitratePreference.value = rounded
            }
            if (rounded != newValue) {
                serverTargetVideoBitratePreference.value = rounded
                false
            } else {
                true
            }
        }

        serverMinVideoBitratePreference.setOnPreferenceChangeListener { _, newValue ->
            val rounded = roundBitrate(newValue as Int)
            if (rounded > serverTargetVideoBitratePreference.value) {
                serverTargetVideoBitratePreference.value = rounded
            }
            if (rounded != newValue) {
                serverMinVideoBitratePreference.value = rounded
                false
            } else {
                true
            }
        }
    }

    private fun setEndpointType(id: Int) {
        val endpointType = EndpointType.fromId(id)
        val endpoint = EndpointFactory(
            endpointType
        ).build()
        srtEndpointPreference.isVisible = endpoint.hasSrtCapabilities
        rtmpEndpointPreference.isVisible = endpoint.hasRtmpCapabilities
        fileEndpointPreference.isVisible = endpoint.hasFileCapabilities

        // Update supported values with a new info
        streamerInfo = StreamerInfoFactory(requireContext(), endpointType).build()
        loadVideoSettings()
        loadAudioSettings()

        // Update file extension
        if (endpoint.hasFileCapabilities) {
            // Remove previous extension (only once)
            fileNamePreference.text = fileNamePreference.text?.substringBeforeLast(".") ?: "output"
            // Add correct extension
            fileNamePreference.text += when {
                endpoint.hasFLVCapabilities -> {
                    FileExtension.FLV.extension
                }

                endpoint.hasTSCapabilities -> {
                    FileExtension.TS.extension
                }

                endpoint.hasMP4Capabilities -> {
                    FileExtension.MP4.extension
                }

                endpointType == EndpointType.WEBM_FILE -> {
                    FileExtension.WEBM.extension
                }

                endpointType == EndpointType.OGG_FILE -> {
                    FileExtension.OGG.extension
                }

                endpointType == EndpointType.THREEGP_FILE -> {
                    FileExtension.THREEGP.extension
                }

                else -> {
                    throw IOException("Unknown file type")
                }
            }
        }
    }

    private fun loadPreferences() {
        loadEndpoint()
    }
}