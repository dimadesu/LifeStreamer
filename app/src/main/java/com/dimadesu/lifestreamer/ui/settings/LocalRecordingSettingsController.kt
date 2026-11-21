package com.dimadesu.lifestreamer.ui.settings

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.models.FileExtension
import com.dimadesu.lifestreamer.models.LocalRecordingFormat
import com.dimadesu.lifestreamer.utils.LocalRecordingCompatibilityValidator
import com.dimadesu.lifestreamer.utils.RecordingFileNameFormatter

/**
 * Modular controller managing the shared local-recording preference category for both
 * standalone file endpoints (TS/MP4/FLV/WebM/OGG/3GP) and streaming endpoints (SRT/SRTLA/RTMP).
 */
class LocalRecordingSettingsController(
    private val fragment: PreferenceFragmentCompat,
    private val videoEncoderProvider: () -> String?,
    private val audioEncoderProvider: () -> String?
) {
    private lateinit var category: PreferenceCategory
    private lateinit var enableSwitch: SwitchPreference
    private lateinit var formatList: ListPreference
    private lateinit var fileNamePrefixPref: EditTextPreference
    private lateinit var warningPref: Preference

    private var currentEndpointType: EndpointType = EndpointType.SRT

    fun bind() {
        val ctx = fragment.requireContext()
        category = requireNotNull(fragment.findPreference(ctx.getString(R.string.file_endpoint_key)))
        enableSwitch = requireNotNull(fragment.findPreference(ctx.getString(R.string.record_to_disk_enable_key)))
        formatList = requireNotNull(fragment.findPreference(ctx.getString(R.string.record_to_disk_format_key)))
        fileNamePrefixPref = requireNotNull(fragment.findPreference(ctx.getString(R.string.file_name_key)))
        warningPref = requireNotNull(fragment.findPreference(ctx.getString(R.string.local_recording_warning_key)))

        // Normalize legacy stored values that included a trailing extension (e.g. "StreamPackFile.mp4")
        val currentText = fileNamePrefixPref.text
        if (!currentText.isNullOrEmpty()) {
            val sanitized = RecordingFileNameFormatter.sanitizePrefix(currentText)
            if (sanitized != currentText) {
                fileNamePrefixPref.text = sanitized
            }
        }

        fileNamePrefixPref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val extension = resolveActiveFileExtension() ?: FileExtension.TS
            val preview = RecordingFileNameFormatter.formatPreview(pref.text, extension)
            "Movies/LifeStreamer/$preview"
        }

        fileNamePrefixPref.setOnPreferenceChangeListener { _, newValue ->
            val sanitized = RecordingFileNameFormatter.sanitizePrefix(newValue as? String)
            if (sanitized != newValue) {
                fileNamePrefixPref.text = sanitized
                false
            } else {
                true
            }
        }

        enableSwitch.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            updateStreamingSubControlsVisibility(enabled)
            updateCompatibilityWarning(
                videoMimeType = videoEncoderProvider(),
                audioMimeType = audioEncoderProvider(),
                recordingEnabledOverride = enabled
            )
            true
        }

        formatList.setOnPreferenceChangeListener { _, newValue ->
            val newFormatPref = newValue as? String
            refreshFileNameSummary(formatPrefOverride = newFormatPref)
            updateCompatibilityWarning(
                videoMimeType = videoEncoderProvider(),
                audioMimeType = audioEncoderProvider(),
                formatPrefOverride = newFormatPref
            )
            true
        }
    }

    fun updateForEndpoint(
        endpointType: EndpointType,
        videoMimeType: String? = videoEncoderProvider(),
        audioMimeType: String? = audioEncoderProvider()
    ) {
        currentEndpointType = endpointType
        val ctx = fragment.requireContext()

        if (endpointType.isLocalFile) {
            category.isVisible = true
            category.title = ctx.getString(R.string.file)
            enableSwitch.isVisible = false
            formatList.isVisible = false
            fileNamePrefixPref.isVisible = true
            warningPref.isVisible = false
        } else {
            category.isVisible = true
            category.title = ctx.getString(R.string.local_recording)
            enableSwitch.isVisible = true

            val supportedFormats = LocalRecordingFormat.supportedFor(endpointType)
            formatList.entries = supportedFormats.map { ctx.getString(it.labelResId) }.toTypedArray()
            formatList.entryValues = supportedFormats.map { it.prefValue }.toTypedArray()

            val effectiveFormat = LocalRecordingFormat.fromPrefValue(formatList.value, endpointType)
            formatList.value = effectiveFormat.prefValue
            formatList.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

            updateStreamingSubControlsVisibility(enableSwitch.isChecked)
            updateCompatibilityWarning(videoMimeType, audioMimeType)
        }

        refreshFileNameSummary()
    }

    fun updateCompatibilityWarning(
        videoMimeType: String? = videoEncoderProvider(),
        audioMimeType: String? = audioEncoderProvider(),
        recordingEnabledOverride: Boolean? = null,
        formatPrefOverride: String? = null
    ) {
        if (!currentEndpointType.isStreaming) {
            warningPref.isVisible = false
            return
        }
        val isEnabled = recordingEnabledOverride ?: enableSwitch.isChecked
        if (!isEnabled) {
            warningPref.isVisible = false
            return
        }

        val format = LocalRecordingFormat.fromPrefValue(
            formatPrefOverride ?: formatList.value,
            currentEndpointType
        )
        val error = LocalRecordingCompatibilityValidator.validate(
            extension = format.extension,
            videoMimeType = videoMimeType,
            audioMimeType = audioMimeType,
            endpointType = currentEndpointType
        )

        if (error != null) {
            val ctx = fragment.requireContext()
            val titleText = ctx.getString(R.string.local_recording_warning_title)
            val coloredTitle = SpannableString(titleText).apply {
                setSpan(
                    ForegroundColorSpan(Color.parseColor("#D32F2F")),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            warningPref.title = coloredTitle
            warningPref.summary = error
            warningPref.isVisible = true
        } else {
            warningPref.isVisible = false
        }
    }

    private fun updateStreamingSubControlsVisibility(recordingEnabled: Boolean) {
        formatList.isVisible = recordingEnabled
        fileNamePrefixPref.isVisible = recordingEnabled
    }

    private fun resolveActiveFileExtension(formatPrefOverride: String? = null): FileExtension? {
        return when {
            currentEndpointType.isLocalFile -> FileExtension.fromEndpointType(currentEndpointType)
            currentEndpointType.isStreaming -> LocalRecordingFormat.fromPrefValue(
                formatPrefOverride ?: formatList.value,
                currentEndpointType
            ).extension
            else -> null
        }
    }

    private fun refreshFileNameSummary(formatPrefOverride: String? = null) {
        // Re-assigning the provider triggers Preference.notifyChanged() so the preview updates
        // immediately when the endpoint type or recording container format changes.
        fileNamePrefixPref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val extension = resolveActiveFileExtension(formatPrefOverride) ?: FileExtension.TS
            val preview = RecordingFileNameFormatter.formatPreview(pref.text, extension)
            "Movies/LifeStreamer/$preview"
        }
    }
}
