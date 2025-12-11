package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages USB audio device detection and automatic audio source switching.
 * 
 * When USB audio is plugged/unplugged during streaming, this manager will
 * reconfigure the audio source to use the optimal settings:
 * - USB audio → UNPROCESSED (raw capture)
 * - Built-in mic → DEFAULT (with system processing)
 */
class UsbAudioManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "UsbAudioManager"
        // Debounce delay to avoid rapid reconnections
        private const val DEBOUNCE_DELAY_MS = 500L
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var isRegistered = false
    
    // Callback to notify when USB audio state changes
    var onUsbAudioChanged: ((hasUsbAudio: Boolean) -> Unit)? = null
    
    // Track last known state to avoid duplicate callbacks
    private var lastKnownUsbState: Boolean? = null
    
    /**
     * Start monitoring for USB audio device changes.
     */
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "USB audio monitoring requires API 23+")
            return
        }
        
        if (isRegistered) {
            Log.d(TAG, "Already monitoring USB audio devices")
            return
        }
        
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                addedDevices?.forEach { device ->
                    if (isUsbAudioInput(device)) {
                        Log.i(TAG, "USB audio input connected: ${device.productName ?: "Unknown"}")
                        notifyUsbAudioChanged(true)
                    }
                }
            }
            
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                removedDevices?.forEach { device ->
                    if (isUsbAudioInput(device)) {
                        Log.i(TAG, "USB audio input disconnected: ${device.productName ?: "Unknown"}")
                        // Check if there's still another USB audio device connected
                        val stillHasUsb = hasUsbAudioInput()
                        notifyUsbAudioChanged(stillHasUsb)
                    }
                }
            }
        }
        
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        isRegistered = true
        
        // Initialize state
        lastKnownUsbState = hasUsbAudioInput()
        Log.i(TAG, "Started USB audio monitoring (current USB state: $lastKnownUsbState)")
    }
    
    /**
     * Stop monitoring for USB audio device changes.
     */
    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        
        if (!isRegistered) {
            return
        }
        
        audioDeviceCallback?.let {
            audioManager.unregisterAudioDeviceCallback(it)
        }
        audioDeviceCallback = null
        isRegistered = false
        lastKnownUsbState = null
        Log.i(TAG, "Stopped USB audio monitoring")
    }
    
    /**
     * Check if a USB audio input device is currently connected.
     */
    fun hasUsbAudioInput(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        
        return try {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            inputDevices.any { isUsbAudioInput(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking USB audio: ${e.message}")
            false
        }
    }
    
    /**
     * Reconfigure audio source on the streamer based on current USB audio state.
     * Call this when USB audio is plugged/unplugged during streaming.
     */
    fun reconfigureAudioSource(streamer: ISingleStreamer) {
        scope.launch(Dispatchers.Main) {
            try {
                // Small delay to let the audio system stabilize
                delay(DEBOUNCE_DELAY_MS)
                
                val hasUsb = hasUsbAudioInput()
                Log.i(TAG, "Reconfiguring audio source (USB audio: $hasUsb)")
                
                // Cast to IWithAudioSource to access setAudioSource
                val audioStreamer = streamer as? IWithAudioSource
                if (audioStreamer == null) {
                    Log.w(TAG, "Streamer does not support audio source switching")
                    return@launch
                }
                
                // Use ConditionalAudioSourceFactory which will create SmartMicrophoneSource
                // SmartMicrophoneSource detects USB audio at configure() time
                audioStreamer.setAudioSource(ConditionalAudioSourceFactory())
                
                Log.i(TAG, "Audio source reconfigured successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconfigure audio source: ${e.message}", e)
            }
        }
    }
    
    private fun isUsbAudioInput(device: AudioDeviceInfo): Boolean {
        if (!device.isSource) return false
        
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
               device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
               device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
    
    private fun notifyUsbAudioChanged(hasUsbAudio: Boolean) {
        // Debounce - only notify if state actually changed
        if (lastKnownUsbState == hasUsbAudio) {
            Log.d(TAG, "USB audio state unchanged ($hasUsbAudio), skipping notification")
            return
        }
        
        lastKnownUsbState = hasUsbAudio
        
        scope.launch(Dispatchers.Main) {
            // Additional debounce delay
            delay(DEBOUNCE_DELAY_MS)
            
            // Verify state hasn't changed during debounce
            val currentState = hasUsbAudioInput()
            if (currentState == hasUsbAudio) {
                Log.i(TAG, "Notifying USB audio state change: $hasUsbAudio")
                onUsbAudioChanged?.invoke(hasUsbAudio)
            } else {
                Log.d(TAG, "USB state changed during debounce, using current: $currentState")
                lastKnownUsbState = currentState
                onUsbAudioChanged?.invoke(currentState)
            }
        }
    }
    
    /**
     * Clean up resources.
     */
    fun cleanup() {
        stopMonitoring()
        onUsbAudioChanged = null
    }
}
