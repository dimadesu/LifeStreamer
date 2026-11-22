package com.dimadesu.lifestreamer.models

import androidx.annotation.StringRes
import com.dimadesu.lifestreamer.R

/**
 * Container formats supported for simultaneous local disk recording while streaming.
 */
enum class LocalRecordingFormat(
    val prefValue: String,
    val extension: FileExtension,
    @param:StringRes val labelResId: Int
) {
    TS("ts", FileExtension.TS, R.string.local_recording_format_ts),
    MP4("mp4", FileExtension.MP4, R.string.local_recording_format_mp4),
    FLV("flv", FileExtension.FLV, R.string.local_recording_format_flv);

    companion object {
        val DEFAULT = TS

        fun supportedFor(endpointType: EndpointType): List<LocalRecordingFormat> {
            return when (endpointType) {
                EndpointType.RTMP -> listOf(TS, MP4, FLV)
                EndpointType.SRT, EndpointType.SRTLA -> listOf(TS, MP4)
                else -> emptyList()
            }
        }

        fun fromPrefValue(value: String?, endpointType: EndpointType): LocalRecordingFormat {
            val supported = supportedFor(endpointType)
            val parsed = entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
            return if (parsed != null && parsed in supported) parsed else DEFAULT
        }
    }
}

/**
 * Side-effect-free configuration describing a local recording target.
 */
data class LocalRecordingConfig(
    val fileNamePrefix: String,
    val extension: FileExtension
)
