package com.dimadesu.lifestreamer.utils

import android.media.MediaFormat
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.models.FileExtension
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecordingCompatibilityValidatorTest {

    @Test
    fun `TS supports H264 and H265 with AAC and Opus`() {
        assertNull(
            LocalRecordingCompatibilityValidator.validate(
                FileExtension.TS,
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaFormat.MIMETYPE_AUDIO_AAC,
                EndpointType.SRT
            )
        )
        assertNull(
            LocalRecordingCompatibilityValidator.validate(
                FileExtension.TS,
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                EndpointType.SRTLA
            )
        )
    }

    @Test
    fun `MP4 rejects Opus audio and suggests AAC or TS`() {
        val error = LocalRecordingCompatibilityValidator.validate(
            FileExtension.MP4,
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            EndpointType.SRT
        )
        assertNotNull(error)
        assertTrue(error!!.contains("MP4"))
        assertTrue(error.contains("Opus"))
        assertTrue(error.contains("AAC"))
        assertTrue(error.contains("TS"))
    }

    @Test
    fun `TS rejects VP9 video on RTMP and suggests H264 or H265 or FLV`() {
        val error = LocalRecordingCompatibilityValidator.validate(
            FileExtension.TS,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_AUDIO_AAC,
            EndpointType.RTMP
        )
        assertNotNull(error)
        assertTrue(error!!.contains("VP9"))
        assertTrue(error.contains("FLV"))
    }

    @Test
    fun `FLV accepts AV1 and Opus on RTMP`() {
        assertNull(
            LocalRecordingCompatibilityValidator.validate(
                FileExtension.FLV,
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                EndpointType.RTMP
            )
        )
    }

    @Test
    fun `MP4 rejects both AV1 video and Opus audio on RTMP`() {
        val error = LocalRecordingCompatibilityValidator.validate(
            FileExtension.MP4,
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            EndpointType.RTMP
        )
        assertNotNull(error)
        assertTrue(error!!.contains("AV1"))
        assertTrue(error.contains("Opus"))
        assertTrue(error.contains("FLV"))
    }
}
