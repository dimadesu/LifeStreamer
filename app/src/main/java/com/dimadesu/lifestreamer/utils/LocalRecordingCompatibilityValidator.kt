package com.dimadesu.lifestreamer.utils

import android.media.MediaFormat
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.models.FileExtension
import com.dimadesu.lifestreamer.models.LocalRecordingFormat
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaContainerType

/**
 * Validates that selected video and audio encoder MIME types are supported by a local
 * recording container format, returning a human-readable error message when incompatible.
 */
object LocalRecordingCompatibilityValidator {

    private val VIDEO_CODECS_BY_CONTAINER: Map<MediaContainerType, Set<String>> = mapOf(
        MediaContainerType.TS to setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC
        ),
        // Android MediaMuxer on minSdk 24 (API 24+)
        MediaContainerType.MP4 to setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_H263,
            MediaFormat.MIMETYPE_VIDEO_MPEG4
        ),
        MediaContainerType.FLV to setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_VIDEO_AV1
        ),
        MediaContainerType.WEBM to setOf(
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9
        ),
        MediaContainerType.THREEGP to setOf(
            MediaFormat.MIMETYPE_VIDEO_H263,
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_VP9
        ),
        MediaContainerType.OGG to emptySet()
    )

    private val AUDIO_CODECS_BY_CONTAINER: Map<MediaContainerType, Set<String>> = mapOf(
        MediaContainerType.TS to setOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_OPUS
        ),
        // Android MediaMuxer does not support Opus in MP4
        MediaContainerType.MP4 to setOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB
        ),
        MediaContainerType.FLV to setOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_OPUS
        ),
        MediaContainerType.WEBM to setOf(
            MediaFormat.MIMETYPE_AUDIO_VORBIS,
            MediaFormat.MIMETYPE_AUDIO_OPUS
        ),
        MediaContainerType.THREEGP to setOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB
        ),
        MediaContainerType.OGG to setOf(
            MediaFormat.MIMETYPE_AUDIO_VORBIS,
            MediaFormat.MIMETYPE_AUDIO_OPUS
        )
    )

    fun isVideoSupported(containerType: MediaContainerType, videoMimeType: String): Boolean {
        return VIDEO_CODECS_BY_CONTAINER[containerType]?.contains(videoMimeType) == true
    }

    fun isAudioSupported(containerType: MediaContainerType, audioMimeType: String): Boolean {
        return AUDIO_CODECS_BY_CONTAINER[containerType]?.contains(audioMimeType) == true
    }

    /**
     * Validates [videoMimeType] and [audioMimeType] against [extension].
     *
     * @return `null` if compatible, or an actionable error message describing what to fix.
     */
    fun validate(
        extension: FileExtension,
        videoMimeType: String?,
        audioMimeType: String?,
        endpointType: EndpointType? = null
    ): String? {
        val container = extension.containerType
        val videoIncompatible = videoMimeType != null && !isVideoSupported(container, videoMimeType)
        val audioIncompatible = audioMimeType != null && !isAudioSupported(container, audioMimeType)

        if (!videoIncompatible && !audioIncompatible) {
            return null
        }

        val containerLabel = container.name
        val issues = mutableListOf<String>()
        if (videoIncompatible) {
            issues += "${displayCodecName(videoMimeType)} video"
        }
        if (audioIncompatible) {
            issues += "${displayCodecName(audioMimeType)} audio"
        }

        val alternativeFormat = suggestAlternativeFormat(videoMimeType, audioMimeType, endpointType, container)
        val suggestion = buildSuggestion(
            videoIncompatible = videoIncompatible,
            audioIncompatible = audioIncompatible,
            container = container,
            alternativeFormat = alternativeFormat
        )

        return "$containerLabel recording does not support ${issues.joinToString(" and ")}. $suggestion"
    }

    private fun suggestAlternativeFormat(
        videoMimeType: String?,
        audioMimeType: String?,
        endpointType: EndpointType?,
        currentContainer: MediaContainerType
    ): LocalRecordingFormat? {
        if (endpointType == null || !endpointType.isStreaming) return null
        return LocalRecordingFormat.supportedFor(endpointType).firstOrNull { format ->
            format.extension.containerType != currentContainer &&
                (videoMimeType == null || isVideoSupported(format.extension.containerType, videoMimeType)) &&
                (audioMimeType == null || isAudioSupported(format.extension.containerType, audioMimeType))
        }
    }

    private fun buildSuggestion(
        videoIncompatible: Boolean,
        audioIncompatible: Boolean,
        container: MediaContainerType,
        alternativeFormat: LocalRecordingFormat?
    ): String {
        val encoderFix = when {
            videoIncompatible && audioIncompatible ->
                "Change Video/Audio encoders in Settings (e.g. H.264/H.265 + AAC)"
            videoIncompatible -> {
                val supported = VIDEO_CODECS_BY_CONTAINER[container].orEmpty()
                    .map(::displayCodecName)
                    .filter { it == "H.264" || it == "H.265" || it == "VP9" || it == "AV1" || it == "VP8" }
                    .distinct()
                if (supported.isNotEmpty()) {
                    "Change Video encoder to ${supported.joinToString("/")}"
                } else {
                    "Change Video encoder in Settings"
                }
            }
            else -> {
                val supported = AUDIO_CODECS_BY_CONTAINER[container].orEmpty()
                    .map(::displayCodecName)
                    .filter { it == "AAC" || it == "Opus" }
                    .distinct()
                if (supported.isNotEmpty()) {
                    "Change Audio encoder to ${supported.joinToString("/")}"
                } else {
                    "Change Audio encoder in Settings"
                }
            }
        }

        return if (alternativeFormat != null) {
            "$encoderFix or switch Recording format to ${alternativeFormat.extension.containerType.name}."
        } else {
            "$encoderFix."
        }
    }

    private fun displayCodecName(mimeType: String): String = when (mimeType) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
        MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
        MediaFormat.MIMETYPE_VIDEO_H263 -> "H.263"
        MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
        else -> mimeType
    }
}
