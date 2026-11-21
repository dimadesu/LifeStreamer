package com.dimadesu.lifestreamer.utils

import com.dimadesu.lifestreamer.models.FileExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Formats local recording file names with a sanitized prefix, start-of-recording timestamp,
 * and container extension (e.g. `LifeStreamer_2026-11-21_11-32-20.ts`).
 */
object RecordingFileNameFormatter {
    const val DEFAULT_PREFIX = "LifeStreamer"
    const val TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
    private const val PREVIEW_TIMESTAMP = "YYYY-MM-DD_HH-MM-SS"

    private val ILLEGAL_CHARS_REGEX = Regex("[/\\\\:*?\"<>|]")
    private val KNOWN_EXTENSIONS = FileExtension.entries.map { it.extension }
    private val LEGACY_DEFAULT_PREFIXES = setOf("streampackfile", "streampack", "output")

    /**
     * Cleans a raw user-entered filename prefix by stripping legacy video extensions,
     * migrating legacy default names (`StreamPackFile`, `StreamPack`, `output`) to [DEFAULT_PREFIX],
     * removing filesystem-unsafe characters, and falling back to [DEFAULT_PREFIX] if blank.
     */
    fun sanitizePrefix(rawPrefix: String?): String {
        var cleaned = rawPrefix?.trim().orEmpty()
        // Strip trailing known video extensions (e.g. legacy "StreamPackFile.mp4")
        var stripped: Boolean
        do {
            stripped = false
            for (ext in KNOWN_EXTENSIONS) {
                if (cleaned.endsWith(ext, ignoreCase = true)) {
                    cleaned = cleaned.dropLast(ext.length).trim()
                    stripped = true
                    break
                }
            }
        } while (stripped)

        cleaned = ILLEGAL_CHARS_REGEX.replace(cleaned, "_").trim()
        if (cleaned.isEmpty() || cleaned.lowercase(Locale.US) in LEGACY_DEFAULT_PREFIXES) {
            return DEFAULT_PREFIX
        }
        return cleaned
    }

    /**
     * Builds the final filename including start-of-recording timestamp and container extension.
     */
    fun format(
        rawPrefix: String?,
        extension: FileExtension,
        timestampMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val prefix = sanitizePrefix(rawPrefix)
        val formatter = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
            this.timeZone = timeZone
        }
        val timestamp = formatter.format(Date(timestampMs))
        return "${prefix}_${timestamp}${extension.extension}"
    }

    /**
     * Builds a deterministic example filename for Settings summaries.
     */
    fun formatPreview(rawPrefix: String?, extension: FileExtension): String {
        val prefix = sanitizePrefix(rawPrefix)
        return "${prefix}_${PREVIEW_TIMESTAMP}${extension.extension}"
    }
}
