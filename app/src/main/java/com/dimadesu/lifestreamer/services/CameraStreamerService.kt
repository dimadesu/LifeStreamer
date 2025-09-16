package com.dimadesu.lifestreamer.services

import android.app.Notification
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import com.dimadesu.lifestreamer.ui.main.MainActivity
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.bitrate.AdaptiveSrtBitrateRegulatorController
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import kotlinx.coroutines.*
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter

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
        const val ACTION_STOP_STREAM = "com.dimadesu.lifestreamer.action.STOP_STREAM"
        const val ACTION_START_STREAM = "com.dimadesu.lifestreamer.action.START_STREAM"
        const val ACTION_TOGGLE_MUTE = "com.dimadesu.lifestreamer.action.TOGGLE_MUTE"
        const val ACTION_OPEN_FROM_NOTIFICATION = "com.dimadesu.lifestreamer.ACTION_OPEN_FROM_NOTIFICATION"

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
    // DataStore repository for reading configured endpoint and regulator settings
    private val storageRepository by lazy { DataStoreRepository(this, this.dataStore) }
    
    // Current device rotation
    private var currentRotation: Int = Surface.ROTATION_0
    
    // Wake lock to prevent audio silencing
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Create our own NotificationUtils instance for custom notifications
    private val customNotificationUtils: NotificationUtils by lazy {
        NotificationUtils(this, "camera_streaming_channel", 1001)
    }
    // Track streaming start time for uptime display
    private var streamingStartTime: Long? = null

    // Coroutine scope for periodic notification updates
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusUpdaterJob: Job? = null
    // SharedFlow for UI messages (notification start feedback)
    private val _notificationMessages = MutableSharedFlow<String>(replay = 1)
    val notificationMessages = _notificationMessages.asSharedFlow()
    
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

        // Register a small receiver for notification actions handled via intents to the service
        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_STREAM)
            addAction(ACTION_START_STREAM)
            addAction(ACTION_OPEN_FROM_NOTIFICATION)
            addAction(ACTION_TOGGLE_MUTE)
        }
        try {
            registerReceiver(notificationActionReceiver, filter)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register notification action receiver: ${t.message}")
        }
        
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

        // Start periodic notification updater to reflect runtime status
        startStatusUpdater()
    }

    override fun onDestroy() {
        // Stop periodic updater and unregister receiver
        stopStatusUpdater()
        try { unregisterReceiver(notificationActionReceiver) } catch (_: Exception) {}

        // Release wake lock if held
        try { releaseWakeLock() } catch (_: Exception) {}

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle notification action intents here too
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_STREAM -> {
                    Log.i(TAG, "Notification action: START_STREAM")
                    serviceScope.launch {
                        try {
                            startStreamFromConfiguredEndpoint()
                        } catch (e: Exception) {
                            Log.w(TAG, "Start from notification failed: ${e.message}")
                        }
                    }
                }
                ACTION_STOP_STREAM -> {
                    Log.i(TAG, "Notification action: STOP_STREAM")
                    // stop streaming but keep service alive
                    serviceScope.launch {
                        try {
                            streamer?.stopStream()
                        } catch (e: Exception) {
                            Log.w(TAG, "Stop from notification failed: ${e.message}")
                        }
                    }
                }
                ACTION_TOGGLE_MUTE -> {
                    Log.i(TAG, "Notification action: TOGGLE_MUTE")
                    // Toggle mute on streamer if available
                    serviceScope.launch {
                        try {
                            val audio = (streamer as? IWithAudioSource)?.audioInput
                            val current = audio?.isMuted ?: false
                            if (audio != null) audio.isMuted = !current
                            customNotificationUtils.notify(onOpenNotification() ?: onCreateNotification())
                        } catch (e: Exception) {
                            Log.w(TAG, "Toggle mute failed: ${e.message}")
                        }
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_STOP_STREAM -> {
                    Log.i(TAG, "Notification receiver: STOP_STREAM")
                    serviceScope.launch {
                        try {
                            streamer?.stopStream()
                            // Refresh notification to show Start action
                            customNotificationUtils.notify(onCloseNotification() ?: onCreateNotification())
                        } catch (e: Exception) {
                            Log.w(TAG, "Stop from notification receiver failed: ${e.message}")
                        }
                    }
                }
                ACTION_START_STREAM -> {
                    Log.i(TAG, "Notification receiver: START_STREAM")
                    serviceScope.launch {
                        try {
                            val isStreaming = streamer?.isStreamingFlow?.value ?: false
                            if (!isStreaming) {
                                // Start using configured endpoint from DataStore
                                startStreamFromConfiguredEndpoint()
                                // Refresh notification to show Stop action
                                customNotificationUtils.notify(onOpenNotification() ?: onCreateNotification())
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Start from notification receiver failed: ${e.message}")
                        }
                    }
                }
                ACTION_TOGGLE_MUTE -> {
                    Log.i(TAG, "Notification receiver: TOGGLE_MUTE")
                    serviceScope.launch {
                        try {
                            val audio = (streamer as? IWithAudioSource)?.audioInput
                            val current = audio?.isMuted ?: false
                            if (audio != null) audio.isMuted = !current
                            // Refresh notification to reflect mute state
                            customNotificationUtils.notify(onOpenNotification() ?: onCreateNotification())
                        } catch (e: Exception) {
                            Log.w(TAG, "Toggle mute from receiver failed: ${e.message}")
                        }
                    }
                }
                ACTION_OPEN_FROM_NOTIFICATION -> {
                    Log.i(TAG, "Notification receiver: OPEN_FROM_NOTIFICATION")
                    // Launch MainActivity (bring to front if exists)
                    try {
                        val activityOpenIntent = Intent(this@CameraStreamerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            setAction(ACTION_OPEN_FROM_NOTIFICATION)
                        }
                        startActivity(activityOpenIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to open MainActivity from notification: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onStreamingStop() {
        // Release wake lock when streaming stops
        releaseWakeLock()
        // clear start time
        streamingStartTime = null
        
        // Override the base class behavior to NOT stop the service when streaming stops
        // This allows the service to remain running for quick restart of streaming
        Log.i(TAG, "Streaming stopped but service remains active for background operation")
        
        // Update notification to show stopped state
        onCloseNotification()?.let { notification ->
            customNotificationUtils.notify(notification)
        }
        // Intentionally NOT calling stopSelf() here - let the service stay alive
    }

    /**
     * Start a coroutine that periodically updates the notification with streaming status
     */
    private fun startStatusUpdater() {
        statusUpdaterJob?.cancel()
        statusUpdaterJob = serviceScope.launch {
            while (isActive) {
                try {
                    val title = getString(R.string.service_notification_title)
                    val content = if (streamer?.isStreamingFlow?.value == true) {
                        // show streaming and uptime (computed from streamingStartTime)
                        val uptime = System.currentTimeMillis() - (streamingStartTime ?: System.currentTimeMillis())
                        getString(R.string.service_notification_text_streaming)
                    } else {
                        getString(R.string.service_notification_text_stopped)
                    }

                    // Build notification with actions using NotificationCompat.Builder directly
                    // Use explicit service intent for Start so the service is started if not running
                    // Use broadcast for Start action so the registered receiver handles it reliably
                    val startIntent = Intent(ACTION_START_STREAM).apply { setPackage(packageName) }
                    val startPending = PendingIntent.getBroadcast(this@CameraStreamerService, 0, startIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

                    val stopIntent = Intent(ACTION_STOP_STREAM).apply { setPackage(packageName) }
                    val stopPending = PendingIntent.getBroadcast(this@CameraStreamerService, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

                    val muteIntent = Intent(ACTION_TOGGLE_MUTE).apply { setPackage(packageName) }
                    val mutePending = PendingIntent.getBroadcast(this@CameraStreamerService, 2, muteIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

                    // Create tap intent so notification opens the app's main activity
                    val tapOpenIntent = Intent(this@CameraStreamerService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        setAction("com.dimadesu.lifestreamer.ACTION_OPEN_FROM_NOTIFICATION")
                    }
                    val openPending = PendingIntent.getActivity(this@CameraStreamerService, 3, tapOpenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

                    val builder = NotificationCompat.Builder(this@CameraStreamerService, "camera_streaming_channel").apply {
                        setContentIntent(openPending)
                        setSmallIcon(notificationIconResourceId)
                        setContentTitle(title)
                        setContentText(content)
                        setOngoing(true)
                        // Show Start when not streaming, Stop when streaming
                        if (streamer?.isStreamingFlow?.value == true) {
                            addAction(notificationIconResourceId, "Stop", stopPending)
                        } else {
                            addAction(notificationIconResourceId, "Start", startPending)
                        }

                        // Determine mute/unmute label based on current audio state
                        val audio = (streamer as? IWithAudioSource)?.audioInput
                        val isMuted = audio?.isMuted ?: false
                        val muteLabel = if (isMuted) getString(R.string.service_notification_action_unmute) else getString(R.string.service_notification_action_mute)
                        addAction(notificationIconResourceId, muteLabel, mutePending)
                    }

                    customNotificationUtils.notify(builder.build())
                } catch (e: Exception) {
                    Log.w(TAG, "Status updater failed: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    private fun stopStatusUpdater() {
        statusUpdaterJob?.cancel()
        statusUpdaterJob = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStreamingStart() {
        // record start time for uptime display
        streamingStartTime = System.currentTimeMillis()
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
     * Start streaming using the endpoint configured in DataStore.
     * Mirrors the logic from PreviewViewModel.startStream(): open with timeout and attach regulator if needed.
     */
    private suspend fun startStreamFromConfiguredEndpoint() {
        try {
            // Wait a short time for streamer to be available (service may be starting)
            val currentStreamer = withTimeoutOrNull(5000) {
                // Poll streamer until non-null or timeout
                while (streamer == null) {
                    delay(200)
                }
                streamer
            } ?: run {
                Log.w(TAG, "startStreamFromConfiguredEndpoint: streamer not available after waiting")
                // Update notification to show error so user gets feedback
                customNotificationUtils.notify(onErrorNotification(Throwable("Streamer not available")) ?: onCreateNotification())
                // Broadcast result for UI
                sendNotificationStartResult("Streamer not available")
                return
            }

            // Read configured endpoint descriptor
            val descriptor = try {
                storageRepository.endpointDescriptorFlow.first()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read endpoint descriptor from storage: ${e.message}")
                customNotificationUtils.notify(onErrorNotification(Throwable("No endpoint configured")) ?: onCreateNotification())
                sendNotificationStartResult("No endpoint configured")
                return
            }

            Log.i(TAG, "startStreamFromConfiguredEndpoint: opening descriptor $descriptor")

            try {
                withTimeout(10000) { // 10s open timeout
                    currentStreamer.open(descriptor)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open endpoint descriptor: ${e.message}")
                customNotificationUtils.notify(onErrorNotification(Throwable("Open failed: ${e.message}")) ?: onCreateNotification())
                sendNotificationStartResult("Open failed: ${e.message}")
                return
            }

            try {
                currentStreamer.startStream()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start stream after open: ${e.message}")
                customNotificationUtils.notify(onErrorNotification(Throwable("Start failed: ${e.message}")) ?: onCreateNotification())
                sendNotificationStartResult("Start failed: ${e.message}")
                return
            }

            // If SRT sink, possibly attach bitrate regulator controller based on stored config
            if (descriptor.type.sinkType == MediaSinkType.SRT) {
                val bitrateRegulatorConfig = try {
                    storageRepository.bitrateRegulatorConfigFlow.first()
                } catch (e: Exception) {
                    null
                }
                if (bitrateRegulatorConfig != null) {
                    try {
                        val mode = try { storageRepository.regulatorModeFlow.first() } catch (_: Exception) { com.dimadesu.lifestreamer.bitrate.RegulatorMode.MOBLIN_FAST }
                        currentStreamer.addBitrateRegulatorController(
                            AdaptiveSrtBitrateRegulatorController.Factory(
                                bitrateRegulatorConfig = bitrateRegulatorConfig,
                                mode = mode
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to attach bitrate regulator: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "startStreamFromConfiguredEndpoint: stream started successfully")
            sendNotificationStartResult("Stream started")
        } catch (e: Exception) {
            Log.w(TAG, "startStreamFromConfiguredEndpoint error: ${e.message}")
        }
    }

    private fun sendNotificationStartResult(message: String) {
        try {
            serviceScope.launch { _notificationMessages.emit(message) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to emit notification start result: ${t.message}")
        }
    }
    
    /**
     * Custom binder that provides access to both the streamer and the service
     */
    inner class CameraStreamerServiceBinder : Binder() {
        fun getService(): CameraStreamerService = this@CameraStreamerService
        val streamer: ISingleStreamer get() = this@CameraStreamerService.streamer
        // Expose message flow to bound clients
        fun notificationMessages() = this@CameraStreamerService.notificationMessages
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
