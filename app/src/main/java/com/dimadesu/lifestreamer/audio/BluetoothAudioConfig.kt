package com.dimadesu.lifestreamer.audio

import android.media.AudioDeviceInfo

object BluetoothAudioConfig {
    // Default policy: Bluetooth mic enabled.
    // Make this writable so the UI toggle can control the app-level policy.
    @Volatile
    private var enabledFlag: Boolean = false
    fun setEnabled(v: Boolean) { enabledFlag = v }
    fun isEnabled(): Boolean = enabledFlag

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    fun setPreferredDevice(d: AudioDeviceInfo?) { preferredDevice = d }
    fun getPreferredDevice(): AudioDeviceInfo? = preferredDevice
}
