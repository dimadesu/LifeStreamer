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
     * Request relevant permissions for audio + Bluetooth usage if not already granted.
     */
    fun requestPermissionsIfNeeded(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        val needed = mutableListOf<String>()

        // Always need audio
        if (!isPermissionGranted(activity, Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        // Bluetooth permissions differ by SDK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isPermissionGranted(activity, Manifest.permission.BLUETOOTH_CONNECT)) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!isPermissionGranted(activity, Manifest.permission.BLUETOOTH_SCAN)) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            // Pre-Android 12: BLUETOOTH and BLUETOOTH_ADMIN are normal permissions declared in manifest
            // but we still check for BLUETOOTH here for completeness.
            if (!isPermissionGranted(activity, Manifest.permission.BLUETOOTH)) {
                // BLUETOOTH is protection level "normal" and auto-granted, skip explicit request
            }
        }

        // Android 13+ introduced NEARBY_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby = "android.permission.NEARBY_DEVICES"
            if (!isPermissionGranted(activity, nearby)) {
                needed.add(nearby)
            }
        }

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
                .setMessage("This app needs microphone and Bluetooth permissions to use a Bluetooth headset as the audio input.")
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
