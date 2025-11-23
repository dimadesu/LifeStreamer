package com.dimadesu.lifestreamer.audio

import android.media.AudioDeviceInfo

object BluetoothAudioConfig {
    // Always enable Bluetooth mic by default (hardcoded policy).
    // Keep setter as a no-op for compatibility with existing call sites.
    @Deprecated("Bluetooth hardcoded on", level = DeprecationLevel.HIDDEN)
    fun setEnabled(@Suppress("UNUSED_PARAMETER") v: Boolean) { /* no-op */ }
    fun isEnabled(): Boolean = true

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    fun setPreferredDevice(d: AudioDeviceInfo?) { preferredDevice = d }
    fun getPreferredDevice(): AudioDeviceInfo? = preferredDevice
}
