package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile

/**
 * Helper class that centralizes SCO (Synchronous Connection Oriented) link orchestration
 * for Bluetooth HFP (Hands-Free Profile) audio routing.
 * 
 * SCO is the Bluetooth link type used for bidirectional voice audio between Android and
 * Bluetooth headsets. This orchestrator handles:
 * - Detecting available Bluetooth SCO input devices
 * - Ensuring required permissions (BLUETOOTH_CONNECT on Android 12+)
 * - Starting SCO and waiting for connection
 * - Checking headset profile connection state as fallback
 * 
 * @param context Application context for accessing system services
 * @param scope CoroutineScope for async operations
 * @param bluetoothConnectPermissionRequest Flow to request BLUETOOTH_CONNECT permission from UI
 */
class ScoOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bluetoothConnectPermissionRequest: MutableSharedFlow<Unit>
) {
    /**
     * Detect if a Bluetooth SCO input device is currently available.
     * 
     * @return AudioDeviceInfo for TYPE_BLUETOOTH_SCO input device, or null if none found
     */
    fun detectBtInputDevice(): AudioDeviceInfo? {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { d -> try { d.isSource && d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } catch (_: Throwable) { false } }
        } catch (_: Throwable) { null }
    }

    /**
     * Check if a Bluetooth headset is connected via HFP profile.
     * This is a fallback check when AudioDeviceInfo detection fails.
     * 
     * @return true if headset profile is connected, false otherwise
     */
    fun isHeadsetConnected(): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val state = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            return state == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) { return false }
    }

    /**
     * Ensure BLUETOOTH_CONNECT permission is granted (Android 12+).
     * On older Android versions, always returns true.
     * If permission is missing, emits a request to the UI via the shared flow.
     * 
     * @return true if permission is granted or not required, false if missing
     */
    fun ensurePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                try { bluetoothConnectPermissionRequest.tryEmit(Unit) } catch (_: Throwable) {}
            }
            granted
        } else true
    }

    /**
     * Start Bluetooth SCO and wait for it to become active.
     * Polls AudioManager.isBluetoothScoOn until timeout or success.
     * 
     * @param timeoutMs Maximum time to wait for SCO activation in milliseconds
     * @return true if SCO became active within timeout, false otherwise
     */
    suspend fun startScoAndWait(timeoutMs: Long): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        try {
            try { audioManager.startBluetoothSco() } catch (_: Throwable) {}
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (audioManager.isBluetoothScoOn) return true
                } catch (_: Throwable) {}
                delay(200)
            }
            return false
        } finally {
            // leave stopping to caller when they need to
        }
    }

    /**
     * Stop Bluetooth SCO without throwing exceptions.
     * Safe to call even if SCO was never started.
     */
    fun stopScoQuietly() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try { audioManager?.stopBluetoothSco() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
