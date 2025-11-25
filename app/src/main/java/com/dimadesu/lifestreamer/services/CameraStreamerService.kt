package com.dimadesu.lifestreamer.services

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.sync.Mutex
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import kotlinx.coroutines.*
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoRotation
import io.github.thibaultbee.streampack.core.streamers.orientation.IRotationProvider
import io.github.thibaultbee.streampack.core.streamers.orientation.SensorRotationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import com.dimadesu.lifestreamer.audio.ScoOrchestrator

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
    
    // Audio passthrough manager for monitoring microphone input
    private val audioPassthroughManager = com.dimadesu.lifestreamer.audio.AudioPassthroughManager()
    
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
    
    // Track if cleanup (close) is still running after stop
    // This prevents race conditions where start is called while previous stop is still cleaning up
    // Shared between UI (via ViewModel) and notification stops
    @Volatile
    var isCleanupInProgress = false
    
    private var statusUpdaterJob: Job? = null
    // Cache last notification state to avoid re-posting identical notifications
    private var lastNotificationKey: String? = null
    // Critical error flow for the UI to show dialogs (non-transient errors)
    // Use replay=0 because we'll handle notification-start timing differently
    private val _criticalErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val criticalErrors = _criticalErrors.asSharedFlow()
    // Current outgoing video bitrate in bits per second (nullable when unknown)
    private val _currentBitrateFlow = MutableStateFlow<Int?>(null)
    val currentBitrateFlow = _currentBitrateFlow.asStateFlow()
    // Current audio mute state exposed as a StateFlow for UI synchronization
    private val _isMutedFlow = MutableStateFlow(false)
    val isMutedFlow = _isMutedFlow.asStateFlow()
    // Whether audio passthrough (monitoring) is currently running on the service
    private val _isPassthroughRunning = MutableStateFlow(false)
    val isPassthroughRunning = _isPassthroughRunning.asStateFlow()
    // Tracks if we started SCO specifically for passthrough monitoring
    private var _scoStartedForPassthrough: Boolean = false
    // Flow used to request BLUETOOTH_CONNECT permission from the UI when needed
    private val bluetoothConnectPermissionRequest = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0)
    // SCO orchestrator helper to centralize detection/permission/start/stop logic
    private val scoOrchestrator by lazy { ScoOrchestrator(this, serviceScope, bluetoothConnectPermissionRequest) }
    // Service-wide streaming status for UI synchronization (shared enum)
    private val _serviceStreamStatus = MutableStateFlow(StreamStatus.NOT_STREAMING)
    val serviceStreamStatus = _serviceStreamStatus.asStateFlow()
    
    // Centralized reconnection state - shared between ViewModel and notification handlers
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting = _isReconnecting.asStateFlow()
    
    // User manually stopped flag - prevents reconnection attempts
    // Shared between ViewModel and notification to stay in sync
    private val _userStoppedManually = MutableStateFlow(false)
    val userStoppedManually = _userStoppedManually.asStateFlow()
    
    // Reconnection status message for UI display
    private val _reconnectionStatusMessage = MutableStateFlow<String?>(null)
    val reconnectionStatusMessage = _reconnectionStatusMessage.asStateFlow()
    
    // Signal when user manually stops from notification (for ViewModel to cancel reconnection)
    private val _userStoppedFromNotification = MutableSharedFlow<Unit>(replay = 0)
    val userStoppedFromNotification = _userStoppedFromNotification.asSharedFlow()
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
        
        // Observe service status changes for immediate notification updates
        // (STARTING, CONNECTING, ERROR, STREAMING, NOT_STREAMING)
        serviceScope.launch {
            serviceStreamStatus.collect { _ ->
                notifyForCurrentState()
            }
        }
        
        // Observe bitrate regulator config changes and update regulator mid-stream
        serviceScope.launch {
            combine(
                storageRepository.bitrateRegulatorConfigFlow,
                storageRepository.regulatorModeFlow
            ) { config, mode -> config to mode }
                .distinctUntilChanged()
                .drop(1) // Skip initial emission to avoid replacing on startup
                .collect { (config, mode) ->
                    // Only update if currently streaming with SRT endpoint
                    if (_serviceStreamStatus.value == StreamStatus.STREAMING && 
                        streamer is ISingleStreamer) {
                        try {
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            if (descriptor.type.sinkType == MediaSinkType.SRT) {
                                Log.i(TAG, "Bitrate regulator settings changed during stream - updating controller")
                                
                                // Remove old controller
                                streamer.removeBitrateRegulatorController()
                                
                                // Re-add with new config if enabled
                                if (config != null) {
                                    streamer.addBitrateRegulatorController(
                                        AdaptiveSrtBitrateRegulatorController.Factory(
                                            bitrateRegulatorConfig = config,
                                            mode = mode
                                        )
                                    )
                                    Log.i(TAG, "Bitrate regulator updated: range=${config.videoBitrateRange.lower/1000}k-${config.videoBitrateRange.upper/1000}k, mode=$mode")
                                } else {
                                    Log.i(TAG, "Bitrate regulator disabled")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update bitrate regulator controller: ${e.message}")
                        }
                    }
                }
        }
        
        // Observe audio config changes and update passthrough if monitoring is active
        serviceScope.launch {
            storageRepository.audioConfigFlow
                .distinctUntilChanged()
                .drop(1) // Skip initial emission to avoid updating on startup
                .collect { audioConfig ->
                    if (audioConfig != null) {
                        val passthroughConfig = com.dimadesu.lifestreamer.audio.AudioPassthroughConfig(
                            sampleRate = audioConfig.sampleRate,
                            channelConfig = audioConfig.channelConfig,
                            audioFormat = audioConfig.byteFormat
                        )
                        audioPassthroughManager.setConfig(passthroughConfig)
                        Log.i(TAG, "Audio passthrough config updated from settings: ${audioConfig.sampleRate}Hz, ${if (audioConfig.channelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO) "STEREO" else "MONO"}")
                    }
                }
        }
    }

    private fun initNotificationPendingIntents() {
        // Use service intents instead of broadcasts - Samsung blocks broadcast receivers
        val startIntent = Intent(this@CameraStreamerService, CameraStreamerService::class.java).apply {
            action = ACTION_START_STREAM
        }
        startPendingIntent = PendingIntent.getService(this@CameraStreamerService, 0, startIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this@CameraStreamerService, CameraStreamerService::class.java).apply {
            action = ACTION_STOP_STREAM
        }
        stopPendingIntent = PendingIntent.getService(this@CameraStreamerService, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val muteIntent = Intent(this@CameraStreamerService, CameraStreamerService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        mutePendingIntent = PendingIntent.getService(this@CameraStreamerService, 2, muteIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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
        // Stop periodic updater
        stopStatusUpdater()

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

        // Ensure audio passthrough is stopped - Quit from notification may call
        // Activity.finishAndRemoveTask() which doesn't always guarantee the
        // service stopped state is fully cleaned up. Stop passthrough here
        // synchronously to avoid stray audio threads continuing to run.
        try {
            Log.i(TAG, "onDestroy: stopping audio passthrough to ensure cleanup")
            // Stop and wait for the passthrough to exit (stop() does join attempts)
            audioPassthroughManager.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy: failed to stop audio passthrough cleanly: ${t.message}")
        }

        try {
            // Cancel any ongoing SCO orchestration and unregister receiver
            scoSwitchJob?.cancel()
            unregisterScoDisconnectReceiver()
            unregisterBtDeviceReceiver()
        } catch (_: Throwable) {}

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle notification action intents here too
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_STREAM -> {
                    Log.i(TAG, "Notification action: START_STREAM")
                    serviceScope.launch(Dispatchers.Default) {
                        // Check if we can start (cleanup not in progress)
                        if (!canStartStream()) {
                            Log.w(TAG, "Cannot start from notification - cleanup in progress or blocked")
                            return@launch
                        }
                        
                        // Clear manual stop flag since user is explicitly starting
                        clearUserStoppedManually()
                        
                        try {
                            // Check if we're using RTMP source - can't start from notification
                            if (isUsingRtmpSource()) {
                                Log.i(TAG, "Cannot start RTMP stream from notification - updating notification")
                                showCannotStartRtmpNotification()
                                return@launch
                            }
                            
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
                        // Mark that user manually stopped (prevents reconnection)
                        markUserStoppedManually()
                        
                        // Mark cleanup in progress to prevent start racing with close()
                        isCleanupInProgress = true
                        Log.i(TAG, "Notification STOP - Set isCleanupInProgress=true")
                        
                        try {
                            // Signal that user manually stopped from notification
                            // This allows ViewModel to cancel reconnection timer immediately
                            _userStoppedFromNotification.emit(Unit)
                            
                            streamer?.stopStream()
                            
                            // Close the endpoint to allow fresh connection on next start
                            try {
                                withTimeout(3000) {
                                    streamer?.close()
                                }
                                Log.i(TAG, "Endpoint closed after stop from notification")
                            } catch (e: Exception) {
                                Log.w(TAG, "Error closing endpoint after notification stop: ${e.message}", e)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Stop from notification failed: ${e.message}")
                        } finally {
                            // Clear cleanup flag - it's now safe to start again
                            isCleanupInProgress = false
                            Log.i(TAG, "Notification STOP - Cleared isCleanupInProgress, cleanup complete")
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
                        if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000.0) else String.format(java.util.Locale.US, "%d kb/s", b / 1000)
                    } ?: ""

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
        // Show Start button when not streaming and not in a transitional state
        val showStart = status == StreamStatus.NOT_STREAMING
        // Show Stop button when streaming or attempting to connect
        val showStop = status == StreamStatus.STREAMING || 
                      status == StreamStatus.CONNECTING || 
                      status == StreamStatus.STARTING

        // Only read bitrate when streaming
        val videoBitrate = if (status == StreamStatus.STREAMING) {
            (streamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder?.bitrate
        } else null

        val bitrateText = videoBitrate?.let { b -> if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000.0) else String.format(java.util.Locale.US, "%d kb/s", b / 1000) } ?: ""

        val contentWithBitrate = if (status == StreamStatus.STREAMING) {
            val vb = videoBitrate?.let { b -> if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000.0) else String.format(java.util.Locale.US, "%d kb/s", b / 1000) } ?: ""
            // content already contains statusLabel when streaming (e.g., "Live • 00:01:23"),
            // so avoid appending the statusLabel again. Just add bitrate after whatever
            // content we've computed.
            "$content • $vb"
        } else content

        // Avoid duplicating the status label. Use contentWithBitrate directly as the
        // notification's content text; the small status label (statusLabel) is used by
        // the collapsed header where appropriate via NotificationUtils.
        val finalContentText = contentWithBitrate
        
        val isFg = status == StreamStatus.STREAMING || status == StreamStatus.CONNECTING

        val notification = customNotificationUtils.createServiceNotification(
            title = title,
            content = finalContentText,
            iconResourceId = notificationIconResourceId,
            // Keep foreground during STREAMING and CONNECTING (includes reconnection)
            isForeground = isFg,
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
            val svcStatus = _serviceStreamStatus.value
            val streamerInstance = streamer
            val reconnecting = _isReconnecting.value
            
            if (streamerInstance != null) {
                val streamingNow = streamerInstance.isStreamingFlow.value
                
                // If we're actively streaming, return STREAMING
                if (streamingNow) return StreamStatus.STREAMING
                
                // If reconnecting, always show CONNECTING status
                if (reconnecting) {
                    return StreamStatus.CONNECTING
                }
                
                // If not streaming but service status is CONNECTING, STARTING, or ERROR,
                // respect the service status (e.g., during reconnection attempts)
                if (svcStatus == StreamStatus.CONNECTING || 
                    svcStatus == StreamStatus.STARTING ||
                    svcStatus == StreamStatus.ERROR) {
                    return svcStatus
                }
                
                // Otherwise, not streaming and no special status
                return StreamStatus.NOT_STREAMING
            } else {
                // Conservative: if streamer is null but service still reports STREAMING,
                // treat as NOT_STREAMING to avoid flashing 'live' when no active streamer.
                if (svcStatus == StreamStatus.STREAMING) StreamStatus.NOT_STREAMING else svcStatus
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
        return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds) else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
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
     * Show a notification when user tries to start RTMP stream from notification.
     * RTMP streams can only be started from the app due to MediaProjection permission requirements.
     */
    private fun showCannotStartRtmpNotification() {
        val notification = createDefaultNotification(
            content = "Can't start with RTMP source from notification"
        )
        customNotificationUtils.notify(notification)
    }
    
    /**
     * Create a standard notification with default settings.
     * Used by onCreateNotification() and other notification methods.
     * 
     * @param content The notification content text
     * @return Notification object
     */
    private fun createDefaultNotification(content: String): Notification {
        return customNotificationUtils.createServiceNotification(
            title = getString(R.string.service_notification_title),
            content = content,
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
    
    /**
     * Validates that both video and audio sources are configured for the streamer.
     * 
     * @return Pair of (isValid, errorMessage). If valid, errorMessage is null.
     */
    private fun validateSourcesConfigured(): Pair<Boolean, String?> {
        val currentStreamer = streamer
        val videoInput = (currentStreamer as? io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource)?.videoInput
        val audioInput = (currentStreamer as? IWithAudioSource)?.audioInput
        
        val videoSource = videoInput?.sourceFlow?.value
        val audioSource = audioInput?.sourceFlow?.value
        
        return if (videoSource == null || audioSource == null) {
            val errorMsg = "video source=${videoSource != null}, audio source=${audioSource != null}"
            false to errorMsg
        } else {
            Log.d(TAG, "Sources validated - video: ${videoSource.javaClass.simpleName}, audio: ${audioSource.javaClass.simpleName}")
            true to null
        }
    }
    
    /**
     * Check if the current video source is RTMP.
     * RTMP sources cannot be started from notifications due to MediaProjection permission requirements.
     * 
     * @return true if current video source is RTMP, false otherwise
     */
    private fun isUsingRtmpSource(): Boolean {
        val currentStreamer = streamer
        val videoSource = (currentStreamer as? io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource)?.videoInput?.sourceFlow?.value
        return videoSource?.javaClass?.simpleName == "RTMPVideoSource"
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

            // Check if sources are configured - wait for them to become available
            // Cast to IWithVideoSource and IWithAudioSource to access inputs
            val videoInput = (currentStreamer as? io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource)?.videoInput
            val audioInput = (currentStreamer as? IWithAudioSource)?.audioInput
            
            // Wait up to 3 seconds for sources to be initialized by ViewModel
            val sourcesReady = withTimeoutOrNull(3000) {
                while (videoInput?.sourceFlow?.value == null || audioInput?.sourceFlow?.value == null) {
                    delay(200)
                }
                true
            } ?: false
            
            val hasVideoSource = videoInput?.sourceFlow?.value != null
            val hasAudioSource = audioInput?.sourceFlow?.value != null
            
            Log.i(TAG, "startStreamFromConfiguredEndpoint: Source check after waiting - Video: $hasVideoSource, Audio: $hasAudioSource")
            
            if (!hasVideoSource || !hasAudioSource) {
                // Sources still not available after waiting - this means ViewModel hasn't initialized them
                // This can happen if user clicks Start in notification before opening the app
                val errorMsg = "Sources not initialized - please start from app first"
                Log.w(TAG, errorMsg)
                customNotificationUtils.notify(onErrorNotification(Throwable(errorMsg)) ?: onCreateNotification())
                serviceScope.launch { _criticalErrors.emit(errorMsg) }
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

            // Final validation: Ensure both sources are still configured right before starting stream
            val (sourcesValid, sourceError) = validateSourcesConfigured()
            if (!sourcesValid) {
                val errorMsg = "Cannot start stream: $sourceError"
                Log.e(TAG, "startStreamFromConfiguredEndpoint: $errorMsg")
                customNotificationUtils.notify(onErrorNotification(Throwable(errorMsg)) ?: onCreateNotification())
                serviceScope.launch { _criticalErrors.emit(errorMsg) }
                return
            }
            Log.i(TAG, "startStreamFromConfiguredEndpoint: Final source validation passed")

            Log.i(TAG, "startStreamFromConfiguredEndpoint: opening descriptor $descriptor")
            // Indicate start sequence
            try { _serviceStreamStatus.tryEmit(StreamStatus.STARTING) } catch (_: Throwable) {}

            try {
                // Indicate we're attempting to connect/open
                try { _serviceStreamStatus.tryEmit(StreamStatus.CONNECTING) } catch (_: Throwable) {}
                // Use NonCancellable for camera configuration to prevent "Broken pipe" errors
                // if coroutine is cancelled during camera setup
                withTimeout(5000) { // 5s open timeout
                    // withContext(NonCancellable) {
                        currentStreamer.open(descriptor)
                    // }
                }
                
                // Wait for encoders to be initialized after open
                // This prevents the race condition where video encoder isn't ready after rapid stop/start
                Log.d(TAG, "startStreamFromConfiguredEndpoint: Waiting for encoders to initialize...")
                val encodersReady = withTimeoutOrNull(5000) {
                    while ((currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder == null 
                           || (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer)?.audioEncoder == null) {
                        delay(200)
                    }
                    true
                } ?: false
                
                if (!encodersReady) {
                    val videoEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder != null
                    val audioEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer)?.audioEncoder != null
                    val errorMsg = "Encoders not ready after open (video=$videoEncoderExists, audio=$audioEncoderExists)"
                    Log.e(TAG, "startStreamFromConfiguredEndpoint: $errorMsg")
                    customNotificationUtils.notify(onErrorNotification(Throwable(errorMsg)) ?: onCreateNotification())
                    serviceScope.launch { _criticalErrors.emit(errorMsg) }
                    return
                }
                val videoEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder != null
                val audioEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer)?.audioEncoder != null
                Log.i(TAG, "startStreamFromConfiguredEndpoint: Encoders ready - video=$videoEncoderExists, audio=$audioEncoderExists")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open endpoint descriptor: ${e.message}")
                // Don't set ERROR status - keep CONNECTING so ViewModel can trigger reconnection
                // The ViewModel's critical errors observer will detect this and trigger handleDisconnection
                // Don't call notify(onCreateNotification()) here - let the status observer handle it
                // to avoid overwriting the CONNECTING notification
                // Emit critical error for ViewModel to observe and trigger reconnection
                serviceScope.launch { _criticalErrors.emit("Open failed: ${e.message}") }
                return
            }

            try {
                // We're ready to start streaming
                try { _serviceStreamStatus.tryEmit(StreamStatus.CONNECTING) } catch (_: Throwable) {}
                // Protect startStream() from cancellation to prevent camera configuration errors
                // withContext(NonCancellable) {
                    currentStreamer.startStream()
                // }
                // Don't set STREAMING immediately - let getEffectiveServiceStatus() 
                // derive it from isStreamingFlow.value to ensure accuracy
                Log.i(TAG, "startStream() called successfully, waiting for isStreamingFlow to confirm")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start stream after open: ${e.message}")
                // Don't set ERROR status - keep CONNECTING so ViewModel can trigger reconnection
                // The ViewModel's critical errors observer will detect this and trigger handleDisconnection
                // Don't call notify(onCreateNotification()) here - let the status observer handle it
                // to avoid overwriting the CONNECTING notification
                // Emit critical error for ViewModel to observe and trigger reconnection
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
            // Launch SCO orchestration after stream start so we can attempt to switch
            // to a Bluetooth mic while keeping the mic source active until SCO connects.
            try {
                scoSwitchJob?.cancel()
                scoSwitchJob = serviceScope.launch(Dispatchers.Default) {
                    // Small stabilization delay to let stream fully settle
                    delay(300)
                    try {
                        attemptScoNegotiationAndSwitch(currentStreamer)
                    } catch (t: Throwable) {
                        Log.w(TAG, "SCO orchestration job failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start SCO orchestration job: ${t.message}")
            }
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
        // Note: audio passthrough control is intentionally not exposed via Binder
        // to keep the service API surface minimal. Bound clients can call
        // `getService()` and control passthrough via the returned service instance
        // when they hold a direct reference.
        // Expose a flow that will request BLUETOOTH_CONNECT permission from the UI
        fun bluetoothConnectPermissionRequests() = this@CameraStreamerService.bluetoothConnectPermissionRequest.asSharedFlow()
        // Expose SCO state flow for UI
        fun scoStateFlow() = this@CameraStreamerService.scoStateFlow.asSharedFlow()
        // Allow bound clients to enable/disable Bluetooth mic policy at runtime
        fun setUseBluetoothMic(enabled: Boolean) {
            try { this@CameraStreamerService.applyBluetoothPolicy(enabled) } catch (_: Throwable) {}
        }
    }

    private val customBinder = CameraStreamerServiceBinder()

    // SCO orchestration state
    private var scoSwitchJob: Job? = null
    private var scoDisconnectReceiver: BroadcastReceiver? = null
    // Receiver for Bluetooth device connect/disconnect events when policy is enabled
    private var btDeviceReceiver: BroadcastReceiver? = null
    // Background job that periodically polls for BT input device presence
    private var btDeviceMonitorJob: Job? = null
    // Helper job to serialize passthrough restarts
    private var passthroughRestartJob: Job? = null
    private val scoMutex = kotlinx.coroutines.sync.Mutex()
    // SCO negotiation state exposed to UI
    enum class ScoState { IDLE, TRYING, USING_BT, FAILED }
    private val scoStateFlow = kotlinx.coroutines.flow.MutableStateFlow(ScoState.IDLE)

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        // If Bluetooth policy already enabled when client binds, ensure receiver
        // is registered so we catch disconnects even when not streaming/passthrough.
        try {
            if (com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.isEnabled()) {
                // Ensure we have permission to inspect Bluetooth devices. If not,
                // request it via the existing permission flow so the UI can prompt.
                if (!scoOrchestrator.ensurePermission()) {
                    Log.i(TAG, "onBind: BLUETOOTH_CONNECT not granted - requesting from UI")
                    // Don't attempt detection until permission granted; UI should call
                    // setUseBluetoothMic(true) again (ViewModel will do this) after grant.
                } else {
                    registerBtDeviceReceiver()
                    // Quick check: if no BT input device currently present, revert policy
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            val btDevice = scoOrchestrator.detectBtInputDevice()
                            if (btDevice == null) {
                                Log.i(TAG, "onBind: Bluetooth policy enabled but no BT device found - reverting")
                                try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false) } catch (_: Throwable) {}
                                try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null) } catch (_: Throwable) {}
                                try { scoStateFlow.tryEmit(ScoState.FAILED) } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
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
     * Apply Bluetooth mic policy at runtime.
     * 
     * When enabled:
     * - Ensures BLUETOOTH_CONNECT permission (requests from UI if needed)
     * - If streaming: attempts SCO negotiation and switches audio source
     * - If not streaming: performs quick connectivity check for UI feedback
     * - Registers device monitoring to handle disconnects
     * 
     * When disabled:
     * - Stops ongoing SCO orchestration
     * - Reverts to built-in microphone source
     * - Unregisters device monitoring
     * 
     * @param enabled true to enable Bluetooth mic, false to disable
     */
    fun applyBluetoothPolicy(enabled: Boolean) {
        try {
            com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(enabled)
        } catch (_: Throwable) {}

        if (!enabled) {
            // Cancel SCO orchestration job and stop SCO if active
            try { scoSwitchJob?.cancel() } catch (_: Throwable) {}
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                try { am?.stopBluetoothSco() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null) } catch (_: Throwable) {}
            // Recreate microphone source to switch back to built-in mic
            try { recreateMicSource(this.streamer) } catch (_: Throwable) {}
            // Update state
            try { scoStateFlow.tryEmit(ScoState.IDLE) } catch (_: Throwable) {}
            // Unregister device receiver when policy disabled
            try { unregisterBtDeviceReceiver() } catch (_: Throwable) {}
        } else {
            // Ensure we have permission before attempting any detection or orchestration.
            if (!scoOrchestrator.ensurePermission()) {
                Log.i(TAG, "applyBluetoothPolicy: BLUETOOTH_CONNECT not granted - requesting UI and reverting toggle")
                // Ask UI to request permission via the shared flow and revert the policy until user grants.
                try { bluetoothConnectPermissionRequest.tryEmit(Unit) } catch (_: Throwable) {}
                try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false) } catch (_: Throwable) {}
                try { scoStateFlow.tryEmit(ScoState.FAILED) } catch (_: Throwable) {}
                return
            }
            // If enabled and we are streaming, attempt SCO orchestration
            try {
                val currentStreamer = this.streamer
                if (currentStreamer != null && currentStreamer.isStreamingFlow.value == true) {
                    scoSwitchJob?.cancel()
                    scoSwitchJob = serviceScope.launch(Dispatchers.Default) {
                        delay(200)
                        try { attemptScoNegotiationAndSwitch(currentStreamer) } catch (_: Throwable) {}
                    }
                }
                // If not streaming, proactively try a quick connectivity check so the UI
                // reflects immediately whether a BT mic is available. This makes the
                // toggle feel responsive even before monitoring/streaming starts.
                if (currentStreamer == null || currentStreamer.isStreamingFlow.value != true) {
                    // Fire-and-forget connectivity check using the orchestrator helper
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            scoStateFlow.tryEmit(ScoState.TRYING)
                            val btDevice = scoOrchestrator.detectBtInputDevice()
                            if (btDevice == null) {
                                Log.i(TAG, "Connectivity check: no BT input device detected")
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false)
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                                scoStateFlow.tryEmit(ScoState.FAILED)
                                return@launch
                            }

                            com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(btDevice)
                            if (!scoOrchestrator.ensurePermission()) {
                                Log.i(TAG, "Connectivity check: permission missing, requested UI")
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false)
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                                scoStateFlow.tryEmit(ScoState.FAILED)
                                return@launch
                            }

                            val connected = scoOrchestrator.startScoAndWait(3000)
                            if (!connected) {
                                Log.i(TAG, "Connectivity check: SCO did not connect")
                                scoOrchestrator.stopScoQuietly()
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false)
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                                scoStateFlow.tryEmit(ScoState.FAILED)
                                return@launch
                            }

                            // Success - stop SCO (don't keep it running) and verify routing
                            scoOrchestrator.stopScoQuietly()
                            verifyAndEmitUsingBtOrFail(1500)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Connectivity check failed: ${t.message}")
                            com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false)
                            com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                            scoStateFlow.tryEmit(ScoState.FAILED)
                        }
                    }
                }
            } catch (_: Throwable) {}
            // Register receiver to monitor BT device connect/disconnect while policy enabled
            try { registerBtDeviceReceiver() } catch (_: Throwable) {}
        }
    }

    private fun registerBtDeviceReceiver() {
        try {
            if (btDeviceReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val action = intent?.action ?: return
                        Log.d(TAG, "BT device receiver action=$action")
                        if (action == android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                            action == android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) {
                            // When any device disconnects, check if a BT input device still exists
                            serviceScope.launch(Dispatchers.Default) {
                                try {
                                    var btDevice = scoOrchestrator.detectBtInputDevice()
                                    // If no device detected via AudioManager, check headset profile state
                                    if (btDevice == null) {
                                        val headsetConnected = scoOrchestrator.isHeadsetConnected()
                                        if (!headsetConnected) btDevice = null
                                    }
                                    Log.d(TAG, "BT device receiver: detected btDevice=${btDevice?.id}")
                                    if (btDevice == null) {
                                        Log.i(TAG, "BT device disconnected and no BT input remains - reverting Bluetooth policy off")
                                        try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false) } catch (_: Throwable) {}
                                        try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null) } catch (_: Throwable) {}
                                        try { scoStateFlow.tryEmit(ScoState.FAILED) } catch (_: Throwable) {}
                                        // If we started SCO for passthrough, stop it
                                        try {
                                            if (_scoStartedForPassthrough) {
                                                scoOrchestrator.stopScoQuietly()
                                                _scoStartedForPassthrough = false
                                            }
                                        } catch (_: Throwable) {}
                                    } else {
                                        // A BT input device is present. If passthrough is running
                                        // and Bluetooth policy is enabled, restart passthrough
                                        // so monitoring switches to BT immediately.
                                        try {
                                            if (_isPassthroughRunning.value && com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.isEnabled()) {
                                                restartPassthroughIfRunning()
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                } catch (t: Throwable) {
                                    Log.w(TAG, "BT device receiver handling failed: ${t.message}")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "BT device receiver onReceive error: ${t.message}")
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            }
            registerReceiver(receiver, filter)
            btDeviceReceiver = receiver
            // Start a short-lived monitor that polls for device presence while policy is enabled
            try {
                btDeviceMonitorJob?.cancel()
                btDeviceMonitorJob = serviceScope.launch(Dispatchers.Default) {
                    var consecutiveMisses = 0
                    while (isActive) {
                        try {
                            val btDevice = scoOrchestrator.detectBtInputDevice()
                            val headsetConnected = if (btDevice == null) scoOrchestrator.isHeadsetConnected() else true
                            if (btDevice != null || headsetConnected) {
                                consecutiveMisses = 0
                            } else {
                                consecutiveMisses++
                                Log.d(TAG, "BT monitor: no device found (misses=$consecutiveMisses)")
                            }
                            if (consecutiveMisses >= 2) {
                                Log.i(TAG, "BT monitor: device missing for multiple checks - reverting Bluetooth policy off")
                                try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false) } catch (_: Throwable) {}
                                try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null) } catch (_: Throwable) {}
                                try { scoStateFlow.tryEmit(ScoState.FAILED) } catch (_: Throwable) {}
                                // Stop SCO if we started it for passthrough
                                try {
                                    if (_scoStartedForPassthrough) {
                                        scoOrchestrator.stopScoQuietly()
                                        _scoStartedForPassthrough = false
                                    }
                                } catch (_: Throwable) {}
                                break
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "BT monitor error: ${t.message}")
                        }
                        delay(2000)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start BT monitor job: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register BT device receiver: ${t.message}")
        }
    }

    private fun unregisterBtDeviceReceiver() {
        try {
            btDeviceReceiver?.let { unregisterReceiver(it) }
        } catch (_: Throwable) {}
        btDeviceReceiver = null
        try { btDeviceMonitorJob?.cancel() } catch (_: Throwable) {}
        btDeviceMonitorJob = null
    }

    private fun restartPassthroughIfRunning() {
        // Serialize restarts to avoid overlapping stop/start
        passthroughRestartJob?.cancel()
        passthroughRestartJob = serviceScope.launch(Dispatchers.Default) {
            try {
                if (_isPassthroughRunning.value) {
                    Log.i(TAG, "Restarting passthrough to switch input device to Bluetooth")
                    try { stopAudioPassthrough() } catch (_: Throwable) {}
                    // Give the system a small moment to switch audio routing
                    delay(300)
                    try { startAudioPassthrough() } catch (_: Throwable) {}
                    Log.i(TAG, "Passthrough restarted via service methods")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to restart passthrough: ${t.message}")
            }
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
     * Start audio passthrough - monitors microphone input and plays through speakers
     * Uses audio configuration from settings to match streaming audio
     */
    fun startAudioPassthrough() {
        // Cancel any previous job and start a fresh one that will update state when ready
        serviceScope.launch(Dispatchers.Default) {
            try {
                // If app-level Bluetooth mic policy is enabled, try to negotiate SCO
                // and prefer BT input for passthrough. We track whether we started
                // SCO so we can stop it when passthrough stops.
                try {
                    if (com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.isEnabled()) {
                        // Notify UI we are attempting SCO for passthrough
                        try { scoStateFlow.tryEmit(ScoState.TRYING) } catch (_: Throwable) {}
                        val btDevice = scoOrchestrator.detectBtInputDevice()
                        if (btDevice != null) {
                            com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(btDevice)
                            try {
                                if (!scoOrchestrator.ensurePermission()) {
                                    Log.w(TAG, "Passthrough SCO: permission missing, emitted request and failing")
                                    com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                                    scoStateFlow.tryEmit(ScoState.FAILED)
                                } else {
                                    val connected = scoOrchestrator.startScoAndWait(4000)
                                    if (connected) {
                                        _scoStartedForPassthrough = true
                                        Log.i(TAG, "Passthrough SCO: SCO connected, will prefer BT input")
                                        try {
                                            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                            try { am?.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Throwable) {}
                                        } catch (_: Throwable) {}
                                        // Verify routing before announcing USING_BT
                                        verifyAndEmitUsingBtOrFail(2000)
                                    } else {
                                        Log.i(TAG, "Passthrough SCO: SCO did not connect - will use mic")
                                        scoOrchestrator.stopScoQuietly()
                                        com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                                        scoStateFlow.tryEmit(ScoState.FAILED)
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Passthrough SCO negotiation failed: ${t.message}")
                                com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                                scoStateFlow.tryEmit(ScoState.FAILED)
                            }
                        } else {
                            Log.i(TAG, "Passthrough SCO: no BT input device detected - will use built-in mic")
                            com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                            scoStateFlow.tryEmit(ScoState.FAILED)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Passthrough pre-start SCO attempt failed: ${t.message}")
                    try { scoStateFlow.tryEmit(ScoState.FAILED) } catch (_: Throwable) {}
                }
                val audioConfig = storageRepository.audioConfigFlow.first()
                if (audioConfig != null) {
                    val passthroughConfig = com.dimadesu.lifestreamer.audio.AudioPassthroughConfig(
                        sampleRate = audioConfig.sampleRate,
                        channelConfig = audioConfig.channelConfig,
                        audioFormat = audioConfig.byteFormat
                    )
                    audioPassthroughManager.setConfig(passthroughConfig)
                    Log.i(TAG, "Audio passthrough config: ${audioConfig.sampleRate}Hz, ${if (audioConfig.channelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO) "STEREO" else "MONO"}")
                }

                // Attempt to detect a Bluetooth input device and publish it to the app-level
                // BluetoothAudioConfig so factories can prefer it when creating audio sources.
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val btDevice = audioManager
                        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        ?.firstOrNull { d ->
                            // Only consider devices that are actual input sources
                            // and represent Bluetooth SCO (HFP) input. A2DP is output-only
                            // and should not be used as a recording device.
                            try {
                                d.isSource && d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            } catch (_: Throwable) { false }
                        }
                    if (btDevice != null) {
                        com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(btDevice)
                        Log.i(TAG, "Detected BT input device (id=${btDevice.id}) and set as preferred")
                    } else {
                        com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
                        Log.i(TAG, "No BT input device detected for passthrough")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Error while detecting BT input devices: ${t.message}")
                }

                audioPassthroughManager.start()
                // Emit running state after successful start
                try { _isPassthroughRunning.tryEmit(true) } catch (_: Throwable) {}
                Log.i(TAG, "Audio passthrough started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio passthrough: ${e.message}", e)
                try { _isPassthroughRunning.tryEmit(false) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Stop audio passthrough
     */
    fun stopAudioPassthrough() {
        serviceScope.launch(Dispatchers.Default) {
            try {
                audioPassthroughManager.stop()
                try { _isPassthroughRunning.tryEmit(false) } catch (_: Throwable) {}
                Log.i(TAG, "Audio passthrough stopped")
                // Clear preferred BT device when passthrough stops so factories re-evaluate
                try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null) } catch (_: Throwable) {}

                // If we started SCO specifically for passthrough, stop it now
                try {
                    if (_scoStartedForPassthrough) {
                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            try { am?.stopBluetoothSco() } catch (_: Throwable) {}
                            try { am?.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
                        try { _scoStartedForPassthrough = false } catch (_: Throwable) {}
                        Log.i(TAG, "Stopped SCO that was started for passthrough")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed stopping passthrough SCO: ${t.message}")
                }

                // Reset SCO state for UI now that passthrough stopped
                try { scoStateFlow.tryEmit(ScoState.IDLE) } catch (_: Throwable) {}
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop audio passthrough: ${e.message}", e)
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
    
    /**
     * Mark that user has manually stopped the stream.
     * This prevents automatic reconnection attempts.
     * Called from both UI (via ViewModel) and notification handlers.
     */
    fun markUserStoppedManually() {
        Log.i(TAG, "markUserStoppedManually() called")
        _userStoppedManually.value = true
        // Also cancel any ongoing reconnection
        if (_isReconnecting.value) {
            Log.i(TAG, "Cancelling reconnection due to manual stop")
            _isReconnecting.value = false
            _reconnectionStatusMessage.value = null
        }
    }
    
    /**
     * Clear the manual stop flag when user initiates a new stream start.
     * Called from both UI (via ViewModel) and notification handlers.
     */
    fun clearUserStoppedManually() {
        Log.i(TAG, "clearUserStoppedManually() called")
        _userStoppedManually.value = false
    }
    
    /**
     * Check if we can start streaming.
     * Returns false if cleanup is in progress or user recently stopped manually.
     */
    fun canStartStream(): Boolean {
        if (isCleanupInProgress) {
            Log.w(TAG, "Cannot start - cleanup in progress")
            return false
        }
        return true
    }
    
    /**
     * Begin a reconnection attempt.
     * Returns true if reconnection should proceed, false if it should be skipped.
     */
    fun beginReconnection(reason: String): Boolean {
        if (_userStoppedManually.value) {
            Log.i(TAG, "Skipping reconnection - user stopped manually")
            return false
        }
        if (_isReconnecting.value) {
            Log.d(TAG, "Already reconnecting, skipping duplicate")
            return false
        }
        if (isCleanupInProgress) {
            Log.w(TAG, "Skipping reconnection - cleanup in progress")
            return false
        }
        
        Log.i(TAG, "Beginning reconnection - reason: $reason")
        // Set reconnecting flag FIRST so that when status change triggers observers,
        // getEffectiveServiceStatus() will see reconnecting=true
        _isReconnecting.value = true
        _reconnectionStatusMessage.value = "Could not connect. Reconnecting in 5 seconds"
        // Now set status - this triggers observers which will see reconnecting=true
        _serviceStreamStatus.value = StreamStatus.CONNECTING
        return true
    }
    
    /**
     * Update reconnection status message for UI display.
     */
    fun setReconnectionMessage(message: String?) {
        _reconnectionStatusMessage.value = message
    }
    
    /**
     * Mark reconnection as complete (successful).
     */
    fun completeReconnection() {
        Log.i(TAG, "Reconnection completed successfully")
        _isReconnecting.value = false
        _reconnectionStatusMessage.value = "Reconnected successfully!"
        
        // Clear success message after 3 seconds
        serviceScope.launch {
            delay(3000)
            _reconnectionStatusMessage.value = null
        }
    }
    
    /**
     * Cancel reconnection attempt.
     */
    fun cancelReconnection() {
        Log.i(TAG, "Reconnection cancelled")
        // Set flag FIRST before status to avoid intermediate state
        _isReconnecting.value = false
        _reconnectionStatusMessage.value = null
        // Now update status - this will trigger notification observer
        ensureNormalAudioRouting()
        _serviceStreamStatus.value = StreamStatus.NOT_STREAMING
    }
    
    /**
     * Update the stream status. Called by ViewModel to keep Service and notification in sync.
     */
    fun setStreamStatus(status: StreamStatus) {
        Log.d(TAG, "setStreamStatus: $status (isReconnecting=${_isReconnecting.value})")
        _serviceStreamStatus.value = status
        // Trigger watcher to start/stop SCO orchestration as needed
        onServiceStatusChanged(status)
    }

    private fun ensureNormalAudioRouting() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                Log.i(TAG, "ensureNormalAudioRouting: stopping SCO and restoring MODE_NORMAL (current mode=${am.mode})")
                try { am.stopBluetoothSco() } catch (_: Throwable) {}
                try { am.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensureNormalAudioRouting failed: ${t.message}")
        }
    }

    // Watcher: trigger SCO orchestration when entering STREAMING
    private fun onServiceStatusChanged(status: StreamStatus) {
        try {
            if (status == StreamStatus.STREAMING) {
                // Ensure we are in normal routing before attempting SCO
                ensureNormalAudioRouting()
                // start orchestration if not already running
                scoSwitchJob?.cancel()
                scoSwitchJob = serviceScope.launch(Dispatchers.Default) {
                    delay(300)
                    try { attemptScoNegotiationAndSwitch(streamer) } catch (t: Throwable) { Log.w(TAG, "SCO orchestration job failed: ${t.message}") }
                }
            } else {
                // cancel orchestration and ensure normal routing if we stop streaming
                scoSwitchJob?.cancel()
                ensureNormalAudioRouting()
            }
        } catch (t: Throwable) { Log.w(TAG, "onServiceStatusChanged failed: ${t.message}") }
    }

    // Helper to compute the localized mute/unmute label based on current audio state
    private fun currentMuteLabel(): String {
        return if (isCurrentlyMuted()) getString(R.string.service_notification_action_unmute) else getString(R.string.service_notification_action_mute)
    }

    override fun onCreateNotification(): Notification {
        return createDefaultNotification(
            content = getString(R.string.service_notification_text_created)
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
        // Don't show "Not streaming" if we're in reconnection mode
        if (_isReconnecting.value) {
            Log.d(TAG, "onCloseNotification: Suppressing notification during reconnection")
            return null
        }
        
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

    /**
     * Attempt to negotiate SCO and switch the streamer's audio source to Bluetooth.
     * This function is safe to call multiple times; it serializes attempts using a mutex.
     */
    private suspend fun attemptScoNegotiationAndSwitch(streamerInstance: ISingleStreamer?) {
        if (streamerInstance == null) return
        // Ensure Bluetooth policy is enabled
        if (!com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.isEnabled()) {
            Log.i(TAG, "SCO orchestration: Bluetooth disabled by policy")
            return
        }

        // Ensure we have a preferred BT device. If not set yet, try to detect one now
        var preferred = try { com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.getPreferredDevice() } catch (_: Throwable) { null }
        if (preferred == null) {
            try {
                val btDevice = scoOrchestrator.detectBtInputDevice()
                if (btDevice != null) {
                    com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(btDevice)
                    preferred = btDevice
                    Log.i(TAG, "SCO orchestration: detected and selected BT input device id=${btDevice.id}")
                } else {
                    Log.i(TAG, "SCO orchestration: no BT input device detected")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SCO orchestration: error detecting BT device: ${t.message}")
            }
        }

        if (preferred == null) {
            Log.i(TAG, "SCO orchestration: no preferred BT device available - will still attempt SCO (nullable device)")
        }

            // Ensure only one orchestration at a time
            scoMutex.lock()
            try {
                scoStateFlow.tryEmit(ScoState.TRYING)
                // Verify streamer still running and current audio source is not already BT
                val audioInput = (streamerInstance as? IWithAudioSource)?.audioInput
                val currentSource = audioInput?.sourceFlow?.value
                if (currentSource != null && currentSource.javaClass.simpleName.contains("Bluetooth", ignoreCase = true)) {
                    Log.i(TAG, "SCO orchestration: audio source already Bluetooth - skipping")
                    // Verify routing before announcing USING_BT to avoid false positives
                    verifyAndEmitUsingBtOrFail(1200)
                    return
                }

                Log.i(TAG, "SCO orchestration: starting negotiation for device id=${preferred?.id ?: -1}")

                // Request BLUETOOTH_CONNECT on S+ before attempting SCO using orchestrator
                if (!scoOrchestrator.ensurePermission()) {
                    Log.w(TAG, "SCO orchestration: permission missing - requested UI and aborting automatic SCO")
                    scoStateFlow.tryEmit(ScoState.FAILED)
                    return
                }

                val connected = scoOrchestrator.startScoAndWait(4000)
                if (!connected) {
                    Log.i(TAG, "SCO orchestration: SCO did not connect - leaving mic source")
                    scoOrchestrator.stopScoQuietly()
                    try { (streamerInstance as? IWithAudioSource)?.setAudioSource(io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory()) } catch (t: Throwable) { Log.w(TAG, "SCO orchestration: failed to reset mic after SCO failure: ${t.message}") }
                    scoStateFlow.tryEmit(ScoState.FAILED)
                    return
                }

                Log.i(TAG, "SCO orchestration: SCO connected - switching audio source to Bluetooth")

                // Do not change AudioManager.mode here; keep MODE_NORMAL to avoid residual routing issues

                // Switch streamer audio source to AppBluetoothSourceFactory with preferred device
                try {
                    (streamerInstance as? IWithAudioSource)?.let { withAudio ->
                        withAudio.setAudioSource(com.dimadesu.lifestreamer.audio.AppBluetoothSourceFactory(preferred))
                        Log.i(TAG, "SCO orchestration: setAudioSource called for Bluetooth factory")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "SCO orchestration: setAudioSource failed: ${t.message}")
                    scoStateFlow.tryEmit(ScoState.FAILED)
                }

                // Register disconnect receiver to revert to mic on SCO disconnect
                registerScoDisconnectReceiver()
                // Verify routing before announcing USING_BT
                verifyAndEmitUsingBtOrFail(2000)
            } finally {
                scoMutex.unlock()
            }
    }

    private fun registerScoDisconnectReceiver() {
        try {
            if (scoDisconnectReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.let { i ->
                        if (i.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                            val state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                            if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                                Log.i(TAG, "SCO disconnect detected - reverting to microphone source")
                                // Revert to mic source asynchronously
                                serviceScope.launch(Dispatchers.Default) {
                                    try {
                                        val wasPassthrough = _isPassthroughRunning.value
                                        if (wasPassthrough) {
                                            try {
                                                audioPassthroughManager.stop()
                                                try { _isPassthroughRunning.tryEmit(false) } catch (_: Throwable) {}
                                            } catch (_: Throwable) {}
                                        }

                                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                        try { am?.stopBluetoothSco() } catch (_: Throwable) {}
                                        try { am?.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}

                                        // Small delay to let system settle audio routing
                                        delay(250)

                                        recreateMicSource(streamer)

                                        // Small delay to allow mic AudioRecord to initialize
                                        delay(150)

                                        if (wasPassthrough) {
                                            try {
                                                audioPassthroughManager.start()
                                                try { _isPassthroughRunning.tryEmit(true) } catch (_: Throwable) {}
                                            } catch (_: Throwable) {
                                                try { _isPassthroughRunning.tryEmit(false) } catch (_: Throwable) {}
                                            }
                                        }

                                        scoStateFlow.tryEmit(ScoState.IDLE)
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "Failed to revert to mic on SCO disconnect: ${t.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            registerReceiver(receiver, filter)
            scoDisconnectReceiver = receiver
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register SCO disconnect receiver: ${t.message}")
        }
    }

    private fun unregisterScoDisconnectReceiver() {
        try {
            scoDisconnectReceiver?.let { unregisterReceiver(it) }
        } catch (_: Throwable) {}
        scoDisconnectReceiver = null
    }

    private suspend fun waitForScoConnected(context: Context, timeoutMs: Long): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (audioManager.isBluetoothScoOn) return true
            } catch (_: Throwable) {}
            delay(200)
        }
        return false
    }

    /**
     * Verify that the platform actually routed audio over SCO/BT before emitting USING_BT.
     * This helps avoid reporting USING_BT when the system hasn't applied routing yet.
     * Returns true if USING_BT was emitted, false if verification failed and FAILED emitted.
     */
    private suspend fun verifyAndEmitUsingBtOrFail(timeoutMs: Long = 2000, pollMs: Long = 250): Boolean {
        val am = try { getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (_: Throwable) { null }
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val isScoOn = try { am?.isBluetoothScoOn ?: false } catch (_: Throwable) { false }
            val btDevice = try { scoOrchestrator.detectBtInputDevice() } catch (_: Throwable) { null }
            Log.d(TAG, "verifyAndEmitUsingBtOrFail: attempt=$attempt isBluetoothScoOn=$isScoOn btDeviceId=${btDevice?.id ?: -1}")
            if (isScoOn || btDevice != null) {
                try { scoStateFlow.tryEmit(ScoState.USING_BT) } catch (_: Throwable) {}
                Log.i(TAG, "verifyAndEmitUsingBtOrFail: confirmed SCO routing (isBluetoothScoOn=$isScoOn btDevice=${btDevice?.id}) - emitted USING_BT")
                return true
            }
            delay(pollMs)
        }
        Log.i(TAG, "verifyAndEmitUsingBtOrFail: failed to confirm SCO routing after ${timeoutMs}ms - emitting FAILED")
        try { scoStateFlow.tryEmit(ScoState.FAILED) } catch (_: Throwable) {}
        return false
    }

    private fun recreateMicSource(streamerInstance: ISingleStreamer?) {
        try {
            Log.i(TAG, "Recreating mic source to ensure clean audio session")
            serviceScope.launch(Dispatchers.Default) {
                try {
                    (streamerInstance as? IWithAudioSource)?.setAudioSource(
                        io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory()
                    )
                    Log.i(TAG, "Recreate mic source: used default MicrophoneSourceFactory")
                } catch (t: Throwable) {
                    Log.w(TAG, "Recreate mic source failed inside coroutine: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Recreate mic source failed: ${t.message}")
        }
    }
}
