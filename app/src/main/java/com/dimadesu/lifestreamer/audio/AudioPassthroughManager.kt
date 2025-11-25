package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Audio configuration for passthrough
 * @param sampleRate Sample rate in Hz (typically 44100 or 48000)
 * @param channelConfig Audio channel configuration (CHANNEL_IN_MONO or CHANNEL_IN_STEREO)
 * @param audioFormat Audio format (typically ENCODING_PCM_16BIT)
 */
data class AudioPassthroughConfig(
    val sampleRate: Int = 44100,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
)

/**
 * Minimal audio passthrough manager that captures audio from microphone
 * and plays it through speakers/headphones with low latency.
 * Supports Bluetooth device preference for routing.
 */
class AudioPassthroughManager(
    private val context: Context,
    private var config: AudioPassthroughConfig = AudioPassthroughConfig()
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var passthroughThread: Thread? = null
    @Volatile
    private var isRunning = false
    private var preferredDevice: AudioDeviceInfo? = null

    /**
     * Set preferred audio device for recording (e.g., Bluetooth device).
     * Must be called before start().
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        Log.i(TAG, "setPreferredDevice called with: ${device?.productName ?: "null (built-in mic)"}")
        preferredDevice = device
    }

    fun start() {
        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "Audio passthrough already running")
                return
            }
            // Prevent residual thread from previous UI/service lifecycle
            if (passthroughThread != null && passthroughThread!!.isAlive) {
                Log.w(TAG, "Existing passthrough thread still alive - attempting to interrupt and join")
                try {
                    passthroughThread?.interrupt()
                    passthroughThread?.join(200)
                } catch (_: InterruptedException) {}
                passthroughThread = null
            }
            isRunning = true
        }

        try {
            // Audio configuration - from provided config
            val sampleRate = config.sampleRate
            val channelConfig = config.channelConfig
            val audioFormat = config.audioFormat
            
            // Capture current device preference at start (for consistent use throughout)
            val currentDevice = preferredDevice
            Log.i(TAG, "start() called - currentDevice: ${currentDevice?.productName ?: "null (built-in mic)"}")
            
            // Calculate buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = minBufferSize * 2 // Use double for safety
            
            // Create AudioRecord (input from microphone)
            // Use AudioRecord.Builder on API 23+ to support setPreferredDevice
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioFormatObj = AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                // Use VOICE_COMMUNICATION for BT (routes to SCO), MIC for built-in
                val audioSource = if (currentDevice != null) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.MIC
                }
                Log.i(TAG, "Using audio source: ${if (currentDevice != null) "VOICE_COMMUNICATION (BT)" else "MIC (built-in)"}")

                val builder = AudioRecord.Builder()
                    .setAudioFormat(audioFormatObj)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioSource(audioSource)

                val record = builder.build()
                
                // Set preferred device on AudioRecord instance (API 23+)
                // For BT: set BT device. For built-in: explicitly set built-in mic to override any cached BT routing
                try {
                    if (currentDevice != null) {
                        val success = record.setPreferredDevice(currentDevice)
                        Log.i(TAG, "Set preferred device on AudioRecord: ${currentDevice.productName}, success=$success")
                    } else {
                        // Find built-in mic and set it explicitly to force away from BT
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        val builtInMic = devices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        if (builtInMic != null) {
                            val success = record.setPreferredDevice(builtInMic)
                            Log.i(TAG, "Set preferred device on AudioRecord: built-in mic (${builtInMic.productName}), success=$success")
                        } else {
                            Log.w(TAG, "Could not find built-in mic device")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set preferred device: ${e.javaClass.simpleName}: ${e.message}")
                }

                record
            } else {
                // For older APIs, use MIC for built-in mic
                val audioSource = if (preferredDevice != null) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.MIC
                }
                AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }
            
            // Create AudioTrack (output to speakers/headphones)
            // This will apply similar audio processing and effects to what goes to stream,
            // giving a better preview of what viewers will actually hear.
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            // Convert input channel config to output channel mask
            val outputChannelMask = when (channelConfig) {
                AudioFormat.CHANNEL_IN_MONO -> AudioFormat.CHANNEL_OUT_MONO
                AudioFormat.CHANNEL_IN_STEREO -> AudioFormat.CHANNEL_OUT_STEREO
                else -> AudioFormat.CHANNEL_OUT_STEREO // Default to stereo
            }
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outputChannelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            
            // If BT device is preferred, try to set it as communication device
            // This forces Android to route audio through BT
            // If NOT using BT, explicitly clear communication device to ensure built-in mic is used
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    if (currentDevice != null) {
                        val method = audioManager::class.java.getMethod("setCommunicationDevice", AudioDeviceInfo::class.java)
                        method.invoke(audioManager, currentDevice)
                        Log.i(TAG, "AudioManager.setCommunicationDevice set for: ${currentDevice.productName}")
                    } else {
                        // Explicitly clear communication device to ensure built-in mic is used
                        try {
                            val clearMethod = audioManager::class.java.getMethod("clearCommunicationDevice")
                            clearMethod.invoke(audioManager)
                            Log.i(TAG, "AudioManager.clearCommunicationDevice called (ensuring built-in mic)")
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to clear communication device: ${e.message}")
                        }
                        // Also ensure SCO is stopped
                        try {
                            audioManager.stopBluetoothSco()
                            Log.i(TAG, "stopBluetoothSco called (ensuring built-in mic)")
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to stop BT SCO: ${e.message}")
                        }
                        // Set mode to NORMAL
                        audioManager.mode = AudioManager.MODE_NORMAL
                        Log.i(TAG, "Audio mode set to NORMAL")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to configure communication device: ${e.message}")
            }
            
            // Start recording and playback
            audioRecord?.startRecording()
            audioTrack?.play()
            
            isRunning = true
            
            // Start passthrough thread
            passthroughThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(bufferSize)

                try {
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (bytesRead > 0) {
                            audioTrack?.write(buffer, 0, bytesRead)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Passthrough thread exception: ${e.message}")
                } finally {
                    Log.i(TAG, "Passthrough thread exiting")
                }
            }, "AudioPassthroughThread")

            passthroughThread?.start()
            Log.i(TAG, "Audio passthrough started successfully (thread=${passthroughThread?.name})")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio passthrough: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        synchronized(this) {
            if (!isRunning && (passthroughThread == null || passthroughThread?.isAlive == false)) {
                Log.d(TAG, "stop() called but passthrough already stopped")
                return
            }
            isRunning = false
            try {
                passthroughThread?.interrupt()
            } catch (_: Exception) {}
        }

        // Try to join the thread with multiple short attempts to ensure termination
        try {
            var attempts = 0
            while (passthroughThread != null && passthroughThread?.isAlive == true && attempts < 10) {
                try {
                    passthroughThread?.join(100)
                } catch (_: InterruptedException) {
                    Log.w(TAG, "Interrupted while joining passthrough thread (attempt=$attempts)")
                }
                if (passthroughThread?.isAlive == true) {
                    try { passthroughThread?.interrupt() } catch (_: Exception) {}
                }
                attempts++
            }
            if (passthroughThread?.isAlive == true) {
                Log.w(TAG, "Passthrough thread still alive after attempts; it may be stuck")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error while joining passthrough thread: ${t.message}")
        }
        passthroughThread = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        audioTrack = null
        
        // Clear communication device if it was set
        try {
            if (preferredDevice != null) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    val method = audioManager::class.java.getMethod("clearCommunicationDevice")
                    method.invoke(audioManager)
                    Log.i(TAG, "AudioManager.clearCommunicationDevice called")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear communication device: ${e.message}")
        }
        
        Log.i(TAG, "Audio passthrough stopped")
    }

    /**
     * Update audio configuration.
     * If currently running, automatically restarts with the new configuration.
     */
    fun setConfig(newConfig: AudioPassthroughConfig) {
        val wasRunning = isRunning
        
        if (wasRunning) {
            Log.i(TAG, "Passthrough is running - restarting with new config")
            stop()
        }
        
        config = newConfig
        Log.i(TAG, "Audio config updated: sampleRate=${config.sampleRate}, channels=${if (config.channelConfig == AudioFormat.CHANNEL_IN_STEREO) "STEREO" else "MONO"}")
        
        if (wasRunning) {
            start()
        }
    }

    companion object {
        private const val TAG = "AudioPassthroughManager"
    }
}
