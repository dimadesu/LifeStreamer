package com.dimadesu.lifestreamer.permissions

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Simple runtime permission helper using Activity Result APIs.
 *
 * Usage:
 * val requester = PermissionRequester(this)
 * requester.requestPermissionsIfNeeded { granted -> /* proceed if granted */ }
 */
class PermissionRequester(private val activity: ComponentActivity) {
    private val permissionsLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results: Map<String, Boolean> ->
            val allGranted = results.values.all { it }
            permissionCallback?.invoke(allGranted)
            permissionCallback = null
        }

    private var permissionCallback: ((Boolean) -> Unit)? = null

    /**
     * Request audio permission at startup if not already granted.
     * Bluetooth permissions are requested on-demand when user taps the BT toggle.
     */
    fun requestPermissionsIfNeeded(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        val needed = mutableListOf<String>()

        // Always need audio for microphone
        if (!isPermissionGranted(activity, Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        // Note: Bluetooth permissions (BLUETOOTH_CONNECT, BLUETOOTH_SCAN) are NOT requested here.
        // They are requested on-demand when user taps the BT mic toggle, which provides
        // better UX by not overwhelming users who don't need BT mic functionality.

        if (needed.isEmpty()) {
            callback(true)
            permissionCallback = null
            return
        }

        // Optionally show rationale if needed
        val showRationale = needed.any { activity.shouldShowRequestPermissionRationale(it) }
        if (showRationale) {
            AlertDialog.Builder(activity)
                .setTitle("Permissions required")
                .setMessage("This app needs microphone permission to capture audio for streaming.")
                .setPositiveButton("OK") { _, _ ->
                    permissionsLauncher.launch(needed.toTypedArray())
                }
                .setNegativeButton("Cancel") { _, _ ->
                    callback(false)
                    permissionCallback = null
                }
                .show()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    companion object {
        fun isPermissionGranted(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
