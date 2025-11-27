package com.dimadesu.lifestreamer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
 * - Starting SCO and waiting for connection (using modern or legacy APIs)
 * - Checking headset profile connection state as fallback
 * 
 * On Android 12+ (API 31+), uses the modern setCommunicationDevice() API.
 * On older versions, uses the deprecated startBluetoothSco()/stopBluetoothSco() APIs.
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
    companion object {
        private const val TAG = "ScoOrchestrator"
    }

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
    @SuppressLint("MissingPermission")
    fun isHeadsetConnected(): Boolean {
        try {
            @Suppress("DEPRECATION")
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
     * Check if Bluetooth SCO is currently active.
     * On Android 12+, checks if a BT SCO device is set as communication device.
     * On older versions, uses the deprecated isBluetoothScoOn property.
     * 
     * @return true if SCO audio is active, false otherwise
     */
    fun isScoActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isScoActiveModern(audioManager)
        } else {
            isScoActiveLegacy(audioManager)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun isScoActiveModern(audioManager: AudioManager): Boolean {
        return try {
            val commDevice = audioManager.communicationDevice
            commDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    private fun isScoActiveLegacy(audioManager: AudioManager): Boolean {
        return try { audioManager.isBluetoothScoOn } catch (_: Throwable) { false }
    }

    /**
     * Start Bluetooth SCO and wait for it to become active.
     * On Android 12+, uses setCommunicationDevice() with the detected BT SCO device.
     * On older versions, uses the deprecated startBluetoothSco() and polls isBluetoothScoOn.
     * 
     * @param timeoutMs Maximum time to wait for SCO activation in milliseconds
     * @return true if SCO became active within timeout, false otherwise
     */
    suspend fun startScoAndWait(timeoutMs: Long): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Try modern API first, fallback to legacy if it fails
            val modernResult = startScoModern(audioManager)
            if (modernResult) {
                true
            } else {
                Log.i(TAG, "startScoAndWait: modern API failed, falling back to legacy")
                startScoLegacy(audioManager, timeoutMs)
            }
        } else {
            startScoLegacy(audioManager, timeoutMs)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startScoModern(audioManager: AudioManager): Boolean {
        val btDevice = detectBtInputDevice()
        if (btDevice == null) {
            Log.w(TAG, "startScoModern: No BT SCO device found")
            return false
        }
        
        // Set audio mode to communication BEFORE setting communication device
        // Some Samsung devices require this order
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.i(TAG, "startScoModern: set audio mode to MODE_IN_COMMUNICATION")
            } catch (e: Throwable) {
                Log.w(TAG, "startScoModern: failed to set audio mode: ${e.message}")
            }
        }
        
        return try {
            val result = audioManager.setCommunicationDevice(btDevice)
            Log.i(TAG, "startScoModern: setCommunicationDevice result=$result for device=${btDevice.productName}")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "startScoModern: setCommunicationDevice failed: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun startScoLegacy(audioManager: AudioManager, timeoutMs: Long): Boolean {
        try {
            try { audioManager.startBluetoothSco() } catch (_: Throwable) {}
            Log.i(TAG, "startScoLegacy: startBluetoothSco called")
            
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (audioManager.isBluetoothScoOn) {
                        Log.i(TAG, "startScoLegacy: SCO is now on")
                        return true
                    }
                } catch (_: Throwable) {}
                delay(200)
            }
            Log.w(TAG, "startScoLegacy: Timeout waiting for SCO")
            return false
        } finally {
            // leave stopping to caller when they need to
        }
    }

    /**
     * Stop Bluetooth SCO without throwing exceptions.
     * On Android 12+, uses clearCommunicationDevice().
     * On older versions, uses the deprecated stopBluetoothSco().
     * Safe to call even if SCO was never started.
     */
    fun stopScoQuietly() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopScoModern(audioManager)
            } else {
                stopScoLegacy(audioManager)
            }
        } catch (_: Throwable) {}
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopScoModern(audioManager: AudioManager) {
        try {
            audioManager.clearCommunicationDevice()
            Log.i(TAG, "stopScoModern: clearCommunicationDevice called")
        } catch (e: Throwable) {
            Log.w(TAG, "stopScoModern: clearCommunicationDevice failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScoLegacy(audioManager: AudioManager) {
        try {
            audioManager.stopBluetoothSco()
            Log.i(TAG, "stopScoLegacy: stopBluetoothSco called")
        } catch (e: Throwable) {
            Log.w(TAG, "stopScoLegacy: stopBluetoothSco failed: ${e.message}")
        }
    }
}
