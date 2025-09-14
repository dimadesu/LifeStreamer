package com.dimadesu.lifestreamer.services

import android.app.Notification
import android.content.Context
import android.content.Intent
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
        // Initialize power manager and other services
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Detect current device rotation
        detectCurrentRotation()

        // Ensure our app-level notification channel (silent) is created before
        // the base StreamerService posts the initial foreground notification.
        // This prevents the system from using an existing channel with sound.
        try {
            customNotificationUtils.createNotificationChannel(
                channelNameResourceId,
                channelDescriptionResourceId
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create custom notification channel: ${t.message}")
        }

        // Let the base class handle the rest of the setup (including startForeground)
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
            // Use the "open/streaming" notification when starting foreground so the
            // notification immediately reflects that streaming is in progress.
            // Fall back to the create notification if open notification is not provided.
            startForeground(1001, onOpenNotification() ?: onCreateNotification(),
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
     * Handle foreground recovery - called when app returns to foreground
     * This helps restore audio recording that may have been silenced in background
     */
    fun handleForegroundRecovery() {
        // No-op. Audio focus logic removed; keep method for compatibility.
        Log.i(TAG, "handleForegroundRecovery() called - audio focus logic removed")
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
