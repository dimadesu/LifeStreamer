package com.dimadesu.lifestreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
 */
class AudioPassthroughManager(private var config: AudioPassthroughConfig = AudioPassthroughConfig()) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var passthroughThread: Thread? = null
    @Volatile
    private var isRunning = false

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
            
            // Calculate buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = minBufferSize * 2 // Use double for safety
            
            // Create AudioRecord (input from microphone)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
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
