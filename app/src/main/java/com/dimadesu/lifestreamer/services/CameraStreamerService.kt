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
import io.github.thibaultbee.streampack.core.elements.sources.IMediaProjectionSource
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
    
    // Audio passthrough manager for monitoring microphone input (lazy init after context available)
    private val audioPassthroughManager by lazy { 
        com.dimadesu.lifestreamer.audio.AudioPassthroughManager(applicationContext)
    }
    
    // Track passthrough restart job to prevent concurrent restarts
    private var passthroughRestartJob: kotlinx.coroutines.Job? = null
    
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
    // Encoder stats flow for UI display
    private val _encoderStatsFlow = MutableStateFlow<String?>(null)
    val encoderStatsFlow = _encoderStatsFlow.asStateFlow()
    // Current audio mute state exposed as a StateFlow for UI synchronization
    private val _isMutedFlow = MutableStateFlow(false)
    val isMutedFlow = _isMutedFlow.asStateFlow()
    // Whether audio passthrough (monitoring) is currently running on the service
    private val _isPassthroughRunning = MutableStateFlow(false)
    val isPassthroughRunning = _isPassthroughRunning.asStateFlow()
    // Flow used to request BLUETOOTH_CONNECT permission from the UI when needed
    private val bluetoothConnectPermissionRequest = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0)
    // Bluetooth audio manager - handles all BT/SCO orchestration
    private val bluetoothAudioManager by lazy { 
        com.dimadesu.lifestreamer.audio.BluetoothAudioManager(this, serviceScope, bluetoothConnectPermissionRequest) 
    }
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

        // Reset BT config to ensure clean state on service start
        // This prevents stale config from affecting passthrough
        com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setEnabled(false)
        com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.setPreferredDevice(null)
        Log.i(TAG, "Service onCreate: Reset BT config to default (disabled)")

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
                    if (_serviceStreamStatus.value == StreamStatus.STREAMING) {
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

        // Cleanup Bluetooth audio manager resources
        try {
            bluetoothAudioManager.cleanup()
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
                            
                            // Unlock stream rotation since streaming has stopped
                            unlockStreamRotation()
                            
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
        // Clear encoder stats flow so UI shows cleared state immediately
        try { _encoderStatsFlow.tryEmit(null) } catch (_: Throwable) {}
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

                    // Only read/emit current encoder bitrate and stats when streaming; otherwise clear them
                    val isStreamingNow = serviceStatus == StreamStatus.STREAMING
                    val videoEncoderRef = (streamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder
                    val videoBitrate = if (isStreamingNow) {
                        videoEncoderRef?.bitrate
                    } else null
                    // Emit bitrate (or null when not streaming) to flow for UI consumers
                    try { _currentBitrateFlow.emit(videoBitrate) } catch (_: Throwable) {}
                    
                    // Emit encoder stats (or null when not streaming) to flow for UI consumers
                    val encoderStatsText = if (isStreamingNow) {
                        try {
                            val stats = videoEncoderRef?.getStats()
                            stats?.let { s -> "%.1f fps".format(java.util.Locale.US, s.outputFps) }
                        } catch (_: Throwable) { null }
                    } else null
                    try { _encoderStatsFlow.emit(encoderStatsText) } catch (_: Throwable) {}
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

        // Only read bitrate and FPS when streaming
        val videoEncoderRef = if (status == StreamStatus.STREAMING) {
            (streamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder
        } else null
        val videoBitrate = videoEncoderRef?.bitrate
        val fpsText = try {
            videoEncoderRef?.getStats()?.let { s -> "%.1f fps".format(java.util.Locale.US, s.outputFps) }
        } catch (_: Throwable) { null }

        val bitrateText = videoBitrate?.let { b -> if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000.0) else String.format(java.util.Locale.US, "%d kb/s", b / 1000) } ?: ""

        val contentWithBitrate = if (status == StreamStatus.STREAMING) {
            val vb = videoBitrate?.let { b -> if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000.0) else String.format(java.util.Locale.US, "%d kb/s", b / 1000) } ?: ""
            // content already contains statusLabel when streaming (e.g., "Live • 00:01:23"),
            // so avoid appending the statusLabel again. Just add bitrate and FPS after whatever
            // content we've computed.
            val fpsAppend = fpsText?.let { " • $it" } ?: ""
            "$content • $vb$fpsAppend"
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

        val key = listOf(status.name, isCurrentlyMuted(), content, bitrateText, fpsText ?: "", statusLabel).joinToString("|")
        return Pair(notification, key)
    }

    // Decide the effective status using streamer immediate state when available,
    // otherwise fall back to the service-level status. Default to NOT_STREAMING
    // if neither is available.
    private fun getEffectiveServiceStatus(): StreamStatus {
        return try {
            val svcStatus = _serviceStreamStatus.value
            val reconnecting = _isReconnecting.value
            val streamingNow = streamer.isStreamingFlow.value
            
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
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
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
        return videoSource is com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
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
            // Lock stream rotation BEFORE starting to ensure consistent orientation
            // Detect current rotation and lock to it (same as PreviewViewModel.startStream())
            detectCurrentRotation()
            lockStreamRotation(currentRotation)
            Log.i(TAG, "startStreamFromConfiguredEndpoint: Pre-locked stream rotation to ${rotationToString(currentRotation)}")
            
            // Streamer is guaranteed to be available (lazy initialized)
            val currentStreamer = streamer

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
        // Expose SCO state flow for UI (delegated to BluetoothAudioManager)
        fun scoStateFlow() = this@CameraStreamerService.bluetoothAudioManager.scoStateFlow
        // Allow bound clients to enable/disable Bluetooth mic policy at runtime
        fun setUseBluetoothMic(enabled: Boolean) {
            try { this@CameraStreamerService.applyBluetoothPolicy(enabled) } catch (_: Throwable) {}
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
     * Trigger Bluetooth mic activation if BT toggle is ON.
     * Called from ViewModel when streaming starts with a camera source.
     */
    fun triggerBluetoothMicActivation() {
        bluetoothAudioManager.onStreamingStarted(streamer)
    }

    /**
     * Apply Bluetooth mic policy at runtime.
     * Delegates to BluetoothAudioManager for all orchestration.
     * If audio passthrough is running, restarts it to switch audio source.
     * 
     * Note: BT mic only applies when using mic-based audio, not MediaProjection.
     * 
     * @param enabled true to enable Bluetooth mic, false to disable
     */
    fun applyBluetoothPolicy(enabled: Boolean) {
        val isStreaming = streamer?.isStreamingFlow?.value == true
        val passthroughWasRunning = _isPassthroughRunning.value
        
        // Check if using mic-based audio (not MediaProjection) - BT mic only applies to mic-based audio
        val currentAudioSource = (streamer as? IWithAudioSource)?.audioInput?.sourceFlow?.value
        val isMediaProjectionAudio = currentAudioSource is IMediaProjectionSource
        
        // Only apply BT policy for streaming if using mic-based audio
        val effectiveIsStreaming = isStreaming && !isMediaProjectionAudio
        bluetoothAudioManager.applyPolicy(enabled, streamer, effectiveIsStreaming)
        
        if (isStreaming && isMediaProjectionAudio && enabled) {
            Log.i(TAG, "applyBluetoothPolicy: BT toggle ON but using MediaProjection audio - BT mic won't be used for streaming")
        }
        
        // If passthrough is running, restart it to switch between BT and built-in mic
        if (passthroughWasRunning) {
            // Cancel any ongoing restart to prevent race conditions
            passthroughRestartJob?.cancel()
            
            passthroughRestartJob = serviceScope.launch(Dispatchers.Default) {
                try {
                    Log.i(TAG, "Restarting passthrough for BT toggle change (enabled=$enabled)")
                    
                    // Stop current passthrough - ensure complete cleanup
                    audioPassthroughManager.stop()
                    _isPassthroughRunning.tryEmit(false)
                    bluetoothAudioManager.stopScoForPassthrough()
                    Log.i(TAG, "Stopped passthrough, waiting for complete cleanup")
                    
                    // Longer wait to ensure AudioRecord is fully released and communication device is cleared
                    delay(500)
                    
                    // Start fresh with new BT config (this can take up to 6s for SCO + verification)
                    val scoSuccess = bluetoothAudioManager.startScoForPassthrough()
                    
                    // Give Android extra time to fully update audio routing after SCO
                    if (scoSuccess) {
                        Log.i(TAG, "SCO established, waiting for audio routing to stabilize")
                        delay(500)
                    }
                    
                    // Configure passthrough with audio settings
                    val audioConfig = storageRepository.audioConfigFlow.first()
                    val audioSourceType = storageRepository.audioSourceTypeFlow.first()
                    
                    if (audioConfig != null) {
                        val passthroughConfig = com.dimadesu.lifestreamer.audio.AudioPassthroughConfig(
                            sampleRate = audioConfig.sampleRate,
                            channelConfig = audioConfig.channelConfig,
                            audioFormat = audioConfig.byteFormat,
                            audioSourceType = audioSourceType
                        )
                        audioPassthroughManager.setConfig(passthroughConfig)
                        Log.i(TAG, "Passthrough config: ${audioConfig.sampleRate}Hz, source=$audioSourceType")
                    }
                    
                    // Set preferred device for BT routing (or null for built-in mic)
                    val preferredDevice = if (scoSuccess) {
                        com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.getPreferredDevice()
                    } else {
                        null
                    }
                    audioPassthroughManager.setPreferredDevice(preferredDevice)
                    Log.i(TAG, "Passthrough preferred device: ${preferredDevice?.productName ?: "built-in"}")
                    
                    audioPassthroughManager.start()
                    _isPassthroughRunning.tryEmit(true)
                    Log.i(TAG, "Audio passthrough restarted after BT toggle (using ${if (scoSuccess) "BT mic" else "built-in mic"})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart passthrough after BT toggle: ${e.message}", e)
                    _isPassthroughRunning.tryEmit(false)
                }
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
     * Start audio passthrough - monitors microphone input and plays through speakers.
     * Delegates Bluetooth/SCO handling to BluetoothAudioManager.
     * Only uses BT if explicitly enabled via BluetoothAudioConfig.
     */
    fun startAudioPassthrough() {
        serviceScope.launch(Dispatchers.Default) {
            try {
                // Only try SCO for passthrough if BT is explicitly enabled
                // Check config state to ensure we don't accidentally use BT when toggle is off
                val btEnabled = com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.isEnabled()
                Log.i(TAG, "Starting audio passthrough (BT config enabled: $btEnabled)")
                
                val scoSuccess = if (btEnabled) {
                    bluetoothAudioManager.startScoForPassthrough()
                } else {
                    // Ensure audio mode is NORMAL and communication device is cleared when not using BT
                    // This prevents AudioRecord from routing to BT if mode/device was left set from previous operation
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        if (audioManager != null) {
                            // Stop any SCO that might be running - use API-appropriate method
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                // On Android S+, just clear communication device (no SCO to stop)
                                try {
                                    audioManager.clearCommunicationDevice()
                                    Log.i(TAG, "Cleared communication device (BT disabled, S+)")
                                } catch (_: Exception) {}
                            } else {
                                // On older versions, stop SCO explicitly
                                try { 
                                    @Suppress("DEPRECATION")
                                    audioManager.stopBluetoothSco() 
                                    Log.i(TAG, "Stopped Bluetooth SCO (BT disabled, pre-S)")
                                } catch (_: Exception) {}
                                // Also try to clear communication device via reflection
                                try {
                                    val method = audioManager::class.java.getMethod("clearCommunicationDevice")
                                    method.invoke(audioManager)
                                    Log.i(TAG, "Cleared communication device via reflection (BT disabled)")
                                } catch (_: Exception) {}
                            }
                            // Set mode to NORMAL
                            audioManager.mode = AudioManager.MODE_NORMAL
                            Log.i(TAG, "Set audio mode to NORMAL (BT disabled)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset audio routing: ${e.message}")
                    }
                    false
                }
                
                // Configure passthrough with audio settings
                val audioConfig = storageRepository.audioConfigFlow.first()
                val audioSourceType = storageRepository.audioSourceTypeFlow.first()
                
                if (audioConfig != null) {
                    val passthroughConfig = com.dimadesu.lifestreamer.audio.AudioPassthroughConfig(
                        sampleRate = audioConfig.sampleRate,
                        channelConfig = audioConfig.channelConfig,
                        audioFormat = audioConfig.byteFormat,
                        audioSourceType = audioSourceType
                    )
                    audioPassthroughManager.setConfig(passthroughConfig)
                    Log.i(TAG, "Audio passthrough config: ${audioConfig.sampleRate}Hz, ${if (audioConfig.channelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO) "STEREO" else "MONO"}, source=$audioSourceType")
                }

                // Set preferred device for BT routing (or null for built-in mic)
                val preferredDevice = if (scoSuccess) {
                    com.dimadesu.lifestreamer.audio.BluetoothAudioConfig.getPreferredDevice()
                } else {
                    null
                }
                audioPassthroughManager.setPreferredDevice(preferredDevice)
                Log.i(TAG, "Passthrough using: ${preferredDevice?.productName ?: "built-in mic"}")
                
                audioPassthroughManager.start()
                _isPassthroughRunning.tryEmit(true)
                Log.i(TAG, "Audio passthrough started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio passthrough: ${e.message}", e)
                _isPassthroughRunning.tryEmit(false)
            }
        }
    }

    /**
     * Stop audio passthrough.
     * Delegates Bluetooth/SCO cleanup to BluetoothAudioManager.
     */
    fun stopAudioPassthrough() {
        serviceScope.launch(Dispatchers.Default) {
            try {
                audioPassthroughManager.stop()
                _isPassthroughRunning.tryEmit(false)
                Log.i(TAG, "Audio passthrough stopped")
                
                // Stop SCO if started for passthrough
                bluetoothAudioManager.stopScoForPassthrough()
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
        _serviceStreamStatus.value = StreamStatus.NOT_STREAMING
    }
    
    /**
     * Update the stream status. Called by ViewModel to keep Service and notification in sync.
     */
    fun setStreamStatus(status: StreamStatus) {
        Log.d(TAG, "setStreamStatus: $status (isReconnecting=${_isReconnecting.value})")
        _serviceStreamStatus.value = status
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
}
