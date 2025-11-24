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

class ScoOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bluetoothConnectPermissionRequest: MutableSharedFlow<Unit>
) {
    fun detectBtInputDevice(): AudioDeviceInfo? {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { d -> try { d.isSource && d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } catch (_: Throwable) { false } }
        } catch (_: Throwable) { null }
    }

    fun ensurePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                try { bluetoothConnectPermissionRequest.tryEmit(Unit) } catch (_: Throwable) {}
            }
            granted
        } else true
    }

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

    fun stopScoQuietly() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try { audioManager?.stopBluetoothSco() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
