package com.dimadesu.lifestreamer.services

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
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
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.dimadesu.lifestreamer.services.utils.NotificationUtils
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * CameraStreamerService extending StreamerService for camera streaming
 */
class CameraStreamerService : StreamerService<ISingleStreamer>(
    streamerFactory = SingleStreamerFactory(
        withAudio = true, 
        withVideo = true 
        // Remove defaultRotation - let StreamPack detect it automatically and we'll update it dynamically
    ),
    notificationId = 1001,
    channelId = "camera_streaming_channel", 
    channelNameResourceId = R.string.streaming_channel_name,
    channelDescriptionResourceId = R.string.streaming_channel_description,
    notificationIconResourceId = R.drawable.ic_baseline_linked_camera_24
) {
    companion object {
        const val TAG = "CameraStreamerService"
        
        /**
         * Convert rotation constant to readable string for logging
         */
        private fun rotationToString(rotation: Int): String {
            return when (rotation) {
                Surface.ROTATION_0 -> "ROTATION_0 (Portrait)"
                Surface.ROTATION_90 -> "ROTATION_90 (Landscape Left)"
                Surface.ROTATION_180 -> "ROTATION_180 (Portrait Upside Down)"
                Surface.ROTATION_270 -> "ROTATION_270 (Landscape Right)"
                else -> "UNKNOWN ($rotation)"
            }
        }
    }

    private val _serviceReady = MutableStateFlow(false)
    
    // Current device rotation
    private var currentRotation: Int = Surface.ROTATION_0
    
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
        // Initialize audio manager and focus listener BEFORE calling super
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // Detect current device rotation
        detectCurrentRotation()
        
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
        
        // For Android 14+, we need to handle the permission issue
        // The base class will try to start with MEDIA_PROJECTION type
        // We'll catch any security exception and retry with limited types
        try {
            super.onCreate()
        } catch (e: SecurityException) {
            Log.w(TAG, "Base class failed with security exception, trying with limited service types", e)
            // Try to start foreground service with only camera and microphone
            try {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    onCreateNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                Log.i(TAG, "Foreground service started with camera and microphone types only")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start foreground service even with limited types", e2)
                throw e // Re-throw original exception
            }
        }
        
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
        
        // Boost process priority for foreground service - use more conservative priority for stability
        try {
            // Use URGENT_DISPLAY instead of URGENT_AUDIO for less aggressive scheduling
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            Log.i(TAG, "Process priority boosted to URGENT_DISPLAY for stable background audio")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to boost process priority", e)
        }
        
        // Request system to keep service alive
        try {
            startForeground(1001, onCreateNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                // Note: MEDIA_PROJECTION removed to avoid permission issues
            )
            Log.i(TAG, "Foreground service reinforced with required service types")
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
     * Detect the current device rotation using the window manager
     */
    private fun detectCurrentRotation() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
            
            currentRotation = rotation
            Log.i(TAG, "Detected device rotation: ${rotationToString(rotation)}")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect device rotation, keeping current: ${rotationToString(currentRotation)}", e)
        }
    }

    /**
     * Acquire wake lock to prevent audio silencing and ensure stable background recording
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            // Use PARTIAL_WAKE_LOCK for better compatibility across Android versions
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamPack::StableBackgroundAudioRecording"
            ).apply {
                acquire(30 * 60 * 1000L) // 30 minutes max - longer timeout for stability
                Log.i(TAG, "Wake lock acquired for stable background audio recording")
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
                // Use new API for Android 8+ with stable background recording configuration
                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) // Perfect for microphone streaming
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH) // Optimized for speech
                            // Remove FLAG_LOW_LATENCY to prevent crackling in background
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true) // Allow delayed focus for better compatibility
                    .setWillPauseWhenDucked(false) // Continue recording even when ducked
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                // Use deprecated API for older versions with voice stream for microphone content
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_VOICE_CALL, // Appropriate for microphone/voice streaming
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
