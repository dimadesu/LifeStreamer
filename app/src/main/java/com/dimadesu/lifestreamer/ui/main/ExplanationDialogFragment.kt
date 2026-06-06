package com.dimadesu.lifestreamer.ui.main

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.dimadesu.lifestreamer.R

/**
 * Reusable explanation dialog that survives orientation changes.
 * Communicates result via Fragment Result API using the provided request key.
 * Result bundle contains "confirmed" (Boolean).
 */
class ExplanationDialogFragment : DialogFragment() {

    companion object {
        const val SYS_AUDIO_TAG = "sys_audio_explanation"
        const val RTMP_AUDIO_TAG = "rtmp_audio_explanation"

        const val RESULT_CONFIRMED = "confirmed"

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(titleRes: Int, messageRes: Int, requestKey: String) =
            ExplanationDialogFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to titleRes,
                    ARG_MESSAGE to messageRes,
                    ARG_REQUEST_KEY to requestKey
                )
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val titleRes = requireArguments().getInt(ARG_TITLE)
        val messageRes = requireArguments().getInt(ARG_MESSAGE)
        val requestKey = requireArguments().getString(ARG_REQUEST_KEY)!!

        return AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.continue_button) { _, _ ->
                setFragmentResult(requestKey, bundleOf(RESULT_CONFIRMED to true))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                setFragmentResult(requestKey, bundleOf(RESULT_CONFIRMED to false))
            }
            .create()
    }
}
