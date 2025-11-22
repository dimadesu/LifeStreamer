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
 * Minimal audio passthrough manager that captures audio from microphone
 * and plays it through speakers/headphones with low latency.
 */
class AudioPassthroughManager {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var passthroughThread: Thread? = null
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) {
            Log.w(TAG, "Audio passthrough already running")
            return
        }

        try {
            // Audio configuration
            val sampleRate = 44100 // Hz
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
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
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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
                
                while (isRunning) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        audioTrack?.write(buffer, 0, bytesRead)
                    }
                }
            }, "AudioPassthroughThread")
            
            passthroughThread?.start()
            Log.i(TAG, "Audio passthrough started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio passthrough: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        isRunning = false
        
        try {
            passthroughThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping passthrough thread")
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

    companion object {
        private const val TAG = "AudioPassthroughManager"
    }
}
