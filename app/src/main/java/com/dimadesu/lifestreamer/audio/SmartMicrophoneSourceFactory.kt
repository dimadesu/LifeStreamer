package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

/**
 * Smart audio source factory that automatically detects USB audio and delegates
 * to the appropriate source:
 * - USB audio connected → UnprocessedAudioSource (raw capture, no DSP)
 * - Built-in mic → MicrophoneSource from StreamPack (DEFAULT with system processing)
 * 
 * This provides the best audio quality for each scenario:
 * - USB audio interfaces have their own high-quality preamps, don't need processing
 * - Built-in phone mics benefit from noise cancellation and echo suppression
 */
class SmartMicrophoneSourceFactory : IAudioSourceInternal.Factory {
    
    companion object {
        private const val TAG = "SmartMicSourceFactory"
        
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
        return if (hasUsbAudioInput(context)) {
            Log.i(TAG, "USB audio detected → creating UnprocessedAudioSource")
            UnprocessedAudioSourceFactory().create(context)
        } else {
            Log.i(TAG, "No USB audio → creating MicrophoneSource (StreamPack)")
            MicrophoneSourceFactory().create(context)
        }
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // Matches either MicrophoneSource (StreamPack) or UnprocessedAudioSource (ours)
        // This allows hot-switching between them when USB is plugged/unplugged
        // MicrophoneSource is internal in StreamPack, so check by class name
        if (source == null) return false
        val className = source.javaClass.simpleName
        return source is UnprocessedAudioSource || 
               className == "MicrophoneSource" ||
               className.contains("AudioRecord", ignoreCase = true)
    }

    override fun toString(): String {
        return "SmartMicrophoneSourceFactory()"
    }
}
