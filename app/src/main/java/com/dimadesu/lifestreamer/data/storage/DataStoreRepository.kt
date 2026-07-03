package com.dimadesu.lifestreamer.data.storage

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dimadesu.lifestreamer.ApplicationConstants
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.models.FileExtension
import com.dimadesu.lifestreamer.utils.appendIfNotEndsWith
import com.dimadesu.lifestreamer.utils.createVideoContentUri
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A repository for storage data.
 *
 * Most of the stored value are [stringPreferencesKey] because of the usage of preference screen.
 */
class DataStoreRepository(
    private val context: Context, private val dataStore: DataStore<Preferences>
) {
    val isAudioEnableFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[booleanPreferencesKey(context.getString(R.string.audio_enable_key))] ?: true
    }.distinctUntilChanged()

    // Audio source type (MediaRecorder.AudioSource constant)
    val audioSourceTypeFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[stringPreferencesKey(context.getString(R.string.audio_source_type_key))]?.toIntOrNull()
            ?: android.media.MediaRecorder.AudioSource.CAMCORDER
    }.distinctUntilChanged()

    val audioConfigFlow: Flow<AudioConfig?> = dataStore.data.map { preferences ->
        val isAudioEnable =
            preferences[booleanPreferencesKey(context.getString(R.string.audio_enable_key))] ?: true
        if (!isAudioEnable) {
            return@map null
        }

        val mimeType =
            preferences[stringPreferencesKey(context.getString(R.string.audio_encoder_key))]
                ?: ApplicationConstants.Audio.defaultEncoder
        val startBitrate =
            preferences[stringPreferencesKey(context.getString(R.string.audio_bitrate_key))]?.toInt()
                ?: ApplicationConstants.Audio.defaultBitrateInBps

        val channelConfig =
            preferences[stringPreferencesKey(context.getString(R.string.audio_channel_config_key))]?.toInt()
                ?: ApplicationConstants.Audio.defaultChannelConfig
        val sampleRate =
            preferences[stringPreferencesKey(context.getString(R.string.audio_sample_rate_key))]?.toInt()
                ?: ApplicationConstants.Audio.defaultSampleRate
        val byteFormat =
            preferences[stringPreferencesKey(context.getString(R.string.audio_byte_format_key))]?.toInt()
                ?: ApplicationConstants.Audio.defaultByteFormat
        val profile =
            preferences[stringPreferencesKey(context.getString(R.string.audio_profile_key))]?.toInt()
                ?: if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                } else {
                    0
                }
        AudioConfig(
            mimeType = mimeType,
            channelConfig = channelConfig,
            startBitrate = startBitrate,
            sampleRate = sampleRate,
            byteFormat = byteFormat,
            profile = profile
        )
    }.distinctUntilChanged()

    val isVideoEnableFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[booleanPreferencesKey(context.getString(R.string.video_enable_key))] ?: true
    }.distinctUntilChanged()

    val videoConfigFlow: Flow<VideoConfig?> = dataStore.data.map { preferences ->
        val isVideoEnable =
            preferences[booleanPreferencesKey(context.getString(R.string.video_enable_key))] ?: true
        if (!isVideoEnable) {
            return@map null
        }

        val mimeType =
            preferences[stringPreferencesKey(context.getString(R.string.video_encoder_key))]
                ?: ApplicationConstants.Video.defaultEncoder
        val startBitrate =
            preferences[intPreferencesKey(context.getString(R.string.video_bitrate_key))]?.times(
                1000
            ) ?: ApplicationConstants.Video.defaultBitrateInBps

        val resolution =
            preferences[stringPreferencesKey(context.getString(R.string.video_resolution_key))]?.split(
                "x"
            )?.let { Size(it[0].toInt(), it[1].toInt()) }
                ?: ApplicationConstants.Video.defaultResolution
        val cameraFps =
            preferences[stringPreferencesKey(context.getString(R.string.camera_fps_key))]?.toInt()
                ?: ApplicationConstants.Video.defaultFps
        val matchFps =
            preferences[booleanPreferencesKey(context.getString(R.string.match_fps_key))] ?: true
        val fps = if (matchFps) {
            cameraFps
        } else {
            preferences[stringPreferencesKey(context.getString(R.string.video_fps_key))]?.toInt()
                ?: ApplicationConstants.Video.defaultFps
        }
        val profile =
            preferences[stringPreferencesKey(context.getString(R.string.video_profile_key))]?.toInt()
                ?: VideoConfig.getBestProfile(mimeType)
        val level =
            preferences[stringPreferencesKey(context.getString(R.string.video_level_key))]?.toInt()
                ?: VideoConfig.getBestLevel(mimeType, profile)
        VideoConfig(
            mimeType = mimeType,
            startBitrate = startBitrate,
            resolution = resolution,
            fps = fps,
            cameraFps = cameraFps,
            profile = profile,
            level = level
        )
    }.distinctUntilChanged()

    val endpointDescriptorFlow: Flow<MediaDescriptor> = dataStore.data.map { preferences ->
        val endpointTypeId =
            preferences[stringPreferencesKey(context.getString(R.string.endpoint_type_key))]?.toInt()
                ?: EndpointType.SRT.id
        when (val endpointType = EndpointType.fromId(endpointTypeId)) {
            EndpointType.TS_FILE,
            EndpointType.FLV_FILE,
            EndpointType.MP4_FILE,
            EndpointType.WEBM_FILE,
            EndpointType.OGG_FILE,
            EndpointType.THREEGP_FILE -> {
                val filename =
                    preferences[stringPreferencesKey(context.getString(R.string.file_endpoint_key))]
                        ?: "StreamPack"
                context.createVideoContentUri(
                    filename.appendIfNotEndsWith(FileExtension.fromEndpointType(endpointType).extension)
                )
            }

            EndpointType.SRT -> {
                val ip =
                    preferences[stringPreferencesKey(context.getString(R.string.srt_server_ip_key))]
                        ?: context.getString(R.string.default_srt_server_url)
                val port =
                    preferences[stringPreferencesKey(context.getString(R.string.srt_server_port_key))]?.toInt()
                        ?: 9998
                val streamId =
                    preferences[stringPreferencesKey(context.getString(R.string.srt_server_stream_id_key))]
                        ?: ""
                val passPhrase =
                    preferences[stringPreferencesKey(context.getString(R.string.srt_server_passphrase_key))]
                        ?: context.getString(R.string.default_srt_server_passphrase)
                val latency =
                    preferences[stringPreferencesKey(context.getString(R.string.srt_server_latency_key))]?.toIntOrNull()
                        ?: context.getString(R.string.default_srt_server_latency).toInt()
                SrtMediaDescriptor(
                    host = ip,
                    port = port,
                    streamId = streamId,
                    passPhrase = passPhrase,
                    latency = latency
                )
            }

            EndpointType.RTMP -> {
                val url =
                    preferences[stringPreferencesKey(context.getString(R.string.rtmp_server_url_key))]
                    ?: context.getString(R.string.default_rtmp_url)
                UriMediaDescriptor(context, url)
            }

            EndpointType.SRTLA -> {
                val listenPort =
                    preferences[stringPreferencesKey(context.getString(R.string.srtla_listen_port_key))]?.toIntOrNull()
                        ?: context.getString(R.string.default_srtla_listen_port).toInt()
                val latency =
                    preferences[stringPreferencesKey(context.getString(R.string.srtla_latency_key))]?.toIntOrNull()
                        ?: context.getString(R.string.default_srtla_latency).toInt()
                val streamId =
                    preferences[stringPreferencesKey(context.getString(R.string.srtla_stream_id_key))]
                        ?: ""
                val passPhrase =
                    preferences[stringPreferencesKey(context.getString(R.string.srtla_passphrase_key))]
                        ?: ""
                // SRT connects to the local Bond Bunny proxy which forwards to the SRTLA receiver
                SrtMediaDescriptor(
                    host = "127.0.0.1",
                    port = listenPort,
                    streamId = streamId,
                    passPhrase = passPhrase,
                    latency = latency
                )
            }
        }
    }.distinctUntilChanged()

    /** Config needed to start Bond Bunny SRTLA service. Null when endpoint type is not SRTLA. */
    data class SrtlaConfig(val receiverHost: String, val receiverPort: String, val listenPort: String)

    val srtlaConfigFlow: Flow<SrtlaConfig?> = dataStore.data.map { preferences ->
        val endpointTypeId =
            preferences[stringPreferencesKey(context.getString(R.string.endpoint_type_key))]?.toInt()
                ?: EndpointType.SRT.id
        if (EndpointType.fromId(endpointTypeId) != EndpointType.SRTLA) return@map null
        SrtlaConfig(
            receiverHost = preferences[stringPreferencesKey(context.getString(R.string.srtla_receiver_host_key))]
                ?: context.getString(R.string.default_srtla_receiver_host),
            receiverPort = preferences[stringPreferencesKey(context.getString(R.string.srtla_receiver_port_key))]
                ?: context.getString(R.string.default_srtla_receiver_port),
            listenPort = preferences[stringPreferencesKey(context.getString(R.string.srtla_listen_port_key))]
                ?: context.getString(R.string.default_srtla_listen_port)
        )
    }.distinctUntilChanged()

    /** Config needed to start the Moblink relay server. Null when disabled or not in SRTLA mode. */
    data class MoblinkConfig(val name: String, val password: String, val port: Int)

    val moblinkConfigFlow: Flow<MoblinkConfig?> = dataStore.data.map { preferences ->
        // Moblink only makes sense when Bond Bunny SRTLA bonding is active
        val endpointTypeId =
            preferences[stringPreferencesKey(context.getString(R.string.endpoint_type_key))]?.toInt()
                ?: EndpointType.SRT.id
        if (EndpointType.fromId(endpointTypeId) != EndpointType.SRTLA) return@map null
        val enabled = preferences[booleanPreferencesKey(context.getString(R.string.moblink_enabled_key))] ?: false
        if (!enabled) return@map null
        MoblinkConfig(
            name = preferences[stringPreferencesKey(context.getString(R.string.moblink_name_key))]
                ?: context.getString(R.string.default_moblink_name),
            password = preferences[stringPreferencesKey(context.getString(R.string.moblink_password_key))]
                ?: context.getString(R.string.default_moblink_password),
            port = preferences[stringPreferencesKey(context.getString(R.string.moblink_port_key))]?.toIntOrNull()
                ?: context.getString(R.string.default_moblink_port).toInt(),
        )
    }.distinctUntilChanged()

    // Flow for RTMP video source URL (primary, index 1)
    val rtmpVideoSourceUrlFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[stringPreferencesKey(context.getString(R.string.rtmp_source_url_key))]
            ?: context.getString(R.string.rtmp_source_default_url)
    }.distinctUntilChanged()

    // Flow for RTMP source count (number of configured RTMP source URLs)
    val rtmpSourceCountFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[intPreferencesKey(context.getString(R.string.rtmp_source_count_key))]
            ?: 1
    }.distinctUntilChanged()

    /**
     * Get the DataStore key for an RTMP source URL at the given 1-based index.
     * Index 1 uses the original key for backward compatibility.
     */
    fun rtmpSourceUrlKey(index: Int): String {
        val baseKey = context.getString(R.string.rtmp_source_url_key)
        return if (index == 1) baseKey else "${baseKey}_$index"
    }

    /**
     * Get the default URL for an RTMP source at the given 1-based index.
     * Index 1 returns the base default URL; higher indices append the index number.
     */
    fun defaultRtmpSourceUrl(index: Int): String {
        val baseUrl = context.getString(R.string.rtmp_source_default_url)
        return if (index == 1) baseUrl else "$baseUrl$index"
    }

    /**
     * Flow for RTMP source URL at a specific 1-based index.
     */
    fun rtmpSourceUrlFlow(index: Int): Flow<String> {
        val key = rtmpSourceUrlKey(index)
        return dataStore.data.map { preferences ->
            preferences[stringPreferencesKey(key)]
                ?: defaultRtmpSourceUrl(index)
        }.distinctUntilChanged()
    }

    /**
     * Set the RTMP source count.
     */
    suspend fun setRtmpSourceCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[intPreferencesKey(context.getString(R.string.rtmp_source_count_key))] = count
        }
    }

    /**
     * Remove the RTMP source URL at the given 1-based index from DataStore.
     */
    suspend fun removeRtmpSourceUrl(index: Int) {
        val key = rtmpSourceUrlKey(index)
        dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
        }
    }

    // Flow for RTMP source restart on disconnect setting
    val rtmpSourceRestartOnDisconnectFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[booleanPreferencesKey(context.getString(R.string.rtmp_source_restart_on_disconnect_key))]
            ?: true // Default to true (recommended)
    }.distinctUntilChanged()

    // Flow for requiring audio track in RTMP source playback
    val rtmpSourceRequireAudioTrackFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[booleanPreferencesKey(context.getString(R.string.rtmp_source_require_audio_track_key))]
            ?: true // Default to true
    }.distinctUntilChanged()

    // Flow for RTMP source playback buffer duration in milliseconds
    val rtmpSourceBufferForPlaybackMsFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[intPreferencesKey(context.getString(R.string.rtmp_source_buffer_for_playback_ms_key))]
            ?: context.getString(R.string.default_rtmp_buffer_for_playback_ms).toInt()
    }.distinctUntilChanged()

    val bitrateRegulatorConfigFlow: Flow<BitrateRegulatorConfig?> =
        dataStore.data.map { preferences ->
            val isBitrateRegulatorEnable =
                preferences[booleanPreferencesKey(context.getString(R.string.srt_server_enable_bitrate_regulation_key))]
                    ?: true
            if (!isBitrateRegulatorEnable) {
                return@map null
            }

            val videoMinBitrate =
                preferences[intPreferencesKey(context.getString(R.string.srt_server_video_min_bitrate_key))]?.toInt()
                    ?.times(1000)
                    ?: 300000
            val videoMaxBitrate =
                preferences[intPreferencesKey(context.getString(R.string.srt_server_video_target_bitrate_key))]?.toInt()
                    ?.times(1000)
                    ?: 10000000
            BitrateRegulatorConfig(
                videoBitrateRange = Range(videoMinBitrate, videoMaxBitrate)
            )
        }

    /**
     * Regulator mode flow. Stored as string preference values: fast, slow, belabox.
     */
    val regulatorModeFlow: Flow<com.dimadesu.lifestreamer.bitrate.RegulatorMode> = dataStore.data.map { preferences ->
        val stored = preferences[stringPreferencesKey(context.getString(R.string.srt_server_moblin_regulator_mode_key))]
            ?: context.getString(R.string.srt_server_moblin_regulator_mode_value_belabox)
        when (stored) {
            context.getString(R.string.srt_server_moblin_regulator_mode_value_slow) -> com.dimadesu.lifestreamer.bitrate.RegulatorMode.MOBLIN_SLOW
            context.getString(R.string.srt_server_moblin_regulator_mode_value_belabox) -> com.dimadesu.lifestreamer.bitrate.RegulatorMode.BELABOX
            else -> com.dimadesu.lifestreamer.bitrate.RegulatorMode.BELABOX
        }
    }.distinctUntilChanged()

    val streamOrientationFlow: Flow<com.dimadesu.lifestreamer.models.StreamOrientation> = dataStore.data.map { preferences ->
        val stored = preferences[stringPreferencesKey(context.getString(R.string.stream_orientation_key))]
            ?: context.getString(R.string.stream_orientation_value_auto)
        com.dimadesu.lifestreamer.models.StreamOrientation.fromId(stored)
    }.distinctUntilChanged()

    // Save methods for audio settings
    suspend fun saveAudioSourceType(sourceType: Int) {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(context.getString(R.string.audio_source_type_key))] = sourceType.toString()
        }
    }
}
