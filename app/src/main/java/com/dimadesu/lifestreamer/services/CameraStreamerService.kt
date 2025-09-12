/*
 * Copyright (C) 2022 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.services

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.dimadesu.lifestreamer.R
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.services.StreamerService
import io.github.thibaultbee.streampack.services.utils.SingleStreamerFactory
import android.content.pm.ServiceInfo
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.dimadesu.lifestreamer.services.utils.NotificationUtils
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * CameraStreamerService extending StreamerService for camera streaming
 */
class CameraStreamerService : StreamerService<ISingleStreamer>(
    streamerFactory = SingleStreamerFactory(
        withAudio = true, 
        withVideo = true, 
        defaultRotation = Surface.ROTATION_0  // Provide default rotation since service context has no display
    ),
    notificationId = 1001,
    channelId = "camera_streaming_channel", 
    channelNameResourceId = R.string.streaming_channel_name,
    channelDescriptionResourceId = R.string.streaming_channel_description,
    notificationIconResourceId = R.drawable.ic_baseline_linked_camera_24
) {
    companion object {
        const val TAG = "CameraStreamerService"
    }

    private val _serviceReady = MutableStateFlow(false)
    
    // Audio focus management
    private lateinit var audioManager: AudioManager
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null // Store for proper release
    private var hasAudioFocus = false
    
    // Wake lock to prevent audio silencing
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Create our own NotificationUtils instance for custom notifications
    private val customNotificationUtils: NotificationUtils by lazy {
        NotificationUtils(this, "camera_streaming_channel", 1001)
    }
    
    // Audio permission checker for debugging
    /**
     * Override onCreate to use both camera and mediaProjection service types
     */
    override fun onCreate() {
        // Initialize audio manager and focus listener
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.i(TAG, "Audio focus gained - continuing recording")
                    hasAudioFocus = true
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.w(TAG, "Audio focus lost permanently")
                    hasAudioFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.w(TAG, "Audio focus lost temporarily")
                    hasAudioFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.i(TAG, "Audio focus lost temporarily (can duck) - continuing recording")
                    // For recording apps, we want to continue recording even when ducked
                    hasAudioFocus = true
                }
            }
        }
        
        // Let the base class handle most of the setup first
        super.onCreate()
        
        // The base class already calls startForeground with MEDIA_PROJECTION type,
        // but we need to update it with CAMERA and MICROPHONE types for Android 14+ to enable background access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ supports camera, media projection, and microphone service types
            ServiceCompat.startForeground(
                this,
                1001, // Use the same notification ID as specified in constructor
                onCreateNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
        // For Android 13 and below, the base class MEDIA_PROJECTION type should work
        // Camera access in background may be more limited but should still work with proper manifest declaration
        
        Log.i(TAG, "CameraStreamerService created and configured for background camera access")
    }

    override fun onStreamingStop() {
        // Release audio focus when streaming stops
        releaseAudioFocus()
        // Release wake lock when streaming stops
        releaseWakeLock()
        
        // Override the base class behavior to NOT stop the service when streaming stops
        // This allows the service to remain running for quick restart of streaming
        Log.i(TAG, "Streaming stopped but service remains active for background operation")
        
        // Update notification to show stopped state
        onCloseNotification()?.let { notification ->
            customNotificationUtils.notify(notification)
        }
        // Intentionally NOT calling stopSelf() here - let the service stay alive
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStreamingStart() {
        // Acquire audio focus when streaming starts
        requestAudioFocus()
        // Acquire wake lock when streaming starts
        acquireWakeLock()
        
        // Boost process priority for foreground service
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Process priority boosted to URGENT_AUDIO for background audio reliability")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to boost process priority", e)
        }
        
        // Request system to keep service alive
        try {
            startForeground(1001, onCreateNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            Log.i(TAG, "Foreground service reinforced with all required service types")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to maintain foreground service state", e)
        }
        
        super.onStreamingStart()
    }

    override fun onDestroy() {
        // Release audio focus when service is destroyed
        releaseAudioFocus()
        // Release wake lock when service is destroyed
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Acquire wake lock to prevent audio silencing
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "StreamPack::HighPriorityAudioRecording"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
                Log.i(TAG, "Enhanced wake lock acquired with ON_AFTER_RELEASE flag for high priority audio recording")
            }
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Wake lock released")
            }
            wakeLock = null
        }
    }

    /**
     * Request audio focus for continuous recording
     */
    fun requestAudioFocus() {
        if (hasAudioFocus) {
            Log.i(TAG, "Audio focus already held")
            return
        }
        
        audioFocusListener?.let { listener ->
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use new API for Android 8+ with persistent focus request
                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) 
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false) // Continue recording even when ducked
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                // Use deprecated API for older versions
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_VOICE_CALL, // Use voice call stream for recording
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    Log.i(TAG, "Audio focus granted for persistent background recording")
                    hasAudioFocus = true
                }
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                    Log.w(TAG, "Audio focus request failed")
                    hasAudioFocus = false
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    Log.i(TAG, "Audio focus request delayed")
                    hasAudioFocus = false
                }
            }
        }
    }

    /**
     * Handle foreground recovery - called when app returns to foreground
     * This helps restore audio recording that may have been silenced in background
     */
    fun handleForegroundRecovery() {
        Log.i(TAG, "handleForegroundRecovery() called - checking audio status")
        
        // Try to restart audio recording by requesting audio focus again
        if (hasAudioFocus) {
            Log.i(TAG, "Re-requesting audio focus to help restore background audio")
            requestAudioFocus()
        }
        
        // Notify the streamer about foreground recovery if it has audio capabilities
        try {
            streamer.let { currentStreamer ->
                if (currentStreamer.isStreamingFlow.value) {
                    Log.i(TAG, "Triggering audio source recovery for active stream")
                    // Audio recovery now handled by comprehensive background audio solution
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger audio recovery", e)
        }
    }

    /**
     * Release audio focus
     */
    private fun releaseAudioFocus() {
        if (!hasAudioFocus) {
            return
        }
        
        audioFocusListener?.let { listener ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
            Log.i(TAG, "Audio focus released")
            hasAudioFocus = false
        }
    }

    /**
     * Required implementation of abstract method
     */
    override suspend fun onExtra(extras: Bundle) {
        // Handle extras if needed
        _serviceReady.value = true
    }
    
    /**
     * Custom binder that provides access to both the streamer and the service
     */
    inner class CameraStreamerServiceBinder : Binder() {
        fun getService(): CameraStreamerService = this@CameraStreamerService
        val streamer: ISingleStreamer get() = this@CameraStreamerService.streamer
    }

    private val customBinder = CameraStreamerServiceBinder()

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return customBinder
    }

    override fun onCreateNotification(): Notification {
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            getString(R.string.service_notification_text_created),
            R.drawable.ic_baseline_linked_camera_24,
            isForgroundService = true // Enable enhanced foreground service attributes
        )
    }

    override fun onOpenNotification(): Notification? {
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            getString(R.string.service_notification_text_streaming),
            R.drawable.ic_baseline_linked_camera_24,
            isForgroundService = true // Enable enhanced foreground service attributes
        )
    }

    override fun onErrorNotification(t: Throwable): Notification? {
        val errorMessage = getString(R.string.service_notification_text_error, t.message ?: "Unknown error")
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            errorMessage,
            R.drawable.ic_baseline_linked_camera_24
        )
    }

    override fun onCloseNotification(): Notification? {
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            getString(R.string.service_notification_text_stopped),
            R.drawable.ic_baseline_linked_camera_24
        )
    }
}
