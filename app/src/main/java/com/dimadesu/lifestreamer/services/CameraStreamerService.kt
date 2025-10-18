package com.dimadesu.lifestreamer.services

import android.app.Notification
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
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
import com.dimadesu.lifestreamer.models.StreamStatus
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import kotlinx.coroutines.*
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoRotation
import io.github.thibaultbee.streampack.core.streamers.orientation.IRotationProvider
import io.github.thibaultbee.streampack.core.streamers.orientation.SensorRotationProvider
import kotlinx.coroutines.flow.first

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
        const val ACTION_EXIT_APP = "com.dimadesu.lifestreamer.action.EXIT_APP"
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
    
    // Stream rotation locking: when streaming starts, lock to current rotation
    // and ignore sensor changes until streaming stops
    private var lockedStreamRotation: Int? = null
    
    // Save the initial streaming orientation to restore it during reconnection
    // This persists through disconnections and reconnections
    private var savedStreamingOrientation: Int? = null

    // Local rotation provider (we register our own to avoid calling StreamerService.onCreate)
    private var localRotationProvider: IRotationProvider? = null
    private var localRotationListener: IRotationProvider.Listener? = null
    
    // Wake lock to prevent audio silencing
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Network wake lock to prevent network I/O throttling during background streaming
    // Especially important for SRT streaming
    private var networkWakeLock: PowerManager.WakeLock? = null
    
    // Create our own NotificationUtils instance for custom notifications
    private val customNotificationUtils: NotificationUtils by lazy {
        NotificationUtils(this, "camera_streaming_channel", 1001)
    }
    // Track streaming start time for uptime display
    private var streamingStartTime: Long? = null
    // Uptime flow (milliseconds since streamingStartTime) for UI consumption
    private val _uptimeFlow = MutableStateFlow<String?>(null)
    val uptimeFlow = _uptimeFlow.asStateFlow()

    // Coroutine scope for periodic notification updates
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusUpdaterJob: Job? = null
    // Cache last notification state to avoid re-posting identical notifications
    private var lastNotificationKey: String? = null
    // Critical error flow for the UI to show dialogs (non-transient errors)
    // Use a replay of 0 so only fresh errors are observed by listeners
    private val _criticalErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 0)
    val criticalErrors = _criticalErrors.asSharedFlow()
    // Current outgoing video bitrate in bits per second (nullable when unknown)
    private val _currentBitrateFlow = MutableStateFlow<Int?>(null)
    val currentBitrateFlow = _currentBitrateFlow.asStateFlow()
    // Current audio mute state exposed as a StateFlow for UI synchronization
    private val _isMutedFlow = MutableStateFlow(false)
    val isMutedFlow = _isMutedFlow.asStateFlow()
    // Service-wide streaming status for UI synchronization (shared enum)
    private val _serviceStreamStatus = MutableStateFlow(StreamStatus.NOT_STREAMING)
    val serviceStreamStatus = _serviceStreamStatus.asStateFlow()
    // Cached PendingIntents for notification actions to avoid recreating/cancelling them
    private lateinit var startPendingIntent: PendingIntent
    private lateinit var stopPendingIntent: PendingIntent
    private lateinit var mutePendingIntent: PendingIntent
    private lateinit var exitPendingIntent: PendingIntent
    private lateinit var openPendingIntent: PendingIntent
    
    // Audio permission checker for debugging
    /**
     * Override onCreate to use both camera and mediaProjection service types
     */
    override fun onCreate() {
        // We intentionally avoid calling super.onCreate() here because the base
        // StreamerService starts a foreground service with the mediaProjection type
        // by default. We want to keep the change local to this app and ensure the
        // camera service only requests CAMERA|MICROPHONE types.

        // onCreate: perform lightweight initialization and promote service to foreground early

        // Initialize power manager and other services
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Detect current device rotation
        detectCurrentRotation()

        // Ensure our app-level notification channel (silent) is created before
        // starting foreground notification. This prevents the system from using
        // an existing channel with sound.
        try {
            customNotificationUtils.createNotificationChannel(
                channelNameResourceId,
                channelDescriptionResourceId
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create custom notification channel: ${t.message}")
        }

        // Call startForeground as early as possible. Use a conservative try/catch
        // and fallbacks so we always call startForeground quickly after
        // startForegroundService() to avoid ANRs.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    onCreateNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                // For older versions, start foreground normally using Service API
                // (the three-arg ServiceCompat overload resolution can be ambiguous
                // with newer API shims). We're in a Service subclass so call directly.
                startForeground(1001, onCreateNotification())
            }
        } catch (t: Throwable) {
            // Fallback: try a minimal startForeground() using a simple notification
            try {
                val minimal = NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(notificationIconResourceId)
                    .setContentTitle(getString(R.string.service_notification_title))
                    .setContentText(getString(R.string.service_notification_text_created))
                    .setOnlyAlertOnce(true)
                    .setSound(null)
                    .build()
                startForeground(1001, minimal)
            } catch (_: Throwable) {
                // If fallback fails, there's not much we can do; service will log exceptions
            }
        }

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
        Log.i(TAG, "CameraStreamerService created and configured for background camera access")

        // Prepare cached PendingIntents for notification actions so updates don't
        // cancel/recreate them which sometimes causes the UI to render a disabled state.
        initNotificationPendingIntents()

        // Register rotation provider off the main thread to avoid blocking onCreate()
        serviceScope.launch(Dispatchers.Default) {
            try {
                val rotationProvider = SensorRotationProvider(this@CameraStreamerService)
                val listener = object : IRotationProvider.Listener {
                    override fun onOrientationChanged(rotation: Int) {
                        // If stream rotation is locked (during streaming), ignore sensor changes
                        if (lockedStreamRotation != null) {
                            Log.i(TAG, "SENSOR: Ignoring rotation change to ${rotationToString(rotation)} - LOCKED to ${rotationToString(lockedStreamRotation!!)}")
                            return
                        }
                        Log.i(TAG, "SENSOR: Rotation changed to ${rotationToString(rotation)} - NOT LOCKED, applying")
                        
                        // When not streaming, update rotation normally
                        try {
                            serviceScope.launch {
                                try {
                                    (streamer as? IWithVideoRotation)?.setTargetRotation(rotation)
                                    currentRotation = rotation
                                    Log.d(TAG, "Rotation updated to ${rotationToString(rotation)}")
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
                rotationProvider.addListener(listener)
                localRotationProvider = rotationProvider
                localRotationListener = listener
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register rotation provider: ${t.message}")
            }
        }

        // Start periodic notification updater to reflect runtime status
        startStatusUpdater()
    }

    private fun initNotificationPendingIntents() {
        // Use stable request codes and UPDATE_CURRENT to keep PendingIntents valid
        val startIntent = Intent(ACTION_START_STREAM).apply { setPackage(packageName) }
        startPendingIntent = PendingIntent.getBroadcast(this@CameraStreamerService, 0, startIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(ACTION_STOP_STREAM).apply { setPackage(packageName) }
        stopPendingIntent = PendingIntent.getBroadcast(this@CameraStreamerService, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val muteIntent = Intent(ACTION_TOGGLE_MUTE).apply { setPackage(packageName) }
        mutePendingIntent = PendingIntent.getBroadcast(this@CameraStreamerService, 2, muteIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val exitActivityIntent = Intent(this@CameraStreamerService, MainActivity::class.java).apply {
            setAction(ACTION_EXIT_APP)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        exitPendingIntent = PendingIntent.getActivity(
            this@CameraStreamerService,
            4,
            exitActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tapOpenIntent = Intent(this@CameraStreamerService, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            setAction(ACTION_OPEN_FROM_NOTIFICATION)
        }
        openPendingIntent = PendingIntent.getActivity(this@CameraStreamerService, 3, tapOpenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        // Stop periodic updater and unregister receiver
        stopStatusUpdater()
        try { unregisterReceiver(notificationActionReceiver) } catch (_: Exception) {}

        // Clean up local rotation provider if we registered one
        try {
            // Remove listener only if we have one registered
            localRotationListener?.let { listener ->
                localRotationProvider?.removeListener(listener)
            }
            localRotationProvider = null
            localRotationListener = null
        } catch (_: Exception) {}

        // Release wake locks if held
        try { releaseWakeLock() } catch (_: Exception) {}
        try { releaseNetworkWakeLock() } catch (_: Exception) {}

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle notification action intents here too
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_STREAM -> {
                    Log.i(TAG, "Notification action: START_STREAM")
                    serviceScope.launch(Dispatchers.Default) {
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
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            streamer?.stopStream()
                        } catch (e: Exception) {
                            Log.w(TAG, "Stop from notification failed: ${e.message}")
                        }
                    }
                }
                ACTION_TOGGLE_MUTE -> {
                    Log.i(TAG, "Notification action: TOGGLE_MUTE")
                    // Toggle mute on streamer if available - run off the main thread
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            // Delegate mute toggle to the centralized service API to avoid races
                            val current = isCurrentlyMuted()
                            setMuted(!current)
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
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            streamer?.stopStream()
                            // Refresh notification to show Start action on main thread
                            val notification = onCloseNotification() ?: onCreateNotification()
                            withContext(Dispatchers.Main) { customNotificationUtils.notify(notification) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Stop from notification receiver failed: ${e.message}")
                        }
                    }
                }
                ACTION_START_STREAM -> {
                    Log.i(TAG, "Notification receiver: START_STREAM")
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            val isStreaming = streamer?.isStreamingFlow?.value ?: false
                            if (!isStreaming) {
                                // Start using configured endpoint from DataStore
                                startStreamFromConfiguredEndpoint()
                                // Refresh notification to show Stop action
                                val notification = onOpenNotification() ?: onCreateNotification()
                                withContext(Dispatchers.Main) { customNotificationUtils.notify(notification) }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Start from notification receiver failed: ${e.message}")
                        }
                    }
                }
                ACTION_TOGGLE_MUTE -> {
                    Log.i(TAG, "Notification receiver: TOGGLE_MUTE")
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            // Delegate mute toggle to centralized service API to avoid races
                            val current = isCurrentlyMuted()
                            setMuted(!current)
                        } catch (e: Exception) {
                            Log.w(TAG, "Toggle mute from receiver failed: ${e.message}")
                        }
                    }
                }
                // EXIT handled by Activity via Activity PendingIntent; do not handle here.
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
        // Don't automatically unlock stream rotation here - it will be unlocked explicitly
        // when the stream truly stops (not during reconnection cleanup)
        // This prevents rotation changes from being accepted during reconnection
        Log.i(TAG, "Streaming stopped - rotation lock maintained for potential reconnection")
        
        // Release wake locks when streaming stops
        releaseWakeLock()
        releaseNetworkWakeLock()
        // clear start time
        streamingStartTime = null
        // Clear uptime so UI hides the uptime display immediately
        try { _uptimeFlow.tryEmit(null) } catch (_: Throwable) {}
        
        // Override the base class behavior to NOT stop the service when streaming stops
        // This allows the service to remain running for quick restart of streaming
        Log.i(TAG, "Streaming stopped but service remains active for background operation")
        
        // Update notification to show stopped state
        onCloseNotification()?.let { notification ->
            customNotificationUtils.notify(notification)
        }
        // Clear bitrate flow so UI shows cleared state immediately
        try { _currentBitrateFlow.tryEmit(null) } catch (_: Throwable) {}
        // Update service-side status
        try { _serviceStreamStatus.tryEmit(StreamStatus.NOT_STREAMING) } catch (_: Throwable) {}
        // Intentionally NOT calling stopSelf() here - let the service stay alive
    }
    
    /**
     * Explicitly unlock stream rotation when streaming truly stops (not during reconnection).
     * This should be called by the ViewModel when the stream is fully stopped.
     */
    fun unlockStreamRotation() {
        lockedStreamRotation = null
        savedStreamingOrientation = null
        Log.i(TAG, "Stream rotation explicitly unlocked - cleared saved orientation, will follow sensor again")
    }
    
    /**
     * Explicitly lock stream rotation to a specific rotation.
     * This should be called when the UI locks orientation to ensure stream matches UI.
     * @param rotation The rotation value (Surface.ROTATION_0, ROTATION_90, etc.)
     */
    fun lockStreamRotation(rotation: Int) {
        val wasLocked = lockedStreamRotation
        lockedStreamRotation = rotation
        savedStreamingOrientation = rotation  // Save for reconnection
        currentRotation = rotation
        Log.i(TAG, "lockStreamRotation: Setting lock from ${if (wasLocked != null) rotationToString(wasLocked) else "null"} to ${rotationToString(rotation)}, saved for reconnection")
    }
    
    /**
     * Get the saved streaming orientation for reconnection.
     * Returns null if no orientation has been saved.
     */
    fun getSavedStreamingOrientation(): Int? {
        return savedStreamingOrientation
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
                    // Prefer the streamer's immediate state when possible to avoid stale service status
                    val serviceStatus = getEffectiveServiceStatus()
                    // Compute the canonical status label once and reuse it for both
                    // the notification content and the small status label.
                    val statusLabel = when (serviceStatus) {
                        StreamStatus.STREAMING -> {
                            // We might want to compute uptime here in the future; keep the
                            // timestamp available if needed. For now the label is the same.
                            val uptimeMillis = System.currentTimeMillis() - (streamingStartTime ?: System.currentTimeMillis())
                            // Emit formatted uptime for UI
                            try { _uptimeFlow.emit(formatUptime(uptimeMillis)) } catch (_: Throwable) {}
                            getString(R.string.status_streaming)
                        }
                        StreamStatus.STARTING -> getString(R.string.status_starting)
                        StreamStatus.CONNECTING -> getString(R.string.status_connecting)
                        StreamStatus.ERROR -> getString(R.string.status_error)
                        else -> getString(R.string.status_not_streaming)
                    }

                    // Use the same label as the base content for notifications to avoid
                    // duplicated lookup and ensure canonical text across UI + notifications.
                    val content = statusLabel

                    // Build notification with actions using NotificationCompat.Builder directly
                    // Use explicit service intent for Start so the service is started if not running
                    // Use broadcast for Start action so the registered receiver handles it reliably
                    // Use cached PendingIntents to avoid recreating actions every loop
                    val startPending = startPendingIntent
                    val stopPending = stopPendingIntent
                    val mutePending = mutePendingIntent
                    val exitPending = exitPendingIntent
                    val openPending = openPendingIntent

                    // Determine mute/unmute state before building the notification key
                    val isMuted = isCurrentlyMuted()

                    // Only read/emit current encoder bitrate when streaming; otherwise clear it
                    val isStreamingNow = serviceStatus == StreamStatus.STREAMING
                    val videoBitrate = if (isStreamingNow) {
                        (streamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder?.bitrate
                    } else null
                    // Emit bitrate (or null when not streaming) to flow for UI consumers
                    try { _currentBitrateFlow.emit(videoBitrate) } catch (_: Throwable) {}

                    val bitrateText = videoBitrate?.let { b ->
                        if (b >= 1_000_000) String.format("%.2f Mbps", b / 1_000_000.0) else String.format("%d kb/s", b / 1000)
                    } ?: "-"

                    // Reuse the already computed statusLabel for the notification key
                    // Build notification and key using canonical helper so it's consistent
                    // Build notification; include uptime when streaming
                    val (notification, notificationKey) = buildNotificationForStatus(serviceStatus)


                    // Skip rebuilding the notification if nothing relevant changed.
                    if (notificationKey == lastNotificationKey) {
                        delay(2000)
                        continue
                    }

                    customNotificationUtils.notify(notification)
                    lastNotificationKey = notificationKey
                } catch (e: Exception) {
                    Log.w(TAG, "Status updater failed: ${e.message}")
                }
                // Use a short sleep between checks; notification updates are already
                // gated above to happen only when needed.
                delay(2000)
            }
        }
    }

    private fun stopStatusUpdater() {
        statusUpdaterJob?.cancel()
        statusUpdaterJob = null
    }

    // Post a notification that is appropriate for the current service status
    private suspend fun notifyForCurrentState() {
        try {
            val status = getEffectiveServiceStatus()
            val (notification, key) = buildNotificationForStatus(status)
            // Update cached key so the periodic updater won't overwrite immediately
            lastNotificationKey = key
            withContext(Dispatchers.Main) { customNotificationUtils.notify(notification) }
        } catch (e: Exception) {
            Log.w(TAG, "notifyForCurrentState failed: ${e.message}")
        }
    }

    // Build the notification and its key in the same way as the status updater
    private fun buildNotificationForStatus(status: StreamStatus): Pair<Notification, String> {
        val title = getString(R.string.service_notification_title)

        // Compute canonical status label
        val statusLabel = when (status) {
            StreamStatus.STREAMING -> getString(R.string.status_streaming)
            StreamStatus.STARTING -> getString(R.string.status_starting)
            StreamStatus.CONNECTING -> getString(R.string.status_connecting)
            StreamStatus.ERROR -> getString(R.string.status_error)
            else -> getString(R.string.status_not_streaming)
        }

        // When streaming, append uptime to the content (e.g., "Live • 00:01:23")
        val content = if (status == StreamStatus.STREAMING) {
            val uptimeMillis = System.currentTimeMillis() - (streamingStartTime ?: System.currentTimeMillis())
            val uptime = try { formatUptime(uptimeMillis) } catch (_: Throwable) { "" }
            if (uptime.isNotEmpty()) "$statusLabel • $uptime" else statusLabel
        } else statusLabel

        // Determine mute/unmute label and pending intents
        val muteLabel = currentMuteLabel()
        val showStart = status != StreamStatus.STREAMING
        val showStop = status == StreamStatus.STREAMING

        // Only read bitrate when streaming
        val videoBitrate = if (status == StreamStatus.STREAMING) {
            (streamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder?.bitrate
        } else null

        val bitrateText = videoBitrate?.let { b -> if (b >= 1_000_000) String.format("%.2f Mbps", b / 1_000_000.0) else String.format("%d kb/s", b / 1000) } ?: "-"

        val contentWithBitrate = if (status == StreamStatus.STREAMING) {
            val vb = videoBitrate?.let { b -> if (b >= 1_000_000) String.format("%.2f Mbps", b / 1_000_000.0) else String.format("%d kb/s", b / 1000) } ?: "-"
            // content already contains statusLabel when streaming (e.g., "Live • 00:01:23"),
            // so avoid appending the statusLabel again. Just add bitrate after whatever
            // content we've computed.
            "$content • $vb"
        } else content

        // Avoid duplicating the status label. Use contentWithBitrate directly as the
        // notification's content text; the small status label (statusLabel) is used by
        // the collapsed header where appropriate via NotificationUtils.
        val finalContentText = contentWithBitrate

        val notification = customNotificationUtils.createServiceNotification(
            title = title,
            content = finalContentText,
            iconResourceId = notificationIconResourceId,
            isForeground = status == StreamStatus.STREAMING,
            showStart = showStart,
            showStop = showStop,
            startPending = startPendingIntent,
            stopPending = stopPendingIntent,
            muteLabel = muteLabel,
            mutePending = mutePendingIntent,
            exitPending = exitPendingIntent,
            openPending = openPendingIntent
        )

        val key = listOf(status.name, isCurrentlyMuted(), content, bitrateText, statusLabel).joinToString("|")
        return Pair(notification, key)
    }

    // Decide the effective status using streamer immediate state when available,
    // otherwise fall back to the service-level status. Default to NOT_STREAMING
    // if neither is available.
    private fun getEffectiveServiceStatus(): StreamStatus {
        return try {
            val streamerInstance = streamer
            if (streamerInstance != null) {
                val streamingNow = streamerInstance.isStreamingFlow.value
                if (streamingNow) StreamStatus.STREAMING else StreamStatus.NOT_STREAMING
            } else {
                // Conservative: if streamer is null but service still reports STREAMING,
                // treat as NOT_STREAMING to avoid flashing 'live' when no active streamer.
                val svc = _serviceStreamStatus.value
                if (svc == StreamStatus.STREAMING) StreamStatus.NOT_STREAMING else svc
            }
        } catch (_: Throwable) {
            _serviceStreamStatus.value
        }
    }

    // Format uptime milliseconds to a human readable H:MM:SS or M:SS string
    private fun formatUptime(ms: Long): String {
        if (ms <= 0) return ""
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStreamingStart() {
        // record start time for uptime display
        streamingStartTime = System.currentTimeMillis()
        
        // Lock stream rotation to current orientation when streaming starts
        // If this is initial start, save the orientation for future reconnections
        // If reconnecting, restore the saved orientation
        Log.i(TAG, "onStreamingStart: lockedStreamRotation = ${if (lockedStreamRotation != null) rotationToString(lockedStreamRotation!!) else "null"}, savedStreamingOrientation = ${if (savedStreamingOrientation != null) rotationToString(savedStreamingOrientation!!) else "null"}")
        
        if (lockedStreamRotation == null) {
            // First time streaming - detect and save the orientation
            detectCurrentRotation() // Updates currentRotation variable
            lockedStreamRotation = currentRotation
            savedStreamingOrientation = currentRotation
            Log.i(TAG, "onStreamingStart: INITIAL START - Detected and locked to ${rotationToString(currentRotation)}, saved for reconnection")
        } else if (savedStreamingOrientation != null) {
            // Reconnection - restore the saved orientation
            lockedStreamRotation = savedStreamingOrientation
            Log.i(TAG, "onStreamingStart: RECONNECTION - Restoring saved orientation ${rotationToString(savedStreamingOrientation!!)}")
        } else {
            // Lock exists but no saved orientation (shouldn't happen, but handle it)
            Log.i(TAG, "onStreamingStart: LOCK EXISTS - Maintaining ${rotationToString(lockedStreamRotation!!)} through reconnection")
        }
        
        // Acquire wake locks when streaming starts
        acquireWakeLock()
        acquireNetworkWakeLock()
        
        // Boost process priority for foreground service - use more conservative priority for stability
        try {
            // Use URGENT_AUDIO to prioritize audio capture when streaming in background.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Process priority boosted to URGENT_AUDIO for stable background audio")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to boost process priority", e)
        }
        
        // Request system to keep service alive and update service-side status
        try {
            // Use the "open/streaming" notification when starting foreground so the
            // notification immediately reflects that streaming is in progress.
            // Fall back to the create notification if open notification is not provided.
            // Reinforce foreground with CAMERA and MICROPHONE types only.
            // Avoid MEDIA_PROJECTION here for the same reasons as above.
            startForeground(
                1001,
                onOpenNotification() ?: onCreateNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            Log.i(TAG, "Foreground service reinforced with all required service types")
            try { _serviceStreamStatus.tryEmit(StreamStatus.STREAMING) } catch (_: Throwable) {}
        } catch (e: Exception) {
            Log.w(TAG, "Failed to maintain foreground service state", e)
            try { _serviceStreamStatus.tryEmit(StreamStatus.ERROR) } catch (_: Throwable) {}
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
                acquire() // No timeout - held until manually released
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
     * Acquire network wake lock to prevent network throttling in background
     * Especially important for SRT streaming
     */
    private fun acquireNetworkWakeLock() {
        if (networkWakeLock == null) {
            networkWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LifeStreamer::NetworkUpload"
            ).apply {
                acquire() // No timeout - held until manually released
                Log.i(TAG, "Network wake lock acquired for SRT/RTMP upload")
            }
        }
    }
    
    /**
     * Release network wake lock
     */
    private fun releaseNetworkWakeLock() {
        networkWakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Network wake lock released")
            }
            networkWakeLock = null
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
                // Surface critical error for UI dialogs
                serviceScope.launch { _criticalErrors.emit("Streamer not available") }
                return
            }

            // Read configured endpoint descriptor
            val descriptor = try {
                storageRepository.endpointDescriptorFlow.first()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read endpoint descriptor from storage: ${e.message}")
                customNotificationUtils.notify(onErrorNotification(Throwable("No endpoint configured")) ?: onCreateNotification())
                return
            }

            Log.i(TAG, "startStreamFromConfiguredEndpoint: opening descriptor $descriptor")
            // Indicate start sequence
            try { _serviceStreamStatus.tryEmit(StreamStatus.STARTING) } catch (_: Throwable) {}

            try {
                // Indicate we're attempting to connect/open
                try { _serviceStreamStatus.tryEmit(StreamStatus.CONNECTING) } catch (_: Throwable) {}
                withTimeout(10000) { // 10s open timeout
                    currentStreamer.open(descriptor)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open endpoint descriptor: ${e.message}")
                try { _serviceStreamStatus.tryEmit(StreamStatus.ERROR) } catch (_: Throwable) {}
                customNotificationUtils.notify(onErrorNotification(Throwable("Open failed: ${e.message}")) ?: onCreateNotification())
                serviceScope.launch { _criticalErrors.emit("Open failed: ${e.message}") }
                return
            }

            try {
                // We're ready to start streaming
                try { _serviceStreamStatus.tryEmit(StreamStatus.CONNECTING) } catch (_: Throwable) {}
                currentStreamer.startStream()
                // On success, move to STREAMING
                try { _serviceStreamStatus.tryEmit(StreamStatus.STREAMING) } catch (_: Throwable) {}
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start stream after open: ${e.message}")
                try { _serviceStreamStatus.tryEmit(StreamStatus.ERROR) } catch (_: Throwable) {}
                customNotificationUtils.notify(onErrorNotification(Throwable("Start failed: ${e.message}")) ?: onCreateNotification())
                serviceScope.launch { _criticalErrors.emit("Start failed: ${e.message}") }
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
            // Notify UI of success via notification / status flow; no dialog needed
            // Keep API surface unchanged for future use
        } catch (e: Exception) {
            Log.w(TAG, "startStreamFromConfiguredEndpoint error: ${e.message}")
        }
    }
    
    /**
     * Custom binder that provides access to both the streamer and the service
     */
    inner class CameraStreamerServiceBinder : Binder() {
        fun getService(): CameraStreamerService = this@CameraStreamerService
        val streamer: ISingleStreamer get() = this@CameraStreamerService.streamer
        // Expose critical error flow to bound clients so the UI can show dialogs
        fun criticalErrors() = this@CameraStreamerService.criticalErrors
        // Expose service status flow to bound clients for UI synchronization
        fun serviceStreamStatus() = this@CameraStreamerService.serviceStreamStatus
        // Expose isMuted flow so UI can reflect mute state changes performed externally
        fun isMutedFlow() = this@CameraStreamerService.isMutedFlow
        // Expose uptime flow so UI can display runtime while streaming
        fun uptimeFlow() = this@CameraStreamerService.uptimeFlow
        // Allow bound clients to set mute centrally in the service
        fun setMuted(isMuted: Boolean) {
            try { Log.d(TAG, "Binder.setMuted called: isMuted=$isMuted") } catch (_: Throwable) {}
            this@CameraStreamerService.setMuted(isMuted)
        }
    }

    private val customBinder = CameraStreamerServiceBinder()

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return customBinder
    }

    // Helper to read current mute state from the streamer audio source
    private fun isCurrentlyMuted(): Boolean {
        return try {
            (streamer as? IWithAudioSource)?.audioInput?.isMuted ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Set mute state centrally in the service. This updates the streamer audio
     * source (if available), emits the `isMuted` flow for observers, and
     * refreshes the notification to reflect the new label.
     */
    fun setMuted(isMuted: Boolean) {
        serviceScope.launch(Dispatchers.Default) {
            try {
                val audio = (streamer as? IWithAudioSource)?.audioInput
                if (audio != null) {
                    audio.isMuted = isMuted
                    try { _isMutedFlow.tryEmit(audio.isMuted) } catch (_: Throwable) {}
                } else {
                    // Still emit desired state so UI can update even if streamer not ready
                    try { _isMutedFlow.tryEmit(isMuted) } catch (_: Throwable) {}
                }

                // Rebuild and post canonical notification for the current effective status
                // notifyForCurrentState will compute the effective status and update lastNotificationKey
                notifyForCurrentState()
            } catch (e: Exception) {
                Log.w(TAG, "setMuted failed: ${e.message}")
            }
        }
    }

    /**
     * Update the service stream status. This is typically called from the ViewModel
     * to keep the service's status in sync during reconnection attempts or other
     * state changes that the service might not detect on its own.
     */
    fun updateStreamStatus(status: StreamStatus) {
        try {
            _serviceStreamStatus.tryEmit(status)
            Log.d(TAG, "Service status updated to: $status")
            // Force notification update with new status
            serviceScope.launch {
                notifyForCurrentState()
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateStreamStatus failed: ${e.message}")
        }
    }

    // Helper to compute the localized mute/unmute label based on current audio state
    private fun currentMuteLabel(): String {
        return if (isCurrentlyMuted()) getString(R.string.service_notification_action_unmute) else getString(R.string.service_notification_action_mute)
    }

    override fun onCreateNotification(): Notification {
        return customNotificationUtils.createServiceNotification(
            title = getString(R.string.service_notification_title),
            content = getString(R.string.service_notification_text_created),
            iconResourceId = notificationIconResourceId,
            isForeground = true,
            showStart = true,
            showStop = false,
            startPending = startPendingIntent,
            stopPending = stopPendingIntent,
            muteLabel = currentMuteLabel(),
            mutePending = mutePendingIntent,
            exitPending = exitPendingIntent,
            openPending = openPendingIntent
        )
    }

    override fun onOpenNotification(): Notification? {
        return customNotificationUtils.createServiceNotification(
            title = getString(R.string.service_notification_title),
            content = getString(R.string.status_streaming),
            iconResourceId = notificationIconResourceId,
            isForeground = true,
            showStart = false,
            showStop = true,
            startPending = startPendingIntent,
            stopPending = stopPendingIntent,
            muteLabel = currentMuteLabel(),
            mutePending = mutePendingIntent,
            exitPending = exitPendingIntent,
            openPending = openPendingIntent
        )
    }

    override fun onErrorNotification(t: Throwable): Notification? {
        // Use the canonical status string and append the throwable message for details
        val errorMessage = "${getString(R.string.status_error)}: ${t.message ?: "Unknown error"}"
        // Surface critical error to UI if someone is listening
        try {
            serviceScope.launch { _criticalErrors.emit(errorMessage) }
        } catch (_: Throwable) {}
        return customNotificationUtils.createServiceNotification(
            title = getString(R.string.service_notification_title),
            content = errorMessage,
            iconResourceId = notificationIconResourceId,
            isForeground = false,
            showStart = true,
            showStop = false,
            startPending = startPendingIntent,
            stopPending = stopPendingIntent,
            muteLabel = currentMuteLabel(),
            mutePending = mutePendingIntent,
            exitPending = exitPendingIntent,
            openPending = openPendingIntent
        )
    }

    override fun onCloseNotification(): Notification? {
        return customNotificationUtils.createServiceNotification(
            title = getString(R.string.service_notification_title),
            content = getString(R.string.status_not_streaming),
            iconResourceId = notificationIconResourceId,
            isForeground = false,
            showStart = true,
            showStop = false,
            startPending = startPendingIntent,
            stopPending = stopPendingIntent,
            muteLabel = currentMuteLabel(),
            mutePending = mutePendingIntent,
            exitPending = exitPendingIntent,
            openPending = openPendingIntent
        )
    }
}
