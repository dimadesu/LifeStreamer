/*
 * Copyright (C) 2021 Thibault B.
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
package com.dimadesu.lifestreamer.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureResult
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.databinding.Bindable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.dimadesu.lifestreamer.BR
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.data.rotation.RotationRepository
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.ui.main.usecases.BuildStreamerUseCase
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionHelper
import com.dimadesu.lifestreamer.utils.ObservableViewModel
import com.dimadesu.lifestreamer.utils.dataStore
import com.dimadesu.lifestreamer.utils.isEmpty
import com.dimadesu.lifestreamer.utils.setNextCameraId
import com.dimadesu.lifestreamer.utils.toggleBackToFront
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.IAudioRecordSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrameRateSupported
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionAudioSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSettings
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import com.dimadesu.lifestreamer.services.CameraStreamerService
import com.dimadesu.lifestreamer.bitrate.AdaptiveSrtBitrateRegulatorController
import com.dimadesu.lifestreamer.models.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)


class PreviewViewModel(private val application: Application) : ObservableViewModel() {
    private val storageRepository = DataStoreRepository(application, application.dataStore)
    private val rotationRepository = RotationRepository.getInstance(application)
    val mediaProjectionHelper = MediaProjectionHelper(application)
    private val buildStreamerUseCase = BuildStreamerUseCase(application, storageRepository)

    // Service binding for background streaming
    /**
     * Service reference for background streaming (using the service abstraction)
     */
    @SuppressLint("StaticFieldLeak")
    private var streamerService: CameraStreamerService? = null

    /**
     * Public getter for the service for foreground recovery
     */
    val service: CameraStreamerService? get() = streamerService

    /**
     * Current streamer instance from the service
     */
    var serviceStreamer: SingleStreamer? = null
        private set
    // If rotation changes while streaming we queue it here and apply it when streaming stops
    private var pendingTargetRotation: Int? = null
    private var serviceConnection: ServiceConnection? = null
    private val _serviceReady = MutableStateFlow(false)
    private val streamerFlow = MutableStateFlow<SingleStreamer?>(null)

    // UI-visible current bitrate string
    private val _bitrateLiveData = MutableLiveData<String?>()
    val bitrateLiveData: LiveData<String?> get() = _bitrateLiveData
    // Uptime string exposed by the service (e.g., "00:01:23")
    private val _uptimeLiveData = MutableLiveData<String?>(null)
    val uptimeLiveData: LiveData<String?> get() = _uptimeLiveData
    // Expose current mute state to the UI
    private val _isMutedLiveData = MutableLiveData<Boolean>(false)
    val isMutedLiveData: LiveData<Boolean> get() = _isMutedLiveData

    // Streamer access through service (with fallback for backward compatibility)
    val streamer: SingleStreamer?
        get() = serviceStreamer

    // Service readiness for UI binding
    val serviceReadyFlow = _serviceReady
    val streamerLiveData = serviceReadyFlow.map { ready ->
        if (ready) serviceStreamer else null
    }.asLiveData()

    /**
     * Test bitmap for [BitmapSource].
     */
    private val testBitmap =
        BitmapFactory.decodeResource(application.resources, R.drawable.img_test)

    /**
     * Camera settings.
     */
    val cameraSettings: CameraSettings?
        get() {
            val currentStreamer = serviceStreamer
            val videoSource = (currentStreamer as? IWithVideoSource)?.videoInput?.sourceFlow?.value
            return (videoSource as? ICameraSource)?.settings
        }

    val requiredPermissions: List<String>
        get() {
            val permissions = mutableListOf<String>()
            val currentStreamer = serviceStreamer
            if (currentStreamer?.videoInput?.sourceFlow is ICameraSource) {
                permissions.add(Manifest.permission.CAMERA)
            }
            if (currentStreamer?.audioInput?.sourceFlow?.value is IAudioRecordSource) {
                permissions.add(Manifest.permission.RECORD_AUDIO)
            }
            storageRepository.endpointDescriptorFlow.asLiveData().value?.let {
                if (it is UriMediaDescriptor) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            return permissions
        }

    /**
     * Determines if MediaProjection is required for the current streaming setup.
     * MediaProjection is needed when streaming from RTMP source for audio capture.
     */
    fun requiresMediaProjection(): Boolean {
        val currentVideoSource = serviceStreamer?.videoInput?.sourceFlow?.value
        // If video source is not a camera source, it's likely RTMP and needs MediaProjection for audio
        return currentVideoSource != null && currentVideoSource !is ICameraSource
    }

    // Streamer errors (nullable to support single-event pattern - cleared after observation)
    private val _streamerErrorLiveData: MutableLiveData<String?> = MutableLiveData()
    val streamerErrorLiveData: LiveData<String?> = _streamerErrorLiveData
    private val _endpointErrorLiveData: MutableLiveData<String?> = MutableLiveData()
    val endpointErrorLiveData: LiveData<String?> = _endpointErrorLiveData
    
    // Clear methods for single-event pattern (prevents re-showing errors on orientation change)
    fun clearStreamerError() {
        _streamerErrorLiveData.value = null
    }
    
    fun clearEndpointError() {
        _endpointErrorLiveData.value = null
    }

    // RTMP status for UI display
    private val _rtmpStatusLiveData: MutableLiveData<String?> = MutableLiveData()
    val rtmpStatusLiveData: LiveData<String?> = _rtmpStatusLiveData
    
    // Job to track RTMP retry loop - cancelled when switching back to camera
    private var rtmpRetryJob: kotlinx.coroutines.Job? = null
    
    // Track current RTMP ExoPlayer for monitoring disconnections
    private var currentRtmpPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var rtmpDisconnectListener: androidx.media3.common.Player.Listener? = null
    private var rtmpBufferingStartTime = 0L
    private var bufferingCheckJob: kotlinx.coroutines.Job? = null
    private var isHandlingDisconnection = false // Guard flag to prevent duplicate disconnection handling

    // Streamer states
    val isStreamingLiveData: LiveData<Boolean>
        get() = serviceReadyFlow.flatMapLatest { ready ->
            Log.d(TAG, "isStreamingLiveData: serviceReady = $ready, serviceStreamer = $serviceStreamer")
            if (ready && serviceStreamer != null) {
                val streamingFlow = serviceStreamer!!.isStreamingFlow
                Log.d(TAG, "isStreamingLiveData: using streamingFlow = $streamingFlow, current value = ${streamingFlow.value}")
                streamingFlow
            } else {
                // When service is not ready, we don't know the streaming state
                // Return false for now, but this will update once service reconnects
                Log.d(TAG, "isStreamingLiveData: service not ready, returning false (will update on reconnect)")
                kotlinx.coroutines.flow.flowOf(false)
            }
        }.asLiveData()
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    // Unified stream status for UI and notifications (shared enum)
    private val _streamStatus = MutableStateFlow(StreamStatus.NOT_STREAMING)
    val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    // Human-friendly status string for UI binding
    val streamStatusTextLiveData: LiveData<String> = _streamStatus.map { status ->
        when (status) {
            StreamStatus.NOT_STREAMING -> application.getString(R.string.status_not_streaming)
            StreamStatus.STARTING -> application.getString(R.string.status_starting)
            StreamStatus.CONNECTING -> application.getString(R.string.status_connecting)
            StreamStatus.STREAMING -> application.getString(R.string.status_streaming)
            StreamStatus.ERROR -> application.getString(R.string.status_error)
        }
    }.asLiveData()

    // MediaProjection session for streaming
    private var streamingMediaProjection: MediaProjection? = null

    init {
        // Bind to streaming service for background streaming capability
        bindToStreamerService()

        // Initialize LiveData flows
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady && serviceStreamer != null) {
                    Log.i(TAG, "Service ready and serviceStreamer available - initializing sources")
                    initializeStreamerSources()
                } else {
                    Log.i(TAG, "Service ready: $isReady, serviceStreamer: ${serviceStreamer != null}")
                }
            }
        }

        // Status-to-notification messaging removed; UI no longer shows sliding panel
    }

    /**
     * Helper functions to interact with streamer directly (service compatibility layer)
     */
    private suspend fun startServiceStreaming(descriptor: MediaDescriptor): Boolean {
        return try {
            Log.i(TAG, "startServiceStreaming: Opening streamer with descriptor: $descriptor")

            val currentStreamer = serviceStreamer
            if (currentStreamer == null) {
                Log.e(TAG, "startServiceStreaming: serviceStreamer is null!")
                _streamerErrorLiveData.postValue("Service streamer not available")
                return false
            }

            // Validate RTMP URL format
            val uri = descriptor.uri.toString()
            if (uri.startsWith("rtmp://")) {
                Log.i(TAG, "startServiceStreaming: Attempting RTMP connection to $uri")
                val host = uri.substringAfter("://").substringBefore("/")
                Log.i(TAG, "startServiceStreaming: RTMP host: $host")
            }

            Log.i(TAG, "startServiceStreaming: serviceStreamer available, calling open()...")

            // Add timeout to prevent hanging
            withTimeout(10000) { // 10 second timeout
                currentStreamer.open(descriptor)
            }
            Log.i(TAG, "startServiceStreaming: open() completed, calling startStream()...")
            currentStreamer.startStream()
            Log.i(TAG, "startServiceStreaming: Stream started successfully")
            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "startServiceStreaming failed: Timeout opening connection to ${descriptor.uri}")
            _streamerErrorLiveData.postValue("Connection timeout - check server address and network")
            false
        } catch (e: Exception) {
            Log.e(TAG, "startServiceStreaming failed: ${e.message}", e)
            _streamerErrorLiveData.postValue("Stream start failed: ${e.message}")
            false
        }
    }

    private suspend fun stopServiceStreaming(): Boolean {
        return try {
            Log.i(TAG, "stopServiceStreaming: Stopping stream...")
            serviceStreamer?.stopStream()
            Log.i(TAG, "stopServiceStreaming: Stream stopped successfully")

            // Stop the foreground service since streaming has ended
            val serviceIntent = Intent(application, CameraStreamerService::class.java)
            application.stopService(serviceIntent)
            Log.i(TAG, "stopServiceStreaming: Stopped CameraStreamerService foreground service")

            true
        } catch (e: Exception) {
            Log.e(TAG, "stopServiceStreaming failed: ${e.message}", e)
            false
        }
    }

    private fun setServiceAudioSource(audioSourceFactory: IAudioSourceInternal.Factory) {
        viewModelScope.launch {
            // Don't change audio source while streaming to avoid configuration conflicts
            if (serviceStreamer?.isStreamingFlow?.value == true) {
                Log.i(TAG, "Skipping audio source change - streamer is currently streaming")
                return@launch
            }
            serviceStreamer?.setAudioSource(audioSourceFactory)
        }
    }

    private fun setServiceVideoSource(videoSourceFactory: IVideoSourceInternal.Factory) {
        viewModelScope.launch {
            // Don't change video source while streaming to avoid configuration conflicts
            if (serviceStreamer?.isStreamingFlow?.value == true) {
                Log.i(TAG, "Skipping video source change - streamer is currently streaming")
                return@launch
            }
            serviceStreamer?.setVideoSource(videoSourceFactory)
        }
    }

    /**
     * Bind to the CameraStreamerService for background streaming.
     * This handles both starting a new service and reconnecting to an existing one.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun bindToStreamerService() {
        Log.i(TAG, "Binding to CameraStreamerService...")

        // Start the service explicitly so it runs independently of binding
        // If service is already running, this will just reconnect to it
        val serviceIntent = Intent(application, CameraStreamerService::class.java)
        application.startForegroundService(serviceIntent)
        Log.i(TAG, "Started/reconnected to CameraStreamerService as independent foreground service")

        // Create custom service connection to get both streamer and service
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder is CameraStreamerService.CameraStreamerServiceBinder) {
                    streamerService = binder.getService()
                    serviceStreamer = binder.streamer as SingleStreamer
                    // Collect bitrate flow from service binder if available
                    try {
                        val svc = binder.getService()
                        viewModelScope.launch {
                            svc.currentBitrateFlow.collect { bits ->
                                val text = bits?.let { b -> if (b >= 1_000_000) String.format("%.2f Mbps", b / 1_000_000.0) else String.format("%d kb/s", b / 1000) } ?: "-"
                                _bitrateLiveData.postValue(text)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to collect bitrate from service: ${t.message}")
                    }
                    streamerFlow.value = serviceStreamer
                    _serviceReady.value = true
                    // Collect service-provided stream status and map it to ViewModel status
                    try {
                        val svcStatusFlow = binder.serviceStreamStatus()
                        viewModelScope.launch {
                            svcStatusFlow.collect { svcStatus ->
                                try {
                                    // The service now publishes the shared StreamStatus enum; assign directly
                                    _streamStatus.value = svcStatus
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Failed to map service status: ${t.message}")
                                }
                            }
                        }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to collect service status: ${t.message}")
                        }
                        // Note: Critical errors are already handled via throwableFlow observer
                        // in observeStreamerFlows(). Don't collect criticalErrors here to avoid
                        // duplicate error dialogs when mid-stream errors occur.
                        // The service's onErrorNotification still updates the notification UI.
                        
                        // Collect isMuted flow to keep UI toggle in sync when mute is toggled via notification
                        try {
                            val isMutedFlow = binder.isMutedFlow()
                            viewModelScope.launch {
                                isMutedFlow.collect { muted ->
                                    try {
                                        _isMutedLiveData.postValue(muted)
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "Failed to post isMuted state: ${t.message}")
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to collect isMuted flow from service: ${t.message}")
                        }
                            // Collect uptime flow to display runtime in UI
                            try {
                                val uptimeFlow = binder.uptimeFlow()
                                viewModelScope.launch {
                                    uptimeFlow.collect { uptime ->
                                        try {
                                            _uptimeLiveData.postValue(uptime)
                                        } catch (t: Throwable) {
                                            Log.w(TAG, "Failed to post uptime state: ${t.message}")
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to collect uptime flow from service: ${t.message}")
                            }
                    Log.i(TAG, "CameraStreamerService connected and ready - streaming state: ${binder.streamer.isStreamingFlow.value}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "CameraStreamerService disconnected: $name")
                serviceStreamer = null
                streamerService = null
                streamerFlow.value = null
                _serviceReady.value = false
                // Ensure UI status is cleared when the service disconnects
                _streamStatus.value = StreamStatus.NOT_STREAMING
            }
        }

        // Use manual binding with custom connection - reuse the same intent
        application.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        serviceConnection = connection
    }

    /**
     * Initialize streamer sources after service is ready.
     * Only initializes if streamer is not already streaming to avoid configuration conflicts.
     */
    private suspend fun initializeStreamerSources() {
        val currentStreamer = serviceStreamer ?: return

        // Don't reinitialize sources if already streaming - this prevents configuration conflicts
        if (currentStreamer.isStreamingFlow.value == true) {
            Log.i(TAG, "Streamer is already streaming - skipping source initialization to avoid conflicts")
            observeStreamerFlows()
            return
        }

        Log.i(TAG, "Initializing streamer sources - Audio enabled: ${currentStreamer.withAudio}, Video enabled: ${currentStreamer.withVideo}")

        // Set audio source and video source only if not streaming
        if (currentStreamer.withAudio) {
            Log.i(TAG, "Audio source is enabled. Setting audio source")
            setServiceAudioSource(MicrophoneSourceFactory())
        } else {
            Log.i(TAG, "Audio source is disabled")
        }

        if (currentStreamer.withVideo) {
            if (ActivityCompat.checkSelfPermission(
                    application,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Camera permission granted, setting video source")
                setServiceVideoSource(CameraSourceFactory())
            } else {
                Log.w(TAG, "Camera permission not granted")
            }
        } else {
            Log.i(TAG, "Video source is disabled")
        }

        // Set up flow observers for the service-based streamer
        observeStreamerFlows()
    }

    /**
     * Set up flow observers for streamer state changes.
     */
    private fun observeStreamerFlows() {
        val currentStreamer = serviceStreamer ?: return

        viewModelScope.launch {
            currentStreamer.videoInput?.sourceFlow?.collect {
                notifySourceChanged()
            }
        }

        viewModelScope.launch {
            currentStreamer.throwableFlow.filterNotNull().filter { !it.isClosedException }
                .map { "${it.javaClass.simpleName}: ${it.message}" }.collect {
                    _streamerErrorLiveData.postValue(it)
                }
        }

        viewModelScope.launch {
            currentStreamer.throwableFlow.filterNotNull().filter { it.isClosedException }
                .map { "Connection lost: ${it.message}" }.collect {
                    _endpointErrorLiveData.postValue(it)
                }
        }
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isOpenFlow?.collect {
                        Log.i(TAG, "Streamer is opened: $it")
                    }
                }
            }
        }
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isStreamingFlow?.collect { isStreaming ->
                        Log.i(TAG, "Streamer is streaming: $isStreaming")
                        // Keep UI status in-sync with actual streamer state. When the streamer
                        // reports it's streaming, show STREAMING; otherwise revert to NOT_STREAMING.
                        if (isStreaming) {
                            _streamStatus.value = StreamStatus.STREAMING
                        } else {
                            _streamStatus.value = StreamStatus.NOT_STREAMING
                        }
                    }
                }
            }
        }
        // Clear bitrate display when streaming stops
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isStreamingFlow?.collect { isStreaming ->
                        if (!isStreaming) {
                            Log.i(TAG, "Streamer stopped - clearing bitrate display")
                            _bitrateLiveData.postValue(null)
                        }
                    }
                }
            }
        }
        // Apply rotation changes, but if streamer is currently streaming queue the change and
        // apply it when streaming stops to avoid conflicts in encoding pipeline.
        viewModelScope.launch {
            rotationRepository.rotationFlow.collect { rotation ->
                val current = serviceStreamer
                if (current?.isStreamingFlow?.value == true) {
                    Log.i(TAG, "Rotation change to $rotation queued until stream stops")
                    pendingTargetRotation = rotation
                } else {
                    try {
                        current?.setTargetRotation(rotation)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to set target rotation: $t")
                    }
                }
            }
        }

        // When streaming stops, apply any pending rotation change.
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isStreamingFlow?.collect { isStreaming ->
                        if (!isStreaming) {
                            pendingTargetRotation?.let { pending ->
                                Log.i(TAG, "Applying pending rotation $pending after stream stopped")
                                try {
                                    serviceStreamer?.setTargetRotation(pending)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Failed to apply pending rotation: $t")
                                }
                                pendingTargetRotation = null
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            storageRepository.isAudioEnableFlow.combine(storageRepository.isVideoEnableFlow) { isAudioEnable, isVideoEnable ->
                Pair(isAudioEnable, isVideoEnable)
            }.drop(1).collect { (_, _) ->
                val previousStreamer = streamer
                streamerFlow.emit(buildStreamerUseCase(previousStreamer))
                if (previousStreamer != streamer) {
                    previousStreamer?.release()
                }
            }
        }
        viewModelScope.launch {
            storageRepository.audioConfigFlow
                .collect { config ->
                    // Don't change audio config while streaming to avoid configuration conflicts
                    if (serviceStreamer?.isStreamingFlow?.value == true) {
                        Log.i(TAG, "Skipping audio config change - streamer is currently streaming")
                        return@collect
                    }

                    if (ActivityCompat.checkSelfPermission(
                            application,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        config?.let {
                            serviceStreamer?.setAudioConfig(it)
                        } ?: Log.i(TAG, "Audio is disabled")
                    }
                }
        }
        viewModelScope.launch {
            storageRepository.videoConfigFlow
                .collect { config ->
                    // Don't change video config while streaming to avoid configuration conflicts
                    if (serviceStreamer?.isStreamingFlow?.value == true) {
                        Log.i(TAG, "Skipping video config change - streamer is currently streaming")
                        return@collect
                    }

                    config?.let {
                        serviceStreamer?.setVideoConfig(it)
                    } ?: Log.i(TAG, "Video is disabled")
                }
        }
    }

    fun onZoomRationOnPinchChanged() {
        notifyPropertyChanged(BR.zoomRatio)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initializeVideoSource() {
        viewModelScope.launch {
            val currentStreamer = serviceStreamer
            if (currentStreamer?.videoInput?.sourceFlow?.value == null) {
                currentStreamer?.setVideoSource(CameraSourceFactory())
            } else {
                Log.i(TAG, "Camera source already set")
            }
        }
    }

    fun startStream() {
        viewModelScope.launch {
            _streamStatus.value = StreamStatus.STARTING
            Log.i(TAG, "startStream() called")
            val currentStreamer = serviceStreamer
            val serviceReady = _serviceReady.value

            Log.i(TAG, "startStream: serviceStreamer = $currentStreamer, serviceReady = $serviceReady")

            if (currentStreamer == null) {
                Log.w(TAG, "Service streamer not ready, cannot start stream")
                _streamerErrorLiveData.postValue("Streaming service not ready")
                return@launch
            }

            // Check if sources are configured
            val hasVideoSource = currentStreamer.videoInput?.sourceFlow?.value != null
            val hasAudioSource = currentStreamer.audioInput?.sourceFlow?.value != null
            Log.i(TAG, "startStream: hasVideoSource = $hasVideoSource, hasAudioSource = $hasAudioSource")

            if (!hasVideoSource) {
                Log.w(TAG, "Video source not configured, initializing...")
                // Try to initialize sources before streaming
                initializeStreamerSources()
                // Small delay to let initialization complete
                kotlinx.coroutines.delay(500)
            }

            _isTryingConnectionLiveData.postValue(true)
            _streamStatus.value = StreamStatus.CONNECTING
            try {
                val descriptor = storageRepository.endpointDescriptorFlow.first()
                Log.i(TAG, "Starting stream with descriptor: $descriptor")
                val success = startServiceStreaming(descriptor)
                if (!success) {
                    Log.e(TAG, "Stream start failed - startServiceStreaming returned false")
                    // Error already posted to _streamerErrorLiveData by startServiceStreaming
                    // Don't post duplicate error
                    _streamStatus.value = StreamStatus.ERROR
                    return@launch
                }
                Log.i(TAG, "Stream started successfully")
                _streamStatus.value = StreamStatus.STREAMING

                if (descriptor.type.sinkType == MediaSinkType.SRT) {
                    val bitrateRegulatorConfig =
                        storageRepository.bitrateRegulatorConfigFlow.first()
                    if (bitrateRegulatorConfig != null) {
                        Log.i(TAG, "Add Moblin SrtFight bitrate regulator controller")
                        // Read user preference for regulator mode (fast/slow/belabox)
                        val selectedMode = storageRepository.regulatorModeFlow.first()
                        streamer?.addBitrateRegulatorController(
                            AdaptiveSrtBitrateRegulatorController.Factory(
                                bitrateRegulatorConfig = bitrateRegulatorConfig,
                                mode = selectedMode
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startStream failed", e)
                _streamerErrorLiveData.postValue("startStream: ${e.message ?: "Unknown error"}")
                _streamStatus.value = StreamStatus.ERROR
            } finally {
                _isTryingConnectionLiveData.postValue(false)
                if (_streamStatus.value != StreamStatus.STREAMING) {
                    _streamStatus.value = StreamStatus.NOT_STREAMING
                }
            }
        }
    }

    /**
     * Start streaming with MediaProjection support.
     * Request MediaProjection permission and keep it active during streaming.
     */
    fun startStreamWithMediaProjection(
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        _isTryingConnectionLiveData.postValue(true)
        _streamStatus.value = StreamStatus.STARTING

        mediaProjectionHelper.requestProjection(mediaProjectionLauncher) { mediaProjection ->
            Log.i(TAG, "MediaProjection callback received - mediaProjection: ${if (mediaProjection != null) "SUCCESS" else "NULL"}")
            if (mediaProjection != null) {
                streamingMediaProjection = mediaProjection
                Log.i(TAG, "MediaProjection acquired for streaming session - starting setup...")

                viewModelScope.launch {
                    try {
                        Log.i(TAG, "About to check video source for audio setup...")
                        // Check if we're on RTMP source - only use MediaProjection audio for RTMP
                        val currentVideoSource = serviceStreamer?.videoInput?.sourceFlow?.value
                        Log.i(TAG, "Current video source: $currentVideoSource (isICameraSource: ${currentVideoSource is ICameraSource})")
                        if (currentVideoSource !is ICameraSource) {
                            // We're on RTMP source - use MediaProjection for audio capture
                            Log.i(TAG, "RTMP source detected - setting up MediaProjection audio capture")
                            try {
                                setServiceAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
                                Log.i(TAG, "MediaProjection audio source configured for RTMP streaming")
                            } catch (audioError: Exception) {
                                Log.w(TAG, "MediaProjection audio setup failed, falling back to microphone: ${audioError.message}")
                                // Fallback to microphone if MediaProjection audio fails
                                setServiceAudioSource(MicrophoneSourceFactory())
                            }
                        } else {
                            // We're on Camera source - use microphone for audio
                            Log.i(TAG, "Camera source detected - using microphone for audio")
                            setServiceAudioSource(MicrophoneSourceFactory())
                        }

                        // Start the actual stream
                        _streamStatus.value = StreamStatus.CONNECTING
                        startStreamInternal(onSuccess, onError)
                    } catch (e: Exception) {
                        _isTryingConnectionLiveData.postValue(false)
                        val error = "Failed to configure MediaProjection audio: ${e.message}"
                        Log.e(TAG, error, e)
                        _streamerErrorLiveData.postValue(error)
                        _streamStatus.value = StreamStatus.ERROR
                        onError(error)  // Also call callback for custom handling if needed
                    }
                }
            } else {
                _isTryingConnectionLiveData.postValue(false)
                val error = "MediaProjection permission required for streaming"
                Log.e(TAG, error)
                _streamerErrorLiveData.postValue(error)
                _streamStatus.value = StreamStatus.ERROR
                onError(error)  // Also call callback for custom handling if needed
            }
        }
    }

    private fun startStreamInternal(onSuccess: () -> Unit, onError: (String) -> Unit) {
        Log.i(TAG, "startStreamInternal called - beginning setup...")
        viewModelScope.launch {
            try {
                val descriptor = storageRepository.endpointDescriptorFlow.first()
                Log.i(TAG, "Starting stream with descriptor: $descriptor")
                Log.i(TAG, "About to call startServiceStreaming()...")
                val success = startServiceStreaming(descriptor)
                
                if (!success) {
                    Log.e(TAG, "startServiceStreaming() returned false - stream start failed")
                    // Error already posted to _streamerErrorLiveData by startServiceStreaming
                    // Don't call onError to avoid double error dialogs
                    _streamStatus.value = StreamStatus.ERROR
                    return@launch
                }
                
                Log.i(TAG, "startServiceStreaming() completed successfully")
                Log.i(TAG, "Stream setup completed successfully, calling onSuccess()")
                _streamStatus.value = StreamStatus.STREAMING
                onSuccess()
            } catch (e: Throwable) {
                val error = "Stream start failed: ${e.message ?: "Unknown error"}"
                Log.e(TAG, "STREAM START EXCEPTION: $error", e)
                // Only call onError for unexpected exceptions not already handled
                onError(error)
                _streamStatus.value = StreamStatus.ERROR
            } finally {
                Log.i(TAG, "startStreamInternal finally block - setting isTryingConnection to false")
                _isTryingConnectionLiveData.postValue(false)
            }
        }
    }

    fun stopStream() {
        viewModelScope.launch {
            try {
                val currentStreamer = serviceStreamer

                if (currentStreamer == null) {
                    Log.w(TAG, "Service streamer not ready, cannot stop stream")
                    return@launch
                }

                val currentStreamingState = currentStreamer.isStreamingFlow.value
                Log.i(TAG, "stopStream() called - Current streaming state: $currentStreamingState")

                // If already stopped, don't do anything
                if (currentStreamingState != true) {
                    Log.i(TAG, "Stream is already stopped, skipping stop sequence")
                    _isTryingConnectionLiveData.postValue(false)
                    return@launch
                }

                Log.i(TAG, "Stopping stream...")

                // Release MediaProjection FIRST to interrupt any ongoing capture
                streamingMediaProjection?.let { mediaProjection ->
                    mediaProjection.stop()
                    Log.i(TAG, "MediaProjection stopped")
                }
                streamingMediaProjection = null

                // Stop streaming via helper method
                try {
                    stopServiceStreaming()
                    Log.i(TAG, "Stream stop command sent")
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping stream: ${e.message}", e)
                }

                // Remove bitrate regulator
                try {
                    currentStreamer.removeBitrateRegulatorController()
                    Log.i(TAG, "Bitrate regulator removed")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove bitrate regulator: ${e.message}")
                }

                // Reset audio source to clean state
                try {
                    val currentVideoSource = currentStreamer.videoInput?.sourceFlow?.value
                    if (currentVideoSource !is ICameraSource) {
                        // We're on RTMP source - reset audio to microphone for clean state
                        Log.i(TAG, "RTMP source detected after stop - resetting audio to microphone")
                        setServiceAudioSource(MicrophoneSourceFactory())
                    } else {
                        // Camera source - ensure microphone is set
                        Log.i(TAG, "Camera source detected after stop - ensuring microphone audio")
                        setServiceAudioSource(MicrophoneSourceFactory())
                    }
                    Log.i(TAG, "Audio source reset to microphone after stream stop")
                } catch (e: Exception) {
                    Log.w(TAG, "Error resetting audio source after stop: ${e.message}", e)
                }

                Log.i(TAG, "Stream stop completed successfully")

            } catch (e: Throwable) {
                Log.e(TAG, "stopStream failed", e)
                // Force clear state
                streamingMediaProjection?.stop()
                streamingMediaProjection = null
            } finally {
                _isTryingConnectionLiveData.postValue(false)
                // Make sure UI status is cleared after stop routine finishes
                _streamStatus.value = StreamStatus.NOT_STREAMING
            }
        }
    }

    fun setMute(isMuted: Boolean) {
        // Perform mute operations off the main thread to avoid blocking UI.
        viewModelScope.launch(Dispatchers.Default) {
            // Prefer calling the bound service to centralize mutation and notification updates
            val svc = streamerService
            if (svc != null) {
                try {
                    svc.setMuted(isMuted)
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to set mute via service: ${t.message}")
                }
            }

            // Fallback: directly write to streamer audio input for backward compatibility
            try {
                streamer?.audioInput?.isMuted = isMuted
                // Ensure UI reflects the change immediately even if service wasn't bound
                _isMutedLiveData.postValue(isMuted)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set mute directly on streamer: ${t.message}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun switchBackToFront(): Boolean {
        /**
         * If video frame rate is not supported by the new camera, streamer will throw an
         * exception instead of crashing. You can either catch the exception or check if the
         * configuration is valid for the new camera with [Context.isFrameRateSupported].
         */
        val videoSource = streamer?.videoInput?.sourceFlow?.value
        if (videoSource is ICameraSource) {
            viewModelScope.launch {
                streamer?.toggleBackToFront(application)
            }
        }
        return true
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleCamera() {
        /**
         * If video frame rate is not supported by the new camera, streamer will throw an
         * exception instead of crashing. You can either catch the exception or check if the
         * configuration is valid for the new camera with [Context.isFrameRateSupported].
         */
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "Streamer service not available for camera toggle")
            _streamerErrorLiveData.postValue("Service not available")
            return
        }

        val videoSource = currentStreamer.videoInput?.sourceFlow?.value
        if (videoSource is ICameraSource) {
            viewModelScope.launch {
                try {
                    currentStreamer.setNextCameraId(application)
                    Log.i(TAG, "Camera toggled successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle camera", e)
                    _streamerErrorLiveData.postValue("Camera toggle failed: ${e.message}")
                }
            }
        } else {
            Log.w(TAG, "Video source is not a camera source, cannot toggle")
        }
    }
    
    /**
     * Monitor RTMP ExoPlayer for disconnections and automatically fallback + retry
     */
    private fun monitorRtmpConnection(player: androidx.media3.exoplayer.ExoPlayer) {
        // Remove any previous listener
        rtmpDisconnectListener?.let { listener ->
            try { currentRtmpPlayer?.removeListener(listener) } catch (_: Exception) {}
        }
        
        // Cancel any pending buffering check
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
        rtmpBufferingStartTime = 0L
        
        currentRtmpPlayer = player
        
        val maxBufferingDuration = 2_000L // 2 seconds of buffering = disconnection
        
        rtmpDisconnectListener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "RTMP stream error detected: ${error.message}", error)
                bufferingCheckJob?.cancel()
                handleRtmpDisconnection()
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "RTMP playback state changed to: $playbackState")
                when (playbackState) {
                    androidx.media3.common.Player.STATE_ENDED -> {
                        Log.w(TAG, "RTMP stream ended")
                        bufferingCheckJob?.cancel()
                        handleRtmpDisconnection()
                    }
                    androidx.media3.common.Player.STATE_IDLE -> {
                        Log.w(TAG, "RTMP stream went idle (unexpected stop)")
                        bufferingCheckJob?.cancel()
                        handleRtmpDisconnection()
                    }
                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        if (rtmpBufferingStartTime == 0L) {
                            rtmpBufferingStartTime = System.currentTimeMillis()
                            Log.d(TAG, "RTMP stream started buffering")
                            
                            // Check if buffering takes too long
                            bufferingCheckJob = viewModelScope.launch {
                                delay(maxBufferingDuration)
                                if (rtmpBufferingStartTime > 0 && player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                                    Log.w(TAG, "RTMP stream buffering for too long (${maxBufferingDuration}ms) - treating as disconnection")
                                    handleRtmpDisconnection()
                                }
                            }
                        }
                    }
                    androidx.media3.common.Player.STATE_READY -> {
                        if (rtmpBufferingStartTime > 0L) {
                            val bufferingDuration = System.currentTimeMillis() - rtmpBufferingStartTime
                            Log.d(TAG, "RTMP stream recovered from buffering after ${bufferingDuration}ms")
                            rtmpBufferingStartTime = 0L
                            bufferingCheckJob?.cancel()
                        }
                    }
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "RTMP isPlaying changed to: $isPlaying, state=${player.playbackState}")
                if (!isPlaying && player.playbackState == androidx.media3.common.Player.STATE_READY) {
                    // Stream stopped playing but is in ready state - might indicate disconnection
                    Log.d(TAG, "RTMP stream not playing despite being ready - monitoring")
                }
            }
        }
        
        player.addListener(rtmpDisconnectListener!!)
        Log.i(TAG, "Started monitoring RTMP connection for disconnections (including buffering stalls)")
    }
    
    /**
     * Handle RTMP disconnection by falling back to bitmap and restarting retry
     */
    private fun handleRtmpDisconnection() {
        val currentStreamer = serviceStreamer ?: return
        
        // Guard against duplicate disconnection handling
        if (isHandlingDisconnection) {
            Log.d(TAG, "Already handling RTMP disconnection, ignoring duplicate call")
            return
        }
        
        Log.i(TAG, "Handling RTMP disconnection - falling back to bitmap and retrying")
        isHandlingDisconnection = true
        
        viewModelScope.launch {
            try {
                // Cancel existing retry job if any
                rtmpRetryJob?.cancel()
                
                // Fallback to bitmap immediately - this will properly release the RTMPVideoSource
                // which cleans up the surface processor before we release the ExoPlayer
                RtmpSourceSwitchHelper.switchToBitmapFallback(currentStreamer, testBitmap)
                
                // Small delay to let the video source release complete and surface processor cleanup
                delay(100)
                
                // Now release the old ExoPlayer to prevent multiple instances playing simultaneously
                // (which causes audio echo when captured by MediaProjection)
                currentRtmpPlayer?.let { player ->
                    try {
                        Log.i(TAG, "Releasing old RTMP ExoPlayer to prevent audio echo")
                        player.stop()
                        player.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error releasing old ExoPlayer: ${e.message}")
                    }
                }
                currentRtmpPlayer = null
                
                // Fallback audio to microphone
                try {
                    currentStreamer.setAudioSource(io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory())
                    Log.i(TAG, "Switched audio to microphone on RTMP disconnection")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to switch audio to microphone: ${e.message}")
                }
                
                // Start retry loop
                rtmpRetryJob = RtmpSourceSwitchHelper.switchToRtmpSource(
                    application = application,
                    currentStreamer = currentStreamer,
                    testBitmap = testBitmap,
                    storageRepository = storageRepository,
                    mediaProjectionHelper = mediaProjectionHelper,
                    streamingMediaProjection = streamingMediaProjection,
                    postError = { msg -> _streamerErrorLiveData.postValue(msg) },
                    postRtmpStatus = { msg -> _rtmpStatusLiveData.postValue(msg) },
                    onRtmpConnected = { player -> 
                        monitorRtmpConnection(player)
                        // Reset guard flag when successfully reconnected
                        isHandlingDisconnection = false
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error handling RTMP disconnection: ${e.message}", e)
                isHandlingDisconnection = false // Reset flag on error
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleVideoSource(mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null) {
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "Streamer service not available for video source toggle")
            _streamerErrorLiveData.postValue("Service not available")
            return
        }

    val videoSource = currentStreamer.videoInput?.sourceFlow?.value
    // Prefer the streamer's own state flow for an up-to-date streaming state.
    val isCurrentlyStreaming = currentStreamer.isStreamingFlow.value == true

        viewModelScope.launch {
            when (videoSource) {
                is ICameraSource -> {
                    Log.i(TAG, "Switching from Camera to RTMP source (streaming: $isCurrentlyStreaming)")

                    // Only request MediaProjection when switching to RTMP while streaming.
                    // Requesting projection while not streaming leads to poor UX (unexpected
                    // permission prompts). If not streaming, skip the request — audio can be
                    // upgraded later when the user starts streaming or explicitly requests it.
                    val needProjection = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                            && isCurrentlyStreaming
                            && streamingMediaProjection == null
                            && mediaProjectionHelper.getMediaProjection() == null

                    if (needProjection) {
                        if (mediaProjectionLauncher != null) {
                            Log.i(TAG, "Requesting MediaProjection permission (audio) while switching video to RTMP during active stream")
                            mediaProjectionHelper.requestProjection(mediaProjectionLauncher) { projection ->
                                Log.i(TAG, "MediaProjection callback received during RTMP switch: ${projection != null}")
                                viewModelScope.launch {
                                    streamingMediaProjection = projection
                                    if (projection == null) {
                                        _streamerErrorLiveData.postValue("MediaProjection permission required to use RTMP audio")
                                        return@launch
                                    }

                                    // Now that we have projection, perform the RTMP source switch
                                    // Cancel any existing retry job first
                                    rtmpRetryJob?.cancel()
                                    rtmpRetryJob = RtmpSourceSwitchHelper.switchToRtmpSource(
                                        application = application,
                                        currentStreamer = currentStreamer,
                                        testBitmap = testBitmap,
                                        storageRepository = storageRepository,
                                        mediaProjectionHelper = mediaProjectionHelper,
                                        streamingMediaProjection = streamingMediaProjection,
                                        postError = { msg -> _streamerErrorLiveData.postValue(msg) },
                                        postRtmpStatus = { msg -> _rtmpStatusLiveData.postValue(msg) },
                                        onRtmpConnected = { player -> monitorRtmpConnection(player) }
                                    )
                                }
                            }
                            // Return early; the actual switch will happen in the projection callback
                            return@launch
                        } else {
                            Log.w(TAG, "MediaProjection required but no launcher available to request it")
                            _streamerErrorLiveData.postValue("MediaProjection permission required to use RTMP audio")
                            return@launch
                        }
                    } else {
                        if (!isCurrentlyStreaming) {
                            Log.i(TAG, "Not requesting MediaProjection because stream is not active; will request when starting stream if needed")
                        }

                        // Cancel any existing retry job first
                        rtmpRetryJob?.cancel()
                        rtmpRetryJob = RtmpSourceSwitchHelper.switchToRtmpSource(
                            application = application,
                            currentStreamer = currentStreamer,
                            testBitmap = testBitmap,
                            storageRepository = storageRepository,
                            mediaProjectionHelper = mediaProjectionHelper,
                            streamingMediaProjection = streamingMediaProjection,
                            postError = { msg -> _streamerErrorLiveData.postValue(msg) },
                            postRtmpStatus = { msg -> _rtmpStatusLiveData.postValue(msg) },
                            onRtmpConnected = { player -> monitorRtmpConnection(player) }
                        )
                    }
                }
                else -> {
                    Log.i(TAG, "Switching from RTMP back to Camera source (streaming: $isCurrentlyStreaming)")

                    // Clear RTMP status message FIRST before cancelling job
                    // (job might be in middle of delay showing error message)
                    // Use setValue for immediate effect since we're on main thread
                    _rtmpStatusLiveData.value = null

                    // Cancel any ongoing RTMP retry loop and reset disconnection flag
                    rtmpRetryJob?.cancel()
                    rtmpRetryJob = null
                    isHandlingDisconnection = false // Reset flag when cancelling retry
                    Log.i(TAG, "Cancelled RTMP retry loop and reset disconnection flag")
                    
                    // Cancel buffering check
                    bufferingCheckJob?.cancel()
                    bufferingCheckJob = null
                    rtmpBufferingStartTime = 0L
                    
                    // Stop monitoring RTMP connection
                    rtmpDisconnectListener?.let { listener ->
                        try {
                            currentRtmpPlayer?.removeListener(listener)
                            Log.i(TAG, "Removed RTMP disconnect listener")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to remove RTMP listener: ${e.message}")
                        }
                    }
                    rtmpDisconnectListener = null
                    currentRtmpPlayer = null

                    // Don't release streaming MediaProjection here - it's managed by stream lifecycle
                    if (streamingMediaProjection == null) {
                        mediaProjectionHelper.release()
                    }

                    // Switch to camera source
                    currentStreamer.setVideoSource(CameraSourceFactory())
                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
                }
            }
//            when (videoSource) {
//                is ICameraSource -> {
//                    Log.i(TAG, "Switching from Camera to Bitmap source (streaming: $isCurrentlyStreaming)")
//
//                    // If we're currently streaming, temporarily stop to prepare for source switch
//                    var wasStreaming = false
//                    if (isCurrentlyStreaming) {
//                        Log.i(TAG, "Temporarily stopping camera stream for source switch")
//                        wasStreaming = true
//                        try {
//                            stopServiceStreaming()
//                            // Brief delay to ensure clean stop
//                            kotlinx.coroutines.delay(100)
//                        } catch (e: Exception) {
//                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
//                        }
//                    }
//
//                    // Switch to bitmap source
//                    currentStreamer.setVideoSource(BitmapSourceFactory(testBitmap))
//
//                    // If we were streaming before, restart with bitmap source
//                    if (wasStreaming) {
//                        Log.i(TAG, "Restarting stream with bitmap source")
//                        try {
//                            // Small delay to let bitmap source initialize
//                            kotlinx.coroutines.delay(300)
//                            val descriptor = storageRepository.endpointDescriptorFlow.first()
//                            startServiceStreaming(descriptor)
//                        } catch (e: Exception) {
//                            Log.e(TAG, "Error restarting stream with bitmap: ${e.message}")
//                            _streamerErrorLiveData.postValue("Failed to restart stream with bitmap: ${e.message}")
//                        }
//                    }
//                }
//                else -> {
//                    Log.i(TAG, "Switching from Bitmap back to Camera source (streaming: $isCurrentlyStreaming)")
//
//                    // If we're currently streaming, we need to stop the current source first
//                    var wasStreaming = false
//                    if (isCurrentlyStreaming) {
//                        Log.i(TAG, "Stopping bitmap streaming before switch")
//                        wasStreaming = true
//                        try {
//                            stopServiceStreaming()
//                            // Small delay to ensure stream stops properly
//                            kotlinx.coroutines.delay(100)
//                        } catch (e: Exception) {
//                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
//                        }
//                    }
//
//                    // Switch to camera source
//                    currentStreamer.setVideoSource(CameraSourceFactory())
//                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
//
//                    // If we were streaming before, restart with camera
//                    if (wasStreaming) {
//                        Log.i(TAG, "Restarting stream with camera source")
//                        try {
//                            // Small delay to let camera source initialize
//                            kotlinx.coroutines.delay(200)
//                            val descriptor = storageRepository.endpointDescriptorFlow.first()
//                            startServiceStreaming(descriptor)
//                        } catch (e: Exception) {
//                            Log.e(TAG, "Error restarting stream with camera: ${e.message}")
//                            _streamerErrorLiveData.postValue("Failed to restart stream with camera: ${e.message}")
//                        }
//                    }
//                }
//            }
            Log.i(TAG, "Switch video source completed")
        }
    }

    val isCameraSource: LiveData<Boolean>
        get() = serviceReadyFlow.flatMapLatest { ready ->
            if (ready && serviceStreamer != null) {
                Log.d(TAG, "isCameraSource: serviceStreamer available, checking video source")
                serviceStreamer!!.videoInput?.sourceFlow?.map { source ->
                    val isCam = source is ICameraSource
                    Log.d(TAG, "isCameraSource: video source = $source, isCameraSource = $isCam")
                    isCam
                } ?: kotlinx.coroutines.flow.flowOf(false)
            } else {
                Log.d(TAG, "isCameraSource: service not ready or serviceStreamer null, returning false")
                kotlinx.coroutines.flow.flowOf(false)
            }
        }.asLiveData()

    val isFlashAvailable = MutableLiveData(false)
    fun toggleFlash() {
        cameraSettings?.let {
            try {
                it.flash.enable = !it.flash.enable
            } catch (t: Throwable) {
                Log.w(TAG, "toggleFlash failed (camera session may be closed): ${t.message}")
            }
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    val isAutoWhiteBalanceAvailable = MutableLiveData(false)
    fun toggleAutoWhiteBalanceMode() {
        cameraSettings?.let { settings ->
            try {
                val awbModes = settings.whiteBalance.availableAutoModes
                val index = awbModes.indexOf(settings.whiteBalance.autoMode)
                settings.whiteBalance.autoMode = awbModes[(index + 1) % awbModes.size]
            } catch (t: Throwable) {
                Log.w(TAG, "toggleAutoWhiteBalanceMode failed (camera session may be closed): ${t.message}")
            }
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    val showExposureSlider = MutableLiveData(false)
    fun toggleExposureSlider() {
        showExposureSlider.postValue(!(showExposureSlider.value)!!)
    }

    val isExposureCompensationAvailable = MutableLiveData(false)
    val exposureCompensationRange = MutableLiveData<Range<Int>>()
    val exposureCompensationStep = MutableLiveData<Rational>()
    var exposureCompensation: Float
        @Bindable get() {
            val settings = cameraSettings
            return if (settings != null && settings.isAvailableFlow.value) {
                settings.exposure.compensation * settings.exposure.availableCompensationStep.toFloat()
            } else {
                0f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                try {
                    settings.exposure.let {
                        if (settings.isAvailableFlow.value) {
                            it.compensation = (value / it.availableCompensationStep.toFloat()).toInt()
                        }
                        notifyPropertyChanged(BR.exposureCompensation)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Setting exposure failed (camera session may be closed): ${t.message}")
                }
            } ?: Log.e(TAG, "Camera settings is not accessible")
        }

    val showZoomSlider = MutableLiveData(false)
    fun toggleZoomSlider() {
        showZoomSlider.postValue(!(showZoomSlider.value)!!)
    }

    val isZoomAvailable = MutableLiveData(false)
    val zoomRatioRange = MutableLiveData<Range<Float>>()
    var zoomRatio: Float
        @Bindable get() {
            val settings = cameraSettings
            return if (settings != null && settings.isAvailableFlow.value) {
                settings.zoom.zoomRatio
            } else {
                1f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                try {
                    if (settings.isAvailableFlow.value) {
                        settings.zoom.zoomRatio = value
                    }
                    notifyPropertyChanged(BR.zoomRatio)
                } catch (t: Throwable) {
                    Log.w(TAG, "Setting zoom failed (camera session may be closed): ${t.message}")
                }
            } ?: Log.e(TAG, "Camera settings is not accessible")
        }

    val isAutoFocusModeAvailable = MutableLiveData(false)
    fun toggleAutoFocusMode() {
        cameraSettings?.let {
            try {
                val afModes = it.focus.availableAutoModes
                val index = afModes.indexOf(it.focus.autoMode)
                it.focus.autoMode = afModes[(index + 1) % afModes.size]
                if (it.focus.autoMode == CaptureResult.CONTROL_AF_MODE_OFF) {
                    showLensDistanceSlider.postValue(true)
                } else {
                    showLensDistanceSlider.postValue(false)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "toggleAutoFocusMode failed (camera session may be closed): ${t.message}")
            }
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    val showLensDistanceSlider = MutableLiveData(false)
    val lensDistanceRange = MutableLiveData<Range<Float>>()
    var lensDistance: Float
        @Bindable get() {
            val settings = cameraSettings
            return if ((settings != null) &&
                settings.isAvailableFlow.value
            ) {
                settings.focus.lensDistance
            } else {
                0f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                try {
                    settings.focus.let {
                        if (settings.isAvailableFlow.value) {
                            it.lensDistance = value
                        }
                        notifyPropertyChanged(BR.lensDistance)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Setting lens distance failed (camera session may be closed): ${t.message}")
                }
            } ?: Log.e(TAG, "Camera settings is not accessible")
        }

    private fun notifySourceChanged() {
        val videoSource = streamer?.videoInput?.sourceFlow?.value ?: return
        if (videoSource is ICameraSource) {
            notifyCameraChanged(videoSource)
        } else {
            isFlashAvailable.postValue(false)
            isAutoWhiteBalanceAvailable.postValue(false)
            isExposureCompensationAvailable.postValue(false)
            isZoomAvailable.postValue(false)
            isAutoFocusModeAvailable.postValue(false)
        }
    }

    private fun notifyCameraChanged(videoSource: ICameraSource) {
        val settings = videoSource.settings
        // Set optical stabilization first
        // Do not set both video and optical stabilization at the same time
        if (settings.isAvailableFlow.value) {
            if (settings.stabilization.isOpticalAvailable) {
                settings.stabilization.enableOptical = true
            } else {
                settings.stabilization.enableVideo = true
            }
        }

        // Flash
        isFlashAvailable.postValue(settings.flash.isAvailable)

        // WB
        isAutoWhiteBalanceAvailable.postValue(settings.whiteBalance.availableAutoModes.size > 1)

        // Exposure
        isExposureCompensationAvailable.postValue(
            !settings.exposure.availableCompensationRange.isEmpty
        )
        exposureCompensationRange.postValue(
            Range(
                (settings.exposure.availableCompensationRange.lower * settings.exposure.availableCompensationStep.toFloat()).toInt(),
                (settings.exposure.availableCompensationRange.upper * settings.exposure.availableCompensationStep.toFloat()).toInt()
            )
        )
        exposureCompensationStep.postValue(settings.exposure.availableCompensationStep)
        exposureCompensation = 0f

        // Zoom
        isZoomAvailable.postValue(
            !settings.zoom.availableRatioRange.isEmpty
        )
        zoomRatioRange.postValue(settings.zoom.availableRatioRange)
        zoomRatio = 1.0f

        // Focus
        isAutoFocusModeAvailable.postValue(settings.focus.availableAutoModes.size > 1)

        // Lens distance
        showLensDistanceSlider.postValue(false)
        lensDistanceRange.postValue(settings.focus.availableLensDistanceRange)
        lensDistance = 0f
    }

    override fun onCleared() {
        super.onCleared()
        
        // Cancel any ongoing RTMP retry loop
        rtmpRetryJob?.cancel()
        rtmpRetryJob = null
        Log.i(TAG, "Cancelled RTMP retry loop in onCleared()")
        
        // Cancel buffering check
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
        rtmpBufferingStartTime = 0L
        
        // Reset disconnection handler guard flag
        isHandlingDisconnection = false
        
        // Clean up RTMP disconnect listener
        rtmpDisconnectListener?.let { listener ->
            try {
                currentRtmpPlayer?.removeListener(listener)
                Log.i(TAG, "Removed RTMP disconnect listener in onCleared()")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove RTMP listener in onCleared(): ${e.message}")
            }
        }
        rtmpDisconnectListener = null
        currentRtmpPlayer = null
        
        // try {
        //     streamer.releaseBlocking()
        // } catch (t: Throwable) {
        //     Log.e(TAG, "Streamer release failed", t)
        // }

        // Always unbind from the service - since we started it independently,
        // unbinding won't destroy it and it should continue streaming in background
        serviceConnection?.let { connection ->
            application.unbindService(connection)
            Log.i(TAG, "Unbound from CameraStreamerService - service continues running independently")
        }

        // Don't clear service state - the service should continue running independently
        // Only clear the ViewModel's local references
        streamerService = null
        serviceConnection = null
        // DO NOT set _serviceReady.value = false here - the service is still running!

        // Clean up MediaProjection resources
        streamingMediaProjection?.stop()
        streamingMediaProjection = null
        mediaProjectionHelper.release()
        Log.i(TAG, "PreviewViewModel cleared but service continues running for background streaming")
    }

    companion object {
        private const val TAG = "PreviewViewModel"
    }
}
