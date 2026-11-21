package com.dimadesu.lifestreamer.utils

import com.dimadesu.lifestreamer.models.FileExtension
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecordingFileNameFormatterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int): Long {
        return Calendar.getInstance(utc).apply {
            set(year, month - 1, day, hour, min, sec)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `default prefix and timestamp format for TS`() {
        val ts = utcMillis(2026, 11, 21, 11, 32, 20)
        val result = RecordingFileNameFormatter.format(null, FileExtension.TS, ts, utc)
        assertEquals("LifeStreamer_2026-11-21_11-32-20.ts", result)
    }

    @Test
    fun `blank prefix falls back to LifeStreamer`() {
        val ts = utcMillis(2026, 1, 5, 9, 4, 3)
        val result = RecordingFileNameFormatter.format("   ", FileExtension.MP4, ts, utc)
        assertEquals("LifeStreamer_2026-01-05_09-04-03.mp4", result)
    }

    @Test
    fun `strips legacy video extension and migrates legacy defaults to LifeStreamer`() {
        val ts = utcMillis(2026, 11, 21, 12, 0, 0)
        assertEquals(
            "LifeStreamer_2026-11-21_12-00-00.mp4",
            RecordingFileNameFormatter.format("StreamPackFile.mp4", FileExtension.MP4, ts, utc)
        )
        assertEquals(
            "LifeStreamer_2026-11-21_12-00-00.ts",
            RecordingFileNameFormatter.format("output.ts", FileExtension.TS, ts, utc)
        )
        assertEquals(
            "LifeStreamer_2026-11-21_12-00-00.ts",
            RecordingFileNameFormatter.format("StreamPack", FileExtension.TS, ts, utc)
        )
        assertEquals(
            "MyTrip_2026-11-21_12-00-00.ts",
            RecordingFileNameFormatter.format("MyTrip.FLV", FileExtension.TS, ts, utc)
        )
    }

    @Test
    fun `sanitizes illegal filesystem characters`() {
        val ts = utcMillis(2026, 11, 21, 12, 0, 0)
        assertEquals(
            "My_Stream_1_2026-11-21_12-00-00.flv",
            RecordingFileNameFormatter.format("My/Stream:1", FileExtension.FLV, ts, utc)
        )
    }

    @Test
    fun `formatPreview returns deterministic placeholder`() {
        assertEquals(
            "LifeStreamer_YYYY-MM-DD_HH-MM-SS.ts",
            RecordingFileNameFormatter.formatPreview("", FileExtension.TS)
        )
        assertEquals(
            "Vlog_YYYY-MM-DD_HH-MM-SS.mp4",
            RecordingFileNameFormatter.formatPreview("Vlog.mp4", FileExtension.MP4)
        )
    }
}
