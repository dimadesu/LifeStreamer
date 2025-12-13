package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

/**
 * Audio source factory that handles mic-based audio with USB awareness.
 * 
 * @param forceUnprocessed When true, forces UNPROCESSED audio source regardless of USB detection.
 *                         Use this when switching to USB video to force audio reinitialization.
 *                         When false (default), auto-detects USB audio and chooses appropriately.
 * 
 * Sources used:
 * - forceUnprocessed=true → MicrophoneSource(unprocessed=true) with no effects
 * - forceUnprocessed=false + USB detected → MicrophoneSource(unprocessed=true) with no effects
 * - forceUnprocessed=false + no USB → MicrophoneSource(unprocessed=false) with AEC+NS effects
 * 
 * SCO/Bluetooth is negotiated asynchronously by the service and will call
 * `setAudioSource(...)` to switch to BluetoothAudioSource only after SCO is confirmed.
 */
class ConditionalAudioSourceFactory(
    private val forceUnprocessed: Boolean = false
) : IAudioSourceInternal.Factory {
    
    companion object {
        private const val TAG = "ConditionalAudioSrcFact"
        
        /**
         * Check if a USB audio input device is currently connected.
         */
        fun hasUsbAudioInput(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            
            return try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
                
                val usbDevice = inputDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                }
                
                if (usbDevice != null) {
                    Log.d(TAG, "Found USB audio input: ${usbDevice.productName ?: "Unknown"} (type=${usbDevice.type})")
                }
                
                usbDevice != null
            } catch (e: Exception) {
                Log.w(TAG, "Error checking for USB audio: ${e.message}")
                false
            }
        }
    }

    override suspend fun create(context: Context): IAudioSourceInternal {
        val useUnprocessed = forceUnprocessed || hasUsbAudioInput(context)
        
        return if (useUnprocessed) {
            Log.i(TAG, "Using UNPROCESSED mode (forced=$forceUnprocessed, usbDetected=${!forceUnprocessed})")
            MicrophoneSourceFactory(unprocessed = true).create(context)
        } else {
            Log.i(TAG, "Using DEFAULT mode with AEC+NS effects")
            MicrophoneSourceFactory(unprocessed = false).create(context)
        }
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // If source is null, we need to create a new source
        if (source == null) return false
        
        // MicrophoneSource is internal in StreamPack, so check by class name
        val className = source.javaClass.simpleName
        return className == "MicrophoneSource"
    }
    
    override fun toString(): String {
        return "ConditionalAudioSourceFactory(forceUnprocessed=$forceUnprocessed)"
    }
}
