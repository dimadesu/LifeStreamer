package com.dimadesu.lifestreamer.audio

import android.media.AudioDeviceInfo

/**
 * Global configuration singleton for Bluetooth audio input preferences.
 * 
 * This object holds the app-level policy for whether to prefer Bluetooth HFP/SCO audio
 * input when available. The UI toggle controls this policy, and the service orchestrates
 * SCO negotiation based on it.
 * 
 * Flow:
 * 1. User toggles "Use Bluetooth Mic" in UI
 * 2. ViewModel updates this config via [setEnabled]
 * 3. Service detects BT input device and attempts SCO negotiation
 * 4. Once SCO connected, service may switch audio source or rely on platform routing
 * 5. When BT device disconnects, service reverts to built-in mic automatically
 */
object BluetoothAudioConfig {
    /**
     * Whether Bluetooth mic is enabled app-wide.
     * Defaults to false - user must explicitly enable via UI toggle.
     */
    @Volatile
    private var enabledFlag: Boolean = false
    
    /**
     * Enable or disable Bluetooth mic preference.
     * @param v true to prefer Bluetooth mic when available, false to use built-in mic
     */
    fun setEnabled(v: Boolean) { enabledFlag = v }
    
    /**
     * Check if Bluetooth mic preference is currently enabled.
     * @return true if BT mic should be preferred, false otherwise
     */
    fun isEnabled(): Boolean = enabledFlag

    /**
     * Currently detected/preferred Bluetooth input device.
     * Set by service during SCO negotiation, cleared on disconnect.
     */
    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    /**
     * Set the preferred Bluetooth input device.
     * @param d AudioDeviceInfo for the BT SCO input device, or null to clear
     */
    fun setPreferredDevice(d: AudioDeviceInfo?) { preferredDevice = d }
    
    /**
     * Get the currently preferred Bluetooth input device.
     * @return AudioDeviceInfo if a BT device is set, null otherwise
     */
    fun getPreferredDevice(): AudioDeviceInfo? = preferredDevice
}
