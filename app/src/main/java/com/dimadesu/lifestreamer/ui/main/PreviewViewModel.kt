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
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.dimadesu.lifestreamer.BR
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.data.rotation.RotationRepository
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.ui.main.usecases.BuildStreamerUseCase
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionHelper
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.uvc.UvcVideoSource
import com.dimadesu.lifestreamer.utils.ObservableViewModel
import com.dimadesu.lifestreamer.utils.dataStore
import com.dimadesu.lifestreamer.utils.isEmpty
import com.dimadesu.lifestreamer.utils.setNextCameraId
import com.dimadesu.lifestreamer.utils.toggleBackToFront
import com.dimadesu.lifestreamer.utils.ReconnectTimer
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameras
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
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.IBitmapSource
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import com.dimadesu.lifestreamer.services.CameraStreamerService
import com.dimadesu.lifestreamer.bitrate.AdaptiveSrtBitrateRegulatorController
import com.dimadesu.lifestreamer.models.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)


class PreviewViewModel(private val application: Application) : ObservableViewModel() {
    private val storageRepository = DataStoreRepository(application, application.dataStore)
    private val rotationRepository = RotationRepository.getInstance(application)
    val mediaProjectionHelper = MediaProjectionHelper(application)
    private val buildStreamerUseCase = BuildStreamerUseCase(application, storageRepository)

    // Mutex to prevent race conditions on rapid start/stop operations
    private val streamOperationMutex = Mutex()

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
    
    // Remember last used camera ID when switching to RTMP/bitmap sources
    private var lastUsedCameraId: String? = null

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
     * UVC Camera helper for USB camera access
     */
    private var uvcCameraHelper: com.herohan.uvcapp.CameraHelper? = null

    /**
     * User intent flags - track what the user toggled ON (not what fallback is active)
     * These allow buttons to show correct state even when source falls back to bitmap
     */
    private val _userToggledRtmp = MutableLiveData<Boolean>(false)
    private val _userToggledUvc = MutableLiveData<Boolean>(false)

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
        // Only RTMP and Bitmap sources need MediaProjection for audio capture
        // UVC and Camera sources use microphone directly
        return currentVideoSource is RTMPVideoSource || currentVideoSource is IBitmapSource
    }

    // Streamer errors (nullable to support single-event pattern - cleared after observation)
    private val _streamerErrorLiveData: MutableLiveData<String?> = MutableLiveData()
    val streamerErrorLiveData: LiveData<String?> = _streamerErrorLiveData

    // Toast messages (nullable to support single-event pattern - cleared after observation)
    private val _toastMessageLiveData: MutableLiveData<String?> = MutableLiveData()
    val toastMessageLiveData: LiveData<String?> = _toastMessageLiveData
    private val _endpointErrorLiveData: MutableLiveData<String?> = MutableLiveData()
    val endpointErrorLiveData: LiveData<String?> = _endpointErrorLiveData
    
    // Clear methods for single-event pattern (prevents re-showing errors on orientation change)
    fun clearStreamerError() {
        _streamerErrorLiveData.value = null
    }
    
    fun clearEndpointError() {
        _endpointErrorLiveData.value = null
    }

    fun clearToastMessage() {
        _toastMessageLiveData.value = null
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

    // Unified stream status for UI and notifications (shared enum)
    // Stream status - observe from Service to keep UI and notification in sync
    val streamStatus: StateFlow<StreamStatus> = serviceReadyFlow.flatMapLatest { ready ->
        if (ready && service != null) {
            service!!.serviceStreamStatus
        } else {
            flowOf(StreamStatus.NOT_STREAMING)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StreamStatus.NOT_STREAMING)

    // LiveData for data binding to track streaming status changes
    val streamStatusLiveData: LiveData<StreamStatus> = streamStatus.asLiveData()

    // Human-friendly status string for UI binding
    val streamStatusTextLiveData: LiveData<String> = streamStatus.map { status ->
        when (status) {
            StreamStatus.NOT_STREAMING -> application.getString(R.string.status_not_streaming)
            StreamStatus.STARTING -> application.getString(R.string.status_starting)
            StreamStatus.CONNECTING -> application.getString(R.string.status_connecting)
            StreamStatus.STREAMING -> application.getString(R.string.status_streaming)
            StreamStatus.ERROR -> application.getString(R.string.status_error)
        }
    }.asLiveData()

    // Audio source indicator for displaying current audio source type
    private val _audioSourceIndicatorLiveData = MutableLiveData<String>()
    val audioSourceIndicatorLiveData: LiveData<String> = _audioSourceIndicatorLiveData

    // MediaProjection session for streaming
    private var streamingMediaProjection: MediaProjection? = null

    // Connection retry mechanism (inspired by Moblin)
    private val reconnectTimer = ReconnectTimer()
    
    // Observe reconnection state from Service
    val isReconnectingLiveData: LiveData<Boolean> = serviceReadyFlow.flatMapLatest { ready ->
        if (ready && service != null) {
            service!!.isReconnecting
        } else {
            flowOf(false)
        }
    }.asLiveData()
    
    private var lastDisconnectReason: String? = null
    private var rotationIgnoredDuringReconnection: Int? = null // Store rotation changes during reconnection
    private var needsMediaProjectionAudioRestore = false // Track if we need to restore MediaProjection audio after reconnection
    
    // LiveData for reconnection status UI feedback
    private val _reconnectionStatusLiveData = MutableLiveData<String?>()
    val reconnectionStatusLiveData: LiveData<String?> = _reconnectionStatusLiveData

    // Camera information for button creation
    data class CameraInfo(
        val id: String,
        val displayName: String,
        val facing: String
    )
    
    private val _availableCamerasLiveData = MutableLiveData<List<CameraInfo>>(emptyList())
    val availableCamerasLiveData: LiveData<List<CameraInfo>> = _availableCamerasLiveData

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
     * Remove bitrate regulator from streamer if streaming with SRT.
     * Safe to call even if regulator is not attached.
     */
    private suspend fun removeBitrateRegulatorIfNeeded() {
        val currentStreamer = serviceStreamer ?: return
        val isCurrentlyStreaming = currentStreamer.isStreamingFlow.value == true
        if (!isCurrentlyStreaming) return
        
        val isSrtStream = storageRepository.endpointDescriptorFlow.first().type.sinkType == MediaSinkType.SRT
        if (!isSrtStream) return
        
        try {
            Log.i(TAG, "Removing bitrate regulator before video source switch")
            currentStreamer.removeBitrateRegulatorController()
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove bitrate regulator: ${e.message}")
        }
    }
    
    /**
     * Re-add bitrate regulator to streamer if streaming with SRT.
     * Waits briefly for encoder to initialize before attaching.
     */
    private suspend fun readdBitrateRegulatorIfNeeded() {
        val currentStreamer = serviceStreamer ?: return
        val isCurrentlyStreaming = currentStreamer.isStreamingFlow.value == true
        if (!isCurrentlyStreaming) return
        
        val isSrtStream = storageRepository.endpointDescriptorFlow.first().type.sinkType == MediaSinkType.SRT
        if (!isSrtStream) return
        
        try {
            val bitrateRegulatorConfig = storageRepository.bitrateRegulatorConfigFlow.first()
            if (bitrateRegulatorConfig != null) {
                val selectedMode = storageRepository.regulatorModeFlow.first()
                // Small delay to let the new encoder initialize
                delay(200)
                currentStreamer.addBitrateRegulatorController(
                    AdaptiveSrtBitrateRegulatorController.Factory(
                        bitrateRegulatorConfig = bitrateRegulatorConfig,
                        mode = selectedMode
                    )
                )
                Log.i(TAG, "Re-added bitrate regulator after video source switch")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-add bitrate regulator: ${e.message}")
        }
    }

    /**
     * Helper functions to interact with streamer directly (service compatibility layer)
     * 
     * @param shouldSuppressErrors If true, errors won't be posted to streamerErrorLiveData (for auto-retry)
     */
    private suspend fun startServiceStreaming(descriptor: MediaDescriptor, shouldSuppressErrors: Boolean = false): Boolean {
        return try {
            // Check if user stopped before we even start
            if (service?.userStoppedManually?.value == true) {
                Log.i(TAG, "startServiceStreaming: User stopped manually, aborting")
                return false
            }
            
            Log.i(TAG, "startServiceStreaming: Opening streamer with descriptor: $descriptor")

            val currentStreamer = serviceStreamer
            if (currentStreamer == null) {
                Log.e(TAG, "startServiceStreaming: serviceStreamer is null!")
                if (!shouldSuppressErrors) {
                    _streamerErrorLiveData.postValue("Service streamer not available")
                }
                return false
            }

            // Validate that both video and audio sources are configured before starting stream
            val (sourcesValid, sourceError) = validateSourcesConfigured(currentStreamer)
            if (!sourcesValid) {
                Log.e(TAG, "startServiceStreaming: Cannot start stream: $sourceError")
                if (!shouldSuppressErrors) {
                    _streamerErrorLiveData.postValue("Missing video or audio source - please reinitialize")
                }
                return false
            }
            Log.i(TAG, "startServiceStreaming: Sources validated successfully")

            // Validate RTMP URL format
            val uri = descriptor.uri.toString()
            if (uri.startsWith("rtmp://")) {
                Log.i(TAG, "startServiceStreaming: Attempting RTMP connection to $uri")
                val host = uri.substringAfter("://").substringBefore("/")
                Log.i(TAG, "startServiceStreaming: RTMP host: $host")
            }

            Log.i(TAG, "startServiceStreaming: serviceStreamer available, calling open()...")

            // Add timeout to prevent hanging
            // Run on IO dispatcher to prevent blocking UI thread
            withTimeout(5000) { // 5 second timeout
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    currentStreamer.open(descriptor)
                }
            }
            Log.i(TAG, "startServiceStreaming: open() completed, calling startStream()...")
            
            // Wait for encoders to be initialized after open
            // This prevents the race condition where video encoder isn't ready after rapid stop/start
            Log.d(TAG, "startServiceStreaming: Waiting for encoders to initialize...")
            val encodersReady = kotlinx.coroutines.withTimeoutOrNull(5000) {
                while ((currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder == null 
                       || (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer)?.audioEncoder == null) {
                    kotlinx.coroutines.delay(200)
                }
                true
            } ?: false
            
            if (!encodersReady) {
                val videoEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder != null
                val audioEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer)?.audioEncoder != null
                val errorMsg = "Encoders not ready (video=$videoEncoderExists, audio=$audioEncoderExists)"
                Log.e(TAG, "startServiceStreaming: $errorMsg")
                if (!shouldSuppressErrors) {
                    _streamerErrorLiveData.postValue(errorMsg)
                }
                return false
            }
            val videoEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer)?.videoEncoder != null
            val audioEncoderExists = (currentStreamer as? io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer)?.audioEncoder != null
            Log.i(TAG, "startServiceStreaming: Encoders ready - video=$videoEncoderExists, audio=$audioEncoderExists")
            
            // Apply saved rotation BEFORE starting stream (during reconnection)
            // This is the critical window where rotation can be set
            service?.getSavedStreamingOrientation()?.let { savedRotation ->
                Log.i(TAG, "startServiceStreaming: Applying saved rotation $savedRotation before starting stream")
                try {
                    currentStreamer.setTargetRotation(savedRotation)
                    Log.i(TAG, "startServiceStreaming: Successfully applied saved rotation $savedRotation")
                } catch (e: Exception) {
                    Log.e(TAG, "startServiceStreaming: Failed to apply saved rotation: ${e.message}")
                }
            }
            
            // Check if user stopped before calling the blocking startStream()
            if (service?.userStoppedManually?.value == true) {
                Log.i(TAG, "startServiceStreaming: User stopped manually before startStream(), aborting")
                return false
            }

            // Protect startStream() from cancellation to prevent camera configuration errors
            // Run on IO dispatcher to prevent blocking UI thread during RTMP connectStream()
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                currentStreamer.startStream()
            }
            Log.i(TAG, "startServiceStreaming: Stream started successfully")
            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "startServiceStreaming failed: Timeout opening connection to ${descriptor.uri}")
            if (!shouldSuppressErrors) {
                _streamerErrorLiveData.postValue("Connection timeout - check server address and network")
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "startServiceStreaming failed: ${e.message}", e)
            if (!shouldSuppressErrors) {
                _streamerErrorLiveData.postValue("Stream start failed: ${e.message}")
            }
            false
        }
    }

    /**
     * Core streaming logic shared between initial start and reconnection.
     * Handles descriptor retrieval, stream start, and bitrate regulator setup.
     * 
     * @param shouldAutoRetry If true, will trigger reconnection on connection failure
     * @return true if stream started successfully, false otherwise
     */
    private suspend fun doStartStream(shouldAutoRetry: Boolean = false): Boolean {
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "doStartStream: serviceStreamer is null!")
            _streamerErrorLiveData.postValue("Service streamer not available")
            return false
        }

        // Validate that both video and audio sources are configured
        val (sourcesValid, sourceError) = validateSourcesConfigured(currentStreamer)
        if (!sourcesValid) {
            Log.e(TAG, "doStartStream: Cannot start stream: $sourceError")
            if (!shouldAutoRetry) {
                _streamerErrorLiveData.postValue("Missing video or audio source - please check configuration")
            }
            return false
        }
        Log.i(TAG, "doStartStream: Sources validated successfully")

        try {
            val descriptor = storageRepository.endpointDescriptorFlow.first()
            Log.i(TAG, "doStartStream: Starting stream with descriptor: $descriptor")
            
            val success = startServiceStreaming(descriptor, shouldSuppressErrors = shouldAutoRetry)
            if (!success) {
                Log.e(TAG, "doStartStream: Stream start failed - startServiceStreaming returned false")
                
                // Check if user stopped manually - if so, don't trigger reconnection
                if (service?.userStoppedManually?.value == true) {
                    Log.d(TAG, "User stopped stream, skipping error handling")
                    return false
                }
                
                // Trigger auto-retry if requested and not already reconnecting
                if (shouldAutoRetry && service?.isReconnecting?.value != true) {
                    val errorMessage = "Connection failed - unable to establish stream"
                    Log.i(TAG, "Connection failed, triggering auto-retry (error dialog suppressed)")
                    handleDisconnection(errorMessage, isInitialConnection = true)
                } else if (!shouldAutoRetry && service?.isReconnecting?.value != true) {
                    // Only show error dialog if not auto-retrying AND not already reconnecting
                    _streamerErrorLiveData.postValue("Connection failed - unable to establish stream")
                } else {
                    Log.d(TAG, "Connection failed during reconnection, error dialog suppressed")
                }
                
                return false
            }
            
            Log.i(TAG, "doStartStream: Stream started successfully")
            
            // Add bitrate regulator for SRT streams
            if (descriptor.type.sinkType == MediaSinkType.SRT) {
                val bitrateRegulatorConfig = storageRepository.bitrateRegulatorConfigFlow.first()
                if (bitrateRegulatorConfig != null) {
                    Log.i(TAG, "doStartStream: Adding bitrate regulator controller")
                    val selectedMode = storageRepository.regulatorModeFlow.first()
                    currentStreamer.addBitrateRegulatorController(
                        AdaptiveSrtBitrateRegulatorController.Factory(
                            bitrateRegulatorConfig = bitrateRegulatorConfig,
                            mode = selectedMode
                        )
                    )
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "doStartStream failed: ${e.message}", e)
            val errorMessage = "Stream start failed: ${e.message}"
            
            // Check if user stopped manually - if so, don't trigger reconnection
            if (service?.userStoppedManually?.value == true) {
                Log.d(TAG, "User stopped stream, skipping error handling")
                return false
            }
            
            // Trigger auto-retry if requested and not already reconnecting
            if (shouldAutoRetry && service?.isReconnecting?.value != true) {
                Log.i(TAG, "Connection failed, triggering auto-retry (error dialog suppressed)")
                handleDisconnection(errorMessage, isInitialConnection = true)
            } else if (!shouldAutoRetry && service?.isReconnecting?.value != true) {
                // Only show error dialog if not auto-retrying AND not already reconnecting
                _streamerErrorLiveData.postValue(errorMessage)
            } else {
                Log.d(TAG, "Exception during reconnection, error dialog suppressed")
            }
            
            return false
        }
    }

    private suspend fun stopServiceStreaming(): Boolean {
        return try {
            Log.i(TAG, "stopServiceStreaming: Stopping stream...")
            serviceStreamer?.stopStream()
            Log.i(TAG, "stopServiceStreaming: Stream stopped successfully")

            // Don't stop the service - keep it alive like notification stop does
            // This prevents race conditions where ViewModel tries to access destroyed service
            // The service will stay alive in the background ready for next stream
            // val serviceIntent = Intent(application, CameraStreamerService::class.java)
            // application.stopService(serviceIntent)
            // Log.i(TAG, "stopServiceStreaming: Stopped CameraStreamerService foreground service")

            true
        } catch (e: Exception) {
            Log.e(TAG, "stopServiceStreaming failed: ${e.message}", e)
            false
        }
    }

    /**
     * Validates that both video and audio sources are configured for a streamer.
     * 
     * @param streamer The streamer instance to validate
     * @return Pair of (isValid, errorMessage). If valid, errorMessage is null.
     */
    private fun validateSourcesConfigured(streamer: SingleStreamer): Pair<Boolean, String?> {
        val videoSource = streamer.videoInput?.sourceFlow?.value
        val audioSource = streamer.audioInput?.sourceFlow?.value
        
        return if (videoSource == null || audioSource == null) {
            val errorMsg = "video source=${videoSource != null}, audio source=${audioSource != null}"
            false to errorMsg
        } else {
            Log.d(TAG, "Sources validated - video: ${videoSource.javaClass.simpleName}, audio: ${audioSource.javaClass.simpleName}")
            true to null
        }
    }

    /**
     * Determines and sets the appropriate audio source based on the current video source.
     * Audio follows video:
     * - Camera video -> Microphone
     * - RTMP/Bitmap video -> MediaProjection (if available), otherwise Microphone
     */
    private suspend fun setAudioSourceBasedOnVideoSource() {
        val currentStreamer = serviceStreamer ?: return
        val currentVideoSource = currentStreamer.videoInput?.sourceFlow?.value
        val isRtmpOrBitmap = currentVideoSource != null && currentVideoSource !is ICameraSource
        
        if (isRtmpOrBitmap) {
            // RTMP or Bitmap source - try MediaProjection, fallback to microphone
            val projection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
            if (projection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(projection))
                    Log.i(TAG, "Set MediaProjection audio for RTMP/Bitmap video")
                } catch (e: Exception) {
                    Log.w(TAG, "MediaProjection audio failed, using microphone: ${e.message}")
                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
                }
            } else {
                Log.i(TAG, "No MediaProjection available for RTMP/Bitmap, using microphone")
                currentStreamer.setAudioSource(MicrophoneSourceFactory())
            }
        } else {
            // Camera source - use microphone
            Log.i(TAG, "Camera video detected, using microphone audio")
            currentStreamer.setAudioSource(MicrophoneSourceFactory())
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
                    
                    // Observe centralized reconnection status message from service
                    try {
                        val svc = binder.getService()
                        viewModelScope.launch {
                            svc.reconnectionStatusMessage.collect { message ->
                                _reconnectionStatusLiveData.postValue(message)
                            }
                        }
                        Log.i(TAG, "Observing centralized reconnection state from service")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to observe reconnection state from service: ${t.message}")
                    }
                    
                    // Collect bitrate flow from service binder if available
                    try {
                        val svc = binder.getService()
                        viewModelScope.launch {
                            svc.currentBitrateFlow.collect { bits ->
                                val text = bits?.let { b -> if (b >= 1_000_000) String.format(java.util.Locale.US, "%.2f Mbps", b / 1_000_000.0) else String.format(java.util.Locale.US, "%d kb/s", b / 1000) } ?: ""
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
                                    service?.setStreamStatus(svcStatus)
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
                service?.setStreamStatus(StreamStatus.NOT_STREAMING)
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
            // Set initial audio source indicator based on current source
            val initialAudioSource = currentStreamer.audioInput?.sourceFlow?.value
            val audioSourceLabel = getAudioSourceLabel(initialAudioSource)
            _audioSourceIndicatorLiveData.postValue(audioSourceLabel)
            Log.i(TAG, "Initial audio source (already streaming): $audioSourceLabel (${initialAudioSource?.javaClass?.simpleName})")
            return
        }

        Log.i(TAG, "Initializing streamer sources - Audio enabled: ${currentStreamer.withAudio}, Video enabled: ${currentStreamer.withVideo}")

        // Set audio source and video source only if not streaming
        if (currentStreamer.withAudio) {
            Log.i(TAG, "Audio source is enabled. Setting audio based on video source")
            setAudioSourceBasedOnVideoSource()
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
                currentStreamer.setVideoSource(CameraSourceFactory())
            } else {
                Log.w(TAG, "Camera permission not granted")
            }
        } else {
            Log.i(TAG, "Video source is disabled")
        }

        // Set up flow observers for the service-based streamer
        observeStreamerFlows()

        // Set initial audio source indicator based on current source
        val initialAudioSource = currentStreamer.audioInput?.sourceFlow?.value
        val audioSourceLabel = getAudioSourceLabel(initialAudioSource)
        _audioSourceIndicatorLiveData.postValue(audioSourceLabel)
        Log.i(TAG, "Initial audio source: $audioSourceLabel (${initialAudioSource?.javaClass?.simpleName})")
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

        // Observe audio source changes to update the audio source indicator
        viewModelScope.launch {
            currentStreamer.audioInput?.sourceFlow?.collect { audioSource ->
                val audioSourceLabel = getAudioSourceLabel(audioSource)
                _audioSourceIndicatorLiveData.postValue(audioSourceLabel)
                Log.i(TAG, "Audio source changed: $audioSourceLabel (${audioSource?.javaClass?.simpleName})")
            }
        }

        viewModelScope.launch {
            currentStreamer.throwableFlow.filterNotNull().filter { !it.isClosedException }
                .map { "${it.javaClass.simpleName}: ${it.message}" }.collect { errorMessage ->
                    // Don't show error dialog during reconnection attempts
                    if (service?.isReconnecting?.value != true) {
                        _streamerErrorLiveData.postValue(errorMessage)
                    } else {
                        Log.w(TAG, "Error during reconnection (dialog suppressed): $errorMessage")
                    }
                }
        }

        viewModelScope.launch {
            currentStreamer.throwableFlow.filterNotNull().filter { it.isClosedException }
                .map { "Connection lost: ${it.message}" }.collect { errorMessage ->
                    Log.w(TAG, "Connection lost detected: $errorMessage, status=${streamStatus.value}, isReconnecting=${service?.isReconnecting?.value}")
                    
                    // Check if user stopped manually - if so, don't trigger reconnection
                    if (service?.userStoppedManually?.value == true) {
                        Log.d(TAG, "User stopped stream, skipping connection error handling")
                        return@collect
                    }
                    
                    // Suppress error if we're already in reconnection mode
                    if (service?.isReconnecting?.value == true) {
                        Log.i(TAG, "Connection error during reconnection - dialog suppressed")
                    } else {
                        // Connection lost - trigger reconnection
                        Log.i(TAG, "Connection lost - will auto-retry (error dialog suppressed)")
                        handleDisconnection(errorMessage, isInitialConnection = false)
                    }
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
                        val previousStatus = streamStatus.value
                        Log.i(TAG, "isStreamingFlow changed: $isStreaming, previousStatus=$previousStatus, userStoppedManually=${service?.userStoppedManually?.value}, isReconnecting=${service?.isReconnecting?.value}")
                        
                        if (isStreaming) {
                            // Don't change to STREAMING during reconnection - keep CONNECTING status
                            // This ensures stop button works during reconnection attempts
                            if (service?.isReconnecting?.value != true) {
                                service?.setStreamStatus(StreamStatus.STREAMING)
                            } else {
                                Log.d(TAG, "Stream started during reconnection - keeping CONNECTING status for stop button")
                            }
                        } else {
                            // Stream stopped - check if this was unexpected (should trigger reconnection)
                            val wasStreaming = previousStatus == StreamStatus.STREAMING || 
                                              previousStatus == StreamStatus.CONNECTING
                            
                            Log.d(TAG, "Stream stopped - wasStreaming=$wasStreaming, userStopped=${service?.userStoppedManually?.value}, isReconnecting=${service?.isReconnecting?.value}")
                            
                            if (wasStreaming && service?.userStoppedManually?.value != true && service?.isReconnecting?.value != true) {
                                // Unexpected disconnection - trigger reconnection
                                Log.w(TAG, "Unexpected stream stop detected - triggering reconnection")
                                handleDisconnection("Stream stopped unexpectedly", isInitialConnection = false)
                            } else {
                                // Normal stop or already handling reconnection
                                if (service?.isReconnecting?.value == true) {
                                    Log.d(TAG, "Stream stopped during reconnection - keeping CONNECTING status")
                                    service?.setStreamStatus(StreamStatus.CONNECTING)
                                } else if (service?.userStoppedManually?.value == true) {
                                    Log.d(TAG, "User stopped stream - setting NOT_STREAMING")
                                    service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                                } else if (!wasStreaming) {
                                    // Stream was never streaming, safe to set NOT_STREAMING
                                    Log.d(TAG, "Stream never started - setting NOT_STREAMING")
                                    service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                                }
                                // If none of the above, don't change status (keep whatever it is)
                            }
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
        // Apply rotation changes, but if streamer is currently streaming or reconnecting,
        // queue the change and apply it when streaming stops to avoid conflicts in encoding pipeline.
        viewModelScope.launch {
            rotationRepository.rotationFlow.collect { rotation ->
                val current = serviceStreamer
                val isCurrentlyStreaming = current?.isStreamingFlow?.value == true
                val currentStatus = streamStatus.value
                val isInStreamingProcess = currentStatus == StreamStatus.STARTING ||
                                           currentStatus == StreamStatus.CONNECTING ||
                                           currentStatus == StreamStatus.STREAMING
                
                // During reconnection, ignore rotation changes to maintain locked orientation
                // But remember the latest rotation so we can apply it when reconnection ends
                if (service?.isReconnecting?.value == true) {
                    rotationIgnoredDuringReconnection = rotation
                    Log.i(TAG, "Rotation change to $rotation ignored during reconnection (will apply later)")
                    return@collect
                }
                
                if (isCurrentlyStreaming || isInStreamingProcess) {
                    Log.i(TAG, "Rotation change to $rotation queued (streaming: $isCurrentlyStreaming, status: $currentStatus)")
                    pendingTargetRotation = rotation
                } else {
                    try {
                        current?.setTargetRotation(rotation)
                        Log.i(TAG, "Rotation change to $rotation applied immediately (not streaming)")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to set target rotation: $t")
                    }
                }
            }
        }

        // When streaming stops, apply any pending rotation change.
        // BUT: Don't apply during reconnection - we want to keep the locked orientation
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isStreamingFlow?.collect { isStreaming ->
                        if (!isStreaming && service?.isReconnecting?.value != true) {
                            pendingTargetRotation?.let { pending ->
                                Log.i(TAG, "Applying pending rotation $pending after stream stopped")
                                try {
                                    serviceStreamer?.setTargetRotation(pending)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Failed to apply pending rotation: $t")
                                }
                                pendingTargetRotation = null
                            }
                        } else if (!isStreaming && service?.isReconnecting?.value == true) {
                            Log.i(TAG, "Skipping pending rotation application during reconnection (pending: $pendingTargetRotation)")
                        }
                    }
                }
            }
        }
        
        // Observe manual stop from notification - cancel reconnection if in progress
        // Use a separate coroutine that waits for service to be ready and then observes the flow
        viewModelScope.launch {
            // Wait for service to be ready
            _serviceReady.first { it }
            // Now observe the stop signal
            service?.userStoppedFromNotification?.collect {
                Log.i(TAG, "User stopped from notification - cancelling reconnection")
                // Mark as manual stop to prevent reconnection
                service?.markUserStoppedManually()
                // Cancel any pending reconnection
                reconnectTimer.stop()
                // isReconnecting managed by service
                _reconnectionStatusLiveData.postValue(null)
                service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                // Update service notification to reflect stopped state
                service?.updateStreamStatus(StreamStatus.NOT_STREAMING)
            }
        }
        
        // Observe critical errors from service (e.g., start from notification failures)
        // Trigger reconnection for these errors
        viewModelScope.launch {
            _serviceReady.first { it }
            service?.let { svc ->
                try {
                    svc.criticalErrors.collect { errorMessage ->
                        Log.w(TAG, "Critical error from service: $errorMessage")
                        
                        // Critical errors from notification starts should trigger reconnection
                        // regardless of userStoppedManually flag (it's a new start attempt)
                        // Only skip if already reconnecting
                        if (service?.isReconnecting?.value != true) {
                            Log.i(TAG, "Triggering reconnection due to service critical error (notification start failure)")
                            // Reset userStoppedManually since this is a new start attempt from notification
                            service?.clearUserStoppedManually()
                            handleDisconnection(errorMessage, isInitialConnection = true)
                        } else {
                            Log.d(TAG, "Skipping reconnection trigger - already reconnecting")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to collect critical errors from service: ${e.message}")
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
            // Pre-flight checks with mutex held briefly
            val canStart = streamOperationMutex.withLock {
                Log.d(TAG, "startStream() - Acquired lock for pre-flight checks")
                
                // Use service's centralized check instead of local flag
                val serviceCanStart = service?.canStartStream() ?: false
                if (!serviceCanStart) {
                    Log.w(TAG, "Cannot start stream - service reports cannot start (cleanup in progress or blocked)")
                    _streamerErrorLiveData.postValue("Please wait - stopping previous stream...")
                    return@withLock false
                }
                
                // Only clear userStoppedManually for user-initiated starts, not reconnection attempts
                // Use service's centralized state
                val isCurrentlyReconnecting = service?.isReconnecting?.value ?: false
                if (!isCurrentlyReconnecting) {
                    service?.clearUserStoppedManually()
                    Log.i(TAG, "startStream() - Cleared userStoppedManually via service (user-initiated start)")
                } else {
                    Log.d(TAG, "startStream() - Keeping userStoppedManually as-is (reconnection attempt)")
                }
                return@withLock true
            }
            
            if (!canStart) {
                Log.d(TAG, "startStream() - Pre-flight check failed, aborting")
                return@launch
            }
            
            service?.setStreamStatus(StreamStatus.STARTING)
            Log.i(TAG, "startStream() called")
            
            // Lock stream rotation BEFORE starting to ensure it matches UI orientation
            // Get current display rotation and lock the service to it
            // Note: We use WindowManager.defaultDisplay for all API levels here because
            // Application context doesn't have an associated display. The deprecation
            // is acceptable since this is just reading the current rotation value.
            val windowManager = application.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val currentRotation = windowManager.defaultDisplay.rotation
            service?.lockStreamRotation(currentRotation)
            Log.i(TAG, "Pre-locked stream rotation to $currentRotation before starting")
            
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
            val videoSource = currentStreamer.videoInput?.sourceFlow?.value
            val audioSource = currentStreamer.audioInput?.sourceFlow?.value

            Log.i(TAG, "Source check before stream start:")
            Log.i(TAG, "  hasVideoSource: $hasVideoSource (${videoSource?.javaClass?.simpleName})")
            Log.i(TAG, "  hasAudioSource: $hasAudioSource (${audioSource?.javaClass?.simpleName})")

            // Special case: If video source is bitmap (RTMP fallback) but no audio, set appropriate audio
            if (hasVideoSource && !hasAudioSource && videoSource is IBitmapSource) {
                Log.w(TAG, "Bitmap source detected without audio - setting audio source")
                try {
                    setAudioSourceBasedOnVideoSource()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set audio for bitmap source: ${e.message}")
                }
            }

            if (!hasVideoSource || !hasAudioSource) {
                Log.w(TAG, "Sources not fully configured - initializing...")
                initializeStreamerSources()
                kotlinx.coroutines.delay(500)
                
                // Re-check after initialization
                val videoAfterInit = currentStreamer.videoInput?.sourceFlow?.value
                val audioAfterInit = currentStreamer.audioInput?.sourceFlow?.value
                Log.i(TAG, "After initialization - Video: ${videoAfterInit?.javaClass?.simpleName}, Audio: ${audioAfterInit?.javaClass?.simpleName}")
                
                if (videoAfterInit == null || audioAfterInit == null) {
                    val error = "Failed to initialize sources - Video: ${videoAfterInit != null}, Audio: ${audioAfterInit != null}"
                    Log.e(TAG, error)
                    _streamerErrorLiveData.postValue(error)
                    return@launch
                }
            }

            service?.setStreamStatus(StreamStatus.CONNECTING)
            Log.i(TAG, "startStream: Set status to CONNECTING")
            var autoRetryTriggered = false
            try {
                val success = doStartStream(shouldAutoRetry = true)
                if (!success) {
                    Log.e(TAG, "Initial connection failed - isReconnecting=${service?.isReconnecting?.value}, status=${streamStatus.value}")
                    // handleDisconnection was already called in doStartStream
                    // Check if reconnection is active before clearing status
                    if (service?.isReconnecting?.value == true) {
                        autoRetryTriggered = true
                        Log.i(TAG, "Auto-retry triggered, keeping status=${streamStatus.value}")
                    } else {
                        service?.setStreamStatus(StreamStatus.ERROR)
                        Log.i(TAG, "No auto-retry, setting status to ERROR")
                    }
                    return@launch
                }
                Log.i(TAG, "Stream started successfully")
                service?.setStreamStatus(StreamStatus.STREAMING)
            } catch (e: Throwable) {
                Log.e(TAG, "startStream failed: ${e.message}, isReconnecting=${service?.isReconnecting?.value}", e)
                // Don't show error dialog if reconnection will handle it
                if (service?.isReconnecting?.value != true) {
                    _streamerErrorLiveData.postValue("startStream: ${e.message ?: "Unknown error"}")
                }
                // Check if reconnection was triggered by the exception
                if (service?.isReconnecting?.value == true) {
                    autoRetryTriggered = true
                    Log.i(TAG, "Exception but reconnecting, keeping status=${streamStatus.value}")
                } else {
                    service?.setStreamStatus(StreamStatus.ERROR)
                    Log.i(TAG, "Exception and no reconnection, setting status to ERROR")
                }
            } finally {
                Log.i(TAG, "startStream finally: autoRetryTriggered=$autoRetryTriggered, isReconnecting=${service?.isReconnecting?.value}, status=${streamStatus.value}, userStoppedManually=${service?.userStoppedManually?.value}")
                // Don't override status if auto-retry was triggered or if we're reconnecting
                // Also set to NOT_STREAMING if user manually stopped (handles race condition)
                if (service?.userStoppedManually?.value == true) {
                    Log.i(TAG, "startStream finally: User stopped manually, setting NOT_STREAMING")
                    service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                } else if (!autoRetryTriggered && service?.isReconnecting?.value != true && streamStatus.value != StreamStatus.STREAMING) {
                    Log.w(TAG, "startStream finally: Overriding status to NOT_STREAMING")
                    service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                } else {
                    Log.i(TAG, "startStream finally: NOT overriding status, keeping ${streamStatus.value}")
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
        // Clear manual stop flag when starting a new stream
        service?.clearUserStoppedManually()
        Log.i(TAG, "startStreamWithMediaProjection() - Reset userStoppedManually=false")
        
        service?.setStreamStatus(StreamStatus.STARTING)

        mediaProjectionHelper.requestProjection(mediaProjectionLauncher) { mediaProjection ->
            Log.i(TAG, "MediaProjection callback received - mediaProjection: ${if (mediaProjection != null) "SUCCESS" else "NULL"}")
            if (mediaProjection != null) {
                streamingMediaProjection = mediaProjection
                Log.i(TAG, "MediaProjection acquired for streaming session - starting setup...")

                viewModelScope.launch {
                    try {
                        // Set appropriate audio source based on current video source
                        setAudioSourceBasedOnVideoSource()

                        // Start the actual stream
                        service?.setStreamStatus(StreamStatus.CONNECTING)
                        startStreamInternal(onSuccess, onError)
                    } catch (e: Exception) {
                        val error = "Failed to configure MediaProjection audio: ${e.message}"
                        Log.e(TAG, error, e)
                        _streamerErrorLiveData.postValue(error)
                        service?.setStreamStatus(StreamStatus.ERROR)
                        onError(error)  // Also call callback for custom handling if needed
                    }
                }
            } else {
                val error = "MediaProjection permission required for streaming"
                Log.e(TAG, error)
                _streamerErrorLiveData.postValue(error)
                service?.setStreamStatus(StreamStatus.ERROR)
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
                val success = startServiceStreaming(descriptor, shouldSuppressErrors = true)
                
                if (!success) {
                    Log.e(TAG, "startServiceStreaming() returned false - stream start failed")
                    val errorMessage = "Connection failed - unable to establish stream"
                    
                    // Check if user stopped manually - if so, don't trigger reconnection
                    if (service?.userStoppedManually?.value == true) {
                        Log.d(TAG, "User stopped stream, skipping reconnection")
                        return@launch
                    }
                    
                    // Trigger auto-retry unless already reconnecting
                    if (service?.isReconnecting?.value != true) {
                        Log.i(TAG, "Connection failed, triggering auto-retry (error dialog suppressed)")
                        handleDisconnection(errorMessage, isInitialConnection = true)
                    }
                    return@launch
                }
                
                Log.i(TAG, "startServiceStreaming() completed successfully")
                Log.i(TAG, "Stream setup completed successfully, calling onSuccess()")
                service?.setStreamStatus(StreamStatus.STREAMING)
                onSuccess()
            } catch (e: Throwable) {
                val error = "Stream start failed: ${e.message ?: "Unknown error"}"
                Log.e(TAG, "STREAM START EXCEPTION: $error", e)
                
                // Check if user stopped manually - if so, don't trigger reconnection
                if (service?.userStoppedManually?.value == true) {
                    Log.d(TAG, "User stopped stream, skipping reconnection")
                    return@launch
                }
                
                // Trigger auto-retry unless already reconnecting
                if (service?.isReconnecting?.value != true) {
                    Log.i(TAG, "Connection failed with exception, triggering auto-retry (error dialog suppressed)")
                    handleDisconnection(error, isInitialConnection = true)
                } else {
                    Log.d(TAG, "Exception during reconnection, error dialog suppressed")
                }
            } finally {
                Log.i(TAG, "startStreamInternal finally block")
            }
        }
    }

    fun stopStream() {
        // CRITICAL: Mark user stopped IMMEDIATELY via service, BEFORE acquiring mutex
        // This ensures error handlers see the flag even if they fire before we get the lock
        service?.markUserStoppedManually()
        Log.i(TAG, "stopStream() - Marked userStoppedManually via service before acquiring mutex")
        
        // Also immediately cancel any pending reconnection timer
        reconnectTimer.stop()
        Log.i(TAG, "stopStream() - Cancelled reconnection timer")
        
        // If cleanup is already in progress, just update UI and return
        // Don't try to stop again - it will just get blocked
        val cleanupInProgress = service?.isCleanupInProgress ?: false
        if (cleanupInProgress) {
            Log.i(TAG, "stopStream() - Cleanup already in progress, just updating UI state")
            service?.cancelReconnection()
            service?.setStreamStatus(StreamStatus.NOT_STREAMING)
            return
        }
        
        viewModelScope.launch {
            // Track if we took an early exit path (these paths handle their own cleanup)
            var tookEarlyExit = false
            
            streamOperationMutex.withLock {
                Log.d(TAG, "stopStream() - Acquired lock")
                
                try {
                    val currentStreamer = serviceStreamer

                    if (currentStreamer == null) {
                        Log.w(TAG, "Service streamer not ready, cannot stop stream")
                        return@launch
                    }

                    val currentStreamingState = currentStreamer.isStreamingFlow.value
                    val currentStatus = streamStatus.value
                    val isCurrentlyReconnecting = service?.isReconnecting?.value ?: false
                    Log.i(TAG, "stopStream() called - Streaming state: $currentStreamingState, Status: $currentStatus, isReconnecting: $isCurrentlyReconnecting")

                    // If reconnecting, always cancel reconnection regardless of streaming state
                    if (isCurrentlyReconnecting) {
                        Log.i(TAG, "Cancelling active reconnection...")
                        
                        // userStoppedManually already marked via service before acquiring mutex
                        
                        // Cancel reconnection timer and clear service flags
                        reconnectTimer.stop()
                        service?.cancelReconnection()
                        
                        // Update UI state so user gets instant feedback
                        service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                        Log.i(TAG, "UI updated immediately - reconnection cancelled by user")
                        
                        // Unlock stream rotation since we're truly stopped
                        service?.unlockStreamRotation()
                        
                        // If we were actually streaming, do slow cleanup in background with flag
                        // If just reconnecting/connecting, close quickly without the flag
                        if (currentStreamingState == true) {
                            // Was actually streaming, need slow cleanup
                            service?.isCleanupInProgress = true
                            Log.i(TAG, "stopStream() - Set service.isCleanupInProgress=true for reconnection cancel cleanup (was streaming)")
                            viewModelScope.launch {
                                try {
                                    Log.d(TAG, "Closing endpoint in background (was streaming, may take time)...")
                                    withTimeout(3000) {
                                        currentStreamer.close()
                                    }
                                    Log.i(TAG, "Reconnection cancelled - endpoint closed")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error closing endpoint during reconnection cancel: ${e.message}")
                                } finally {
                                    service?.isCleanupInProgress = false
                                    Log.i(TAG, "stopStream() - Cleared service.isCleanupInProgress after reconnection cancel cleanup")
                                }
                            }
                        } else {
                            // Was just connecting/reconnecting, close quickly
                            try {
                                Log.d(TAG, "Closing endpoint (was just connecting, should be fast)...")
                                withContext(Dispatchers.IO) {
                                    withTimeout(1000) {
                                        currentStreamer.close()
                                    }
                                }
                                Log.i(TAG, "Reconnection cancelled - endpoint closed quickly")
                            } catch (e: Exception) {
                                Log.w(TAG, "Error closing endpoint during reconnection cancel: ${e.message}")
                            }
                        }
                        
                        tookEarlyExit = true
                        return@launch
                    }

                    // If already stopped and not in CONNECTING state, don't do anything
                    if (currentStreamingState != true && currentStatus != StreamStatus.CONNECTING) {
                        Log.i(TAG, "Stream is already stopped, skipping stop sequence")
                        tookEarlyExit = true
                        return@launch
                    }
                    
                    // If in CONNECTING state but not streaming, force stop the connection attempt
                    // This handles initial connection attempts that aren't part of reconnection
                    if (currentStatus == StreamStatus.CONNECTING && currentStreamingState != true) {
                        Log.i(TAG, "Stopping connection attempt (userStoppedManually already set)")
                        
                        // userStoppedManually already set to true before acquiring mutex
                        
                        // Cancel any pending reconnection attempts
                        reconnectTimer.stop()
                        // isReconnecting managed by service
                        
                        // Update UI state so user gets instant feedback
                        service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                        Log.i(TAG, "UI updated immediately - connection attempt cancelled by user")
                        
                        // Unlock stream rotation since we're truly stopped
                        service?.unlockStreamRotation()
                        
                        // Close endpoint synchronously - it should be fast since we never actually streamed
                        // No need for isCleanupInProgress flag since this is quick
                        try {
                            Log.d(TAG, "Closing endpoint (connection attempt never succeeded)...")
                            withContext(Dispatchers.IO) {
                                withTimeout(1000) {
                                    currentStreamer.close()
                                }
                            }
                            Log.i(TAG, "Connection attempt aborted - endpoint closed")
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing endpoint during connection abort: ${e.message}")
                        }
                        
                        tookEarlyExit = true
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

                } catch (e: Throwable) {
                    Log.e(TAG, "stopStream failed", e)
                    // Force clear state
                    streamingMediaProjection?.stop()
                    streamingMediaProjection = null
                } finally {
                    // Set status to NOT_STREAMING FIRST so any pending callbacks see it immediately
                    service?.setStreamStatus(StreamStatus.NOT_STREAMING)
                    
                    // User stopped flag already set via service before acquiring mutex
                    
                    // Cancel any pending reconnection attempts (already done via service.markUserStoppedManually)
                    reconnectTimer.stop()
                    
                    // Apply any rotation that was ignored during reconnection
                    rotationIgnoredDuringReconnection?.let { ignoredRotation ->
                        viewModelScope.launch {
                            try {
                                serviceStreamer?.setTargetRotation(ignoredRotation)
                                Log.i(TAG, "Applied rotation ($ignoredRotation) that was ignored during reconnection")
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to apply ignored rotation: $t")
                            }
                        }
                        rotationIgnoredDuringReconnection = null
                    }
                    
                    // Only set cleanup flag if we didn't take an early exit path
                    // (Early exit paths manage their own cleanup flags)
                    if (!tookEarlyExit) {
                        // Mark that cleanup will continue outside mutex
                        // This prevents race condition if user rapidly taps stop->start
                        service?.isCleanupInProgress = true
                        Log.i(TAG, "stopStream() - Set service.isCleanupInProgress=true before releasing lock")
                    }
                }
            } // Release mutex lock
            Log.d(TAG, "stopStream() - Released lock")
            
            // Skip cleanup if we took an early exit (those paths handle their own cleanup)
            if (tookEarlyExit) {
                Log.d(TAG, "stopStream() - Skipping main cleanup path (early exit taken)")
                return@launch
            }
            
            // Move cleanup to IO dispatcher to avoid blocking main thread for up to 8+ seconds
            // Wait for stream to stop and close endpoint OUTSIDE the mutex
            // Protected by isCleanupInProgress flag to prevent start() racing with cleanup
            withContext(Dispatchers.IO) {
                try {
                    val currentStreamer = serviceStreamer
                    if (currentStreamer != null) {
                        // Wait for stream to actually stop before closing endpoint
                        // This prevents calling close() while stopStream() is still executing
                        var retries = 0
                        while (currentStreamer.isStreamingFlow.value == true && retries < 50) {
                            kotlinx.coroutines.delay(100)
                            retries++
                        }
                        
                        if (retries >= 50) {
                            Log.w(TAG, "Timeout waiting for stream to stop - forcing close anyway")
                        }
                        
                        // Close the endpoint connection to allow fresh connection on next start
                        // Without this, the endpoint stays open and cannot be reopened on next start
                        try {
                            withTimeout(3000) {
                                currentStreamer.close()
                            }
                            Log.i(TAG, "Endpoint connection closed - ready for next start")
                        } catch (e: Exception) {
                            Log.e(TAG, "CRITICAL: Failed to close endpoint - second start will fail!", e)
                        }
                        
                        // Clear MediaProjection from service to prevent reusing expired tokens
                        mediaProjectionHelper.clearMediaProjection()
                        Log.i(TAG, "MediaProjection cleared from service")
                        
                        if (currentStreamer.isStreamingFlow.value == true) {
                            Log.w(TAG, "Stream did not stop cleanly after 5 seconds - forcing cleanup")
                        }
                        
                        // Don't reset audio source here - it will be properly set when starting next stream
                        // Resetting after stop/close can leave the source in a broken state
                        Log.i(TAG, "Stream confirmed stopped - audio/video sources will be reinitialized on next start")

                        Log.i(TAG, "Stream stop completed successfully")
                        
                        // Explicitly unlock stream rotation in the service
                        // This allows rotation to follow sensor again when truly stopped
                        service?.unlockStreamRotation()
                    } else {
                        Log.w(TAG, "serviceStreamer is null during cleanup - skipping streamer cleanup")
                    }
                } finally {
                    // ALWAYS clear cleanup flag - even if serviceStreamer was null or cleanup failed
                    // This prevents the flag from getting stuck and blocking future stream starts
                    service?.isCleanupInProgress = false
                    Log.i(TAG, "stopStream() - Cleared service.isCleanupInProgress, cleanup complete")
                }
            }
        }
    }

    /**
     * Handles connection loss and triggers automatic reconnection.
     * Inspired by Moblin's reconnection pattern with 5-second delay.
     * 
     * @param reason The reason for the disconnection
     * @param isInitialConnection If true, skips status check (for initial connection failures)
     */
    private fun handleDisconnection(reason: String, isInitialConnection: Boolean = false) {
        val userStopped = service?.userStoppedManually?.value ?: false
        val isCurrentlyReconnecting = service?.isReconnecting?.value ?: false
        Log.i(TAG, "handleDisconnection called: reason='$reason', isInitialConnection=$isInitialConnection, currentStatus=${streamStatus.value}, isReconnecting=$isCurrentlyReconnecting, userStopped=$userStopped")
        
        // Use service's centralized method to attempt beginning reconnection
        // This handles all the checks (userStoppedManually, isReconnecting=${service?.isReconnecting?.value}, cleanup in progress)
        val canReconnect = service?.beginReconnection(reason) ?: false
        if (!canReconnect) {
            Log.i(TAG, "Reconnection skipped by service (user stopped, already reconnecting, or cleanup in progress)")
            return
        }

        // Check if we should attempt reconnection
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.w(TAG, "Cannot reconnect: streamer not available")
            service?.cancelReconnection()
            return
        }

        lastDisconnectReason = reason
        
        // Clear any pending rotation changes - we want to maintain current locked orientation
        if (pendingTargetRotation != null) {
            Log.i(TAG, "Clearing pending rotation $pendingTargetRotation - maintaining locked orientation during reconnection")
            pendingTargetRotation = null
        }
        
        Log.i(TAG, "Connection lost: $reason - will attempt reconnection in 5 seconds")
        // Status and message already set by service.beginReconnection()
        // Just update our local _streamStatus to match
        service?.setStreamStatus(StreamStatus.CONNECTING)

        // Stop current stream cleanly before reconnecting
        viewModelScope.launch {
            try {
                // Cancel any RTMP retry/upgrade tasks before reconnecting
                // This prevents stale MediaProjection token from being used
                rtmpRetryJob?.cancel()
                rtmpRetryJob = null
                Log.d(TAG, "Cancelled RTMP retry job before reconnection")
                
                // For RTMP/Bitmap sources with MediaProjection audio: switch to microphone before close()
                // MediaProjection tokens become invalid after close(), so we can't reuse them
                // We'll upgrade back to MediaProjection after successful reconnection with a fresh token
                val currentVideoSource = currentStreamer.videoInput?.sourceFlow?.value
                val currentAudioSource = currentStreamer.audioInput?.sourceFlow?.value
                val isRtmpOrBitmap = currentVideoSource != null && currentVideoSource !is io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
                val hasMediaProjectionAudio = currentAudioSource?.javaClass?.simpleName?.contains("MediaProjection") == true
                
                if (isRtmpOrBitmap && hasMediaProjectionAudio) {
                    try {
                        currentStreamer.setAudioSource(MicrophoneSourceFactory())
                        needsMediaProjectionAudioRestore = true
                        Log.d(TAG, "Switched to microphone before reconnection (will restore MediaProjection after)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to switch to microphone before reconnection: ${e.message}")
                        needsMediaProjectionAudioRestore = false
                    }
                }
                
                // Clean up current connection
                // Only call stopStream() if stream was actually started
                // If open() failed, the stream is not running and stopStream() will hang
                val isCurrentlyStreaming = currentStreamer.isStreamingFlow.value == true
                if (isCurrentlyStreaming) {
                    Log.d(TAG, "Stopping failed stream before reconnection...")
                    currentStreamer.stopStream()
                    Log.d(TAG, "Stream stopped")
                } else {
                    Log.d(TAG, "Stream was never started - skipping stopStream()")
                }
                
                // Remove any bitrate regulator
                try {
                    currentStreamer.removeBitrateRegulatorController()
                    Log.d(TAG, "Bitrate regulator removed")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove bitrate regulator: ${e.message}")
                }
                
                // Close the endpoint connection before reconnecting
                // This ensures clean state for reconnection attempt
                try {
                    withTimeout(3000) {
                        currentStreamer.close()
                    }
                    Log.i(TAG, "Endpoint closed before reconnection - clean slate established")
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Failed to close endpoint before reconnection!", e)
                }

                // Wait for stream to actually stop before reconnecting
                // This ensures clean state for the reconnection attempt
                var retries = 0
                while (currentStreamer.isStreamingFlow.value == true && retries < 50) {
                    kotlinx.coroutines.delay(100)
                    retries++
                }
                
                if (currentStreamer.isStreamingFlow.value == true) {
                    Log.w(TAG, "Stream did not stop cleanly after 5 seconds during reconnection cleanup")
                }
                
                Log.d(TAG, "Stream confirmed stopped - ready for reconnection (keeping existing audio/video sources)")

                // NOTE: We do NOT reset audio/video sources during reconnection because:
                // - RTMP source needs to keep MediaProjection audio
                // - Camera source already has microphone audio
                // - Changing sources mid-reconnection can cause initialization issues
                // Sources are only reset during manual stopStream() to prepare for new configuration

                // Schedule reconnection after 5 seconds
                reconnectTimer.startSingleShot(timeoutSeconds = 5) {
                    // Double-check if user manually stopped during the delay (using service state)
                    val userStopped = service?.userStoppedManually?.value ?: false
                    if (userStopped) {
                        Log.d(TAG, "User stopped streaming during delay, cancelling reconnection")
                        service?.cancelReconnection()
                        return@startSingleShot
                    }
                    Log.i(TAG, "Attempting to reconnect after disconnection...")
                    attemptReconnection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during pre-reconnect cleanup: ${e.message}", e)
                service?.cancelReconnection()
                service?.setStreamStatus(StreamStatus.ERROR)
                _streamerErrorLiveData.postValue("Reconnection failed: ${e.message}")
            }
        }
    }

    /**
     * Attempts to reconnect to the stream endpoint.
     */
    private fun attemptReconnection() {
        viewModelScope.launch {
            try {
                // Check if user manually stopped streaming FIRST (using service state)
                val userStopped = service?.userStoppedManually?.value ?: false
                if (userStopped) {
                    Log.d(TAG, "User stopped streaming, cancelling reconnection attempt")
                    service?.cancelReconnection()
                    return@launch
                }
                
                // Check if cleanup is still in progress from a previous stop
                val cleanupInProgress = service?.isCleanupInProgress ?: false
                if (cleanupInProgress) {
                    Log.w(TAG, "Cleanup still in progress, delaying reconnection attempt")
                    // Reschedule after a short delay to let cleanup finish
                    reconnectTimer.startSingleShot(timeoutSeconds = 2) {
                        val stillUserStopped = service?.userStoppedManually?.value ?: false
                        if (!stillUserStopped) {
                            attemptReconnection()
                        }
                    }
                    return@launch
                }
                
                val currentStreamer = serviceStreamer
                if (currentStreamer == null) {
                    Log.w(TAG, "Reconnection failed: streamer no longer available")
                    service?.cancelReconnection()
                    service?.setStreamStatus(StreamStatus.ERROR)
                    return@launch
                }

                Log.i(TAG, "Executing reconnection attempt...")
                service?.setReconnectionMessage("Reconnecting...")
                
                // Set status to CONNECTING so UI shows stop button and it actually works
                service?.setStreamStatus(StreamStatus.CONNECTING)

                // Validate that both sources exist before attempting reconnection
                val (sourcesValid, sourceError) = validateSourcesConfigured(currentStreamer)
                if (!sourcesValid) {
                    Log.e(TAG, "Reconnection failed: $sourceError")
                    service?.setReconnectionMessage("Reconnection failed. Sources not configured")
                    service?.cancelReconnection()
                    service?.setStreamStatus(StreamStatus.ERROR)
                    return@launch
                }
                
                // During reconnection, DON'T reset audio source - it may invalidate MediaProjection token
                // The audio source is already correctly configured from before the disconnection
                val currentVideoSource = currentStreamer.videoInput?.sourceFlow?.value!!
                val currentAudioSource = currentStreamer.audioInput?.sourceFlow?.value!!
                
                Log.d(TAG, "Reconnection video source: ${currentVideoSource.javaClass.simpleName}")
                Log.d(TAG, "Reconnection audio source: ${currentAudioSource.javaClass.simpleName} (keeping existing)")

                // Use the same stream start logic as initial connection
                // Pass shouldAutoRetry=true to suppress error dialogs during reconnection
                val success = doStartStream(shouldAutoRetry = true)
                
                if (success) {
                    Log.i(TAG, "Reconnection successful!")
                    service?.completeReconnection()
                    service?.setStreamStatus(StreamStatus.STREAMING)
                    
                    // Clear any stored rotation since we successfully reconnected with locked orientation
                    rotationIgnoredDuringReconnection = null
                    
                    // Restore MediaProjection audio if we switched to microphone before reconnection
                    if (needsMediaProjectionAudioRestore) {
                        Log.d(TAG, "Restoring MediaProjection audio after reconnection")
                        needsMediaProjectionAudioRestore = false
                        
                        try {
                            // Get MediaProjection from helper if we don't have it yet
                            val mediaProjection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                            if (mediaProjection != null) {
                                // Set MediaProjection audio source directly
                                currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
                                Log.i(TAG, "Restored MediaProjection audio after reconnection")
                            } else {
                                Log.w(TAG, "Could not restore MediaProjection audio: MediaProjection not available")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to restore MediaProjection audio: ${e.message}")
                        }
                    }
                } else {
                    // Check if user stopped before showing failure message (using service state)
                    val userStopped = service?.userStoppedManually?.value ?: false
                    if (userStopped) {
                        Log.d(TAG, "User stopped during reconnection failure handling, skipping retry")
                        service?.cancelReconnection()
                        return@launch
                    }
                    
                    Log.w(TAG, "Reconnection attempt failed - will retry again in 5 seconds")
                    service?.setReconnectionMessage("Reconnection failed. Retrying in 5 seconds")
                    
                    // Keep isReconnecting = true for retry attempt
                    // Schedule another reconnection attempt directly (don't call handleDisconnection as it would be blocked)
                    reconnectTimer.startSingleShot(timeoutSeconds = 5) {
                        Log.i(TAG, "Retrying reconnection after previous failure...")
                        attemptReconnection()
                    }
                    return@launch
                }
            } catch (e: Exception) {
                // Check if user stopped before showing failure message
                if (service?.userStoppedManually?.value == true) {
                    Log.d(TAG, "User stopped during reconnection exception handling, skipping retry")
                    // isReconnecting managed by service
                    _reconnectionStatusLiveData.value = null
                    return@launch
                }
                
                Log.e(TAG, "Reconnection attempt threw exception: ${e.message}", e)
                _reconnectionStatusLiveData.postValue("Reconnection failed. Retrying in 5 seconds")
                
                // Keep isReconnecting = true for retry attempt
                // Schedule another reconnection attempt directly
                reconnectTimer.startSingleShot(timeoutSeconds = 5) {
                    Log.i(TAG, "Retrying reconnection after exception...")
                    attemptReconnection()
                }
                return@launch
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
                    // Show toast message
                    val message = if (isMuted) {
                        application.getString(R.string.service_notification_action_mute)
                    } else {
                        application.getString(R.string.service_notification_action_unmute)
                    }
                    _toastMessageLiveData.postValue(message)
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
                // Show toast message
                val message = if (isMuted) {
                    application.getString(R.string.service_notification_action_mute)
                } else {
                    application.getString(R.string.service_notification_action_unmute)
                }
                _toastMessageLiveData.postValue(message)
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
                    // Add delay before switching camera to allow previous camera to fully release
                    // This prevents resource conflicts when hot-swapping cameras
                    delay(300)
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
                
                // Remove bitrate regulator if streaming with SRT
                removeBitrateRegulatorIfNeeded()
                
                // Switch only VIDEO to bitmap - keep existing audio source to avoid glitches
                currentStreamer.setVideoSource(BitmapSourceFactory(testBitmap))
                Log.i(TAG, "Switched to bitmap fallback (video only, keeping current audio source)")
                
                // Re-add bitrate regulator if streaming with SRT
                readdBitrateRegulatorIfNeeded()
                
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

    /**
     * Load available cameras and update LiveData
     */
    fun loadAvailableCameras() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraIds = cameraManager.cameraIdList
                
                val cameraList = cameraIds.mapNotNull { id ->
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val facingConst = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                        val facing = when (facingConst) {
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Back"
                            else -> "External"
                        }
                        
                        // Get FOV info
                        val focalArr = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val physicalSize = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) as? android.util.SizeF
                        val fovLabel = if (focalArr != null && focalArr.isNotEmpty()) {
                            val firstFocal = focalArr.first()
                            if (physicalSize != null) {
                                val sensorDiag = kotlin.math.sqrt(physicalSize.width * physicalSize.width + physicalSize.height * physicalSize.height)
                                val fovRad = 2.0 * kotlin.math.atan((sensorDiag / (2.0 * firstFocal)).toDouble())
                                val fovDeg = (fovRad * 180.0 / kotlin.math.PI).toInt()
                                "${fovDeg}°"
                            } else {
                                val formattedAll = focalArr.joinToString(",") { "%.1f".format(it) }
                                "f=${formattedAll}mm"
                            }
                        } else {
                            ""
                        }
                        
                        val displayName = "$facing ${fovLabel}".trim()
                        CameraInfo(id, displayName, facing)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get camera characteristics for $id: ${e.message}")
                        null
                    }
                }
                
                _availableCamerasLiveData.postValue(cameraList)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cameras: ${e.message}")
                _availableCamerasLiveData.postValue(emptyList())
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
                    
                    // Mark that user toggled RTMP ON
                    _userToggledRtmp.postValue(true)
                    _userToggledUvc.postValue(false)
                    
                    // Remember current camera ID before switching away
                    lastUsedCameraId = videoSource.cameraId
                    Log.d(TAG, "Saved camera ID for later: $lastUsedCameraId")

                    // Hide camera-specific sliders when switching away from camera
                    showZoomSlider.value = false
                    showExposureSlider.value = false
                    showLensDistanceSlider.value = false

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
                                        // Permission denied - turn off RTMP toggle
                                        _userToggledRtmp.postValue(false)
                                        _streamerErrorLiveData.postValue("MediaProjection permission denied - staying on camera source")
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

                        // Remove bitrate regulator if streaming with SRT
                        removeBitrateRegulatorIfNeeded()
                        
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
                        
                        // Re-add bitrate regulator if streaming with SRT
                        readdBitrateRegulatorIfNeeded()
                    }
                }
                else -> {
                    Log.i(TAG, "Switching from RTMP back to Camera source (streaming: $isCurrentlyStreaming)")

                    // Mark that user toggled RTMP OFF (back to camera)
                    _userToggledRtmp.postValue(false)

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

                    // Add delay before switching sources to allow RTMP/ExoPlayer to fully release
                    // This prevents resource conflicts when hot-swapping sources
                    kotlinx.coroutines.delay(300)
                    
                    // Remove bitrate regulator if streaming with SRT
                    removeBitrateRegulatorIfNeeded()
                    
                    // Switch to camera sources - restore last used camera if available
                    val cameraId = lastUsedCameraId
                    currentStreamer.setVideoSource(CameraSourceFactory(cameraId))
                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
                    if (cameraId != null) {
                        Log.i(TAG, "Switched back to camera video (restored camera: $cameraId) and microphone audio")
                    } else {
                        Log.i(TAG, "Switched to camera video (default camera) and microphone audio")
                    }
                    
                    // Re-add bitrate regulator if streaming with SRT
                    readdBitrateRegulatorIfNeeded()
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

    fun toggleUvcSource() {
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "Streamer service not available for UVC source toggle")
            _streamerErrorLiveData.postValue("Service not available")
            return
        }

        val videoSource = currentStreamer.videoInput?.sourceFlow?.value

        viewModelScope.launch {
            try {
                when (videoSource) {
                    is UvcVideoSource -> {
                        Log.i(TAG, "Switching from UVC to Camera source")
                        
                        // Mark that user toggled UVC OFF (back to camera)
                        _userToggledUvc.postValue(false)
                        
                        // Remove bitrate regulator if streaming with SRT
                        removeBitrateRegulatorIfNeeded()
                        
                        // Switch back to camera
                        delay(300)
                        currentStreamer.setVideoSource(CameraSourceFactory(lastUsedCameraId ?: application.cameras.firstOrNull() ?: "0"))
                        currentStreamer.setAudioSource(MicrophoneSourceFactory())
                        
                        // Re-add bitrate regulator if streaming with SRT
                        readdBitrateRegulatorIfNeeded()
                        
                        Log.i(TAG, "Switched to camera source")
                    }
                    is ICameraSource -> {
                        Log.i(TAG, "Switching from Camera to UVC source")
                        
                        // Remember current camera
                        lastUsedCameraId = videoSource.cameraId
                        Log.d(TAG, "Saved camera ID: $lastUsedCameraId")
                        
                        // Clean up existing UVC camera helper if it exists (might be stale after UvcTestActivity)
                        uvcCameraHelper?.let { existingHelper ->
                            try {
                                Log.d(TAG, "Releasing existing UVC camera helper")
                                existingHelper.stopPreview()
                                existingHelper.closeCamera()
                                existingHelper.release()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error releasing existing helper: ${e.message}")
                            }
                            uvcCameraHelper = null
                        }
                        
                        // Always create a fresh CameraHelper instance
                        uvcCameraHelper = com.herohan.uvcapp.CameraHelper().apply {
                            setStateCallback(object : com.herohan.uvcapp.ICameraHelper.StateCallback {
                                override fun onAttach(device: android.hardware.usb.UsbDevice) {
                                    Log.d(TAG, "UVC camera attached: ${device.deviceName}")
                                }
                                
                                override fun onDeviceOpen(device: android.hardware.usb.UsbDevice, isFirstOpen: Boolean) {
                                    Log.d(TAG, "UVC device opened (isFirstOpen=$isFirstOpen)")
                                    
                                    // If this is first open after permission grant, switch video source now
                                    if (isFirstOpen) {
                                        Log.i(TAG, "First open after permission grant - switching to UVC source")
                                        viewModelScope.launch {
                                            try {
                                                // Mark that user toggled UVC ON
                                                _userToggledUvc.postValue(true)
                                                _userToggledRtmp.postValue(false)
                                                
                                                // Remove bitrate regulator if streaming with SRT
                                                removeBitrateRegulatorIfNeeded()
                                                
                                                // Switch to UVC source
                                                delay(300)
                                                currentStreamer.setVideoSource(UvcVideoSource.Factory(this@apply))
                                                currentStreamer.setAudioSource(MicrophoneSourceFactory())
                                                
                                                // Re-add bitrate regulator if streaming with SRT
                                                readdBitrateRegulatorIfNeeded()
                                                
                                                Log.i(TAG, "Switched to UVC source after permission grant")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error switching to UVC after permission: ${e.message}", e)
                                                _streamerErrorLiveData.postValue("Failed to switch to UVC: ${e.message}")
                                            }
                                        }
                                    }
                                    
                                    // Open the camera after device is opened
                                    openCamera()
                                }
                                
                                override fun onCameraOpen(device: android.hardware.usb.UsbDevice) {
                                    Log.i(TAG, "UVC camera opened and ready")
                                    // Camera is now ready and streaming
                                    startPreview()
                                }
                                
                                override fun onCameraClose(device: android.hardware.usb.UsbDevice) {
                                    Log.d(TAG, "UVC camera closed")
                                }
                                
                                override fun onDeviceClose(device: android.hardware.usb.UsbDevice) {
                                    Log.d(TAG, "UVC device closed")
                                }
                                
                                override fun onDetach(device: android.hardware.usb.UsbDevice) {
                                    Log.w(TAG, "UVC camera detached - device=${device.deviceName}")
                                    
                                    // Only switch to fallback if UVC source is actually active
                                    // If UVC toggle is OFF, this detach might be from UvcTestActivity or other code
                                    val isUvcActive = _userToggledUvc.value ?: false
                                    if (!isUvcActive) {
                                        Log.d(TAG, "UVC toggle is OFF, ignoring detach event")
                                        return
                                    }
                                    
                                    Log.w(TAG, "UVC toggle is ON - switching to fallback (toggle stays ON)")
                                    viewModelScope.launch {
                                        try {
                                            RtmpSourceSwitchHelper.switchToBitmapFallback(
                                                currentStreamer,
                                                testBitmap,
                                                streamingMediaProjection,
                                                mediaProjectionHelper
                                            )
                                            // Keep UVC toggle ON - user can plug cable back in
                                            // and toggle will reconnect automatically
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error switching to fallback: ${e.message}", e)
                                        }
                                    }
                                }
                                
                                override fun onCancel(device: android.hardware.usb.UsbDevice) {
                                    Log.d(TAG, "UVC camera permission cancelled")
                                    _toastMessageLiveData.postValue("USB permission denied")
                                }
                            })
                        }
                        
                        // Get available UVC devices
                        val helper = uvcCameraHelper
                        if (helper == null) {
                            _streamerErrorLiveData.postValue("Failed to initialize UVC camera helper")
                            Log.e(TAG, "Failed to initialize camera helper")
                            return@launch
                        }
                        
                        val deviceList = helper.deviceList
                        if (deviceList.isNullOrEmpty()) {
                            _streamerErrorLiveData.postValue("No USB camera connected")
                            Log.w(TAG, "No UVC devices found")
                            return@launch
                        }
                        
                        // Select the first available device
                        val device = deviceList[0]
                        Log.i(TAG, "Selecting UVC device: ${device.deviceName}")
                        
                        // Check if we have permission for this USB device
                        val usbManager = application.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
                        if (!usbManager.hasPermission(device)) {
                            Log.d(TAG, "No USB permission yet - requesting permission")
                            _toastMessageLiveData.postValue("USB permission required")
                            // Request permission - onDeviceOpen will be called automatically after grant
                            helper.selectDevice(device) // This will trigger permission dialog
                            // The source switch will happen in onDeviceOpen callback when isFirstOpen=true
                            return@launch
                        }
                        
                        // We have permission - proceed with source switch
                        Log.d(TAG, "USB permission already granted - switching source now")
                        
                        // Mark that user toggled UVC ON (only after confirming device is available AND we have permission)
                        _userToggledUvc.postValue(true)
                        _userToggledRtmp.postValue(false)
                        
                        // Remove bitrate regulator if streaming with SRT
                        removeBitrateRegulatorIfNeeded()
                        
                        // Switch to UVC source first
                        delay(300)
                        currentStreamer.setVideoSource(UvcVideoSource.Factory(helper))
                        currentStreamer.setAudioSource(MicrophoneSourceFactory())
                        
                        // Re-add bitrate regulator if streaming with SRT
                        readdBitrateRegulatorIfNeeded()
                        
                        // Then select the device (this will trigger onDeviceOpen -> onCameraOpen)
                        helper.selectDevice(device)
                        
                        Log.i(TAG, "Switched to UVC source")
                    }
                    else -> {
                        // We're on bitmap or other source (likely bitmap fallback from disconnected UVC)
                        // Switch back to camera instead of trying to reconnect UVC
                        Log.i(TAG, "Switching from ${videoSource?.javaClass?.simpleName} to Camera source")
                        
                        // Clear UVC toggle flag (user wants camera now)
                        _userToggledUvc.postValue(false)
                        
                        // Remove bitrate regulator if streaming with SRT
                        removeBitrateRegulatorIfNeeded()
                        
                        delay(300)
                        currentStreamer.setVideoSource(CameraSourceFactory(lastUsedCameraId ?: application.cameras.firstOrNull() ?: "0"))
                        currentStreamer.setAudioSource(MicrophoneSourceFactory())
                        
                        // Re-add bitrate regulator if streaming with SRT
                        readdBitrateRegulatorIfNeeded()
                        
                        Log.i(TAG, "Switched to camera source")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling UVC source: ${e.message}", e)
                _streamerErrorLiveData.postValue("Failed to toggle UVC source: ${e.message}")
            }
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

    /**
     * Expose user toggle state for UVC button
     */
    val isUvcSource: LiveData<Boolean> = _userToggledUvc

    /**
     * Expose user toggle state for RTMP button
     */
    val isRtmpSource: LiveData<Boolean> = _userToggledRtmp

    /**
     * Show camera controls only when on camera source (neither RTMP nor UVC toggle is ON)
     */
    val showCameraControls: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        value = true // Initial value: show controls when both toggles are OFF
        
        var rtmpOn = _userToggledRtmp.value ?: false
        var uvcOn = _userToggledUvc.value ?: false
        
        addSource(_userToggledRtmp) { 
            rtmpOn = it ?: false
            value = !rtmpOn && !uvcOn
        }
        addSource(_userToggledUvc) { 
            uvcOn = it ?: false
            value = !rtmpOn && !uvcOn
        }
    }

    val isRtmpOrBitmapSource: LiveData<Boolean>
        get() = serviceReadyFlow.flatMapLatest { ready ->
            if (ready && serviceStreamer != null) {
                Log.d(TAG, "isRtmpOrBitmapSource: serviceStreamer available, checking video source")
                serviceStreamer!!.videoInput?.sourceFlow?.map { source ->
                    val isNotCamera = source !is ICameraSource && source != null
                    Log.d(TAG, "isRtmpOrBitmapSource: video source = $source, isRtmpOrBitmapSource = $isNotCamera")
                    isNotCamera
                } ?: kotlinx.coroutines.flow.flowOf(false)
            } else {
                Log.d(TAG, "isRtmpOrBitmapSource: service not ready or serviceStreamer null, returning false")
                kotlinx.coroutines.flow.flowOf(false)
            }
        }.asLiveData()

    val isFlashAvailable = MutableLiveData(false)
    fun toggleFlash() {
        cameraSettings?.let {
            try {
                it.flash.enable = !it.flash.enable
                
                // Show toast with flash state
                val message = if (it.flash.enable) "Torch: On" else "Torch: Off"
                _toastMessageLiveData.postValue(message)
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
                val newMode = awbModes[(index + 1) % awbModes.size]
                settings.whiteBalance.autoMode = newMode
                
                // Show toast with white balance mode name
                val modeName = getWhiteBalanceModeName(newMode)
                _toastMessageLiveData.postValue("White Balance: $modeName")
            } catch (t: Throwable) {
                Log.w(TAG, "toggleAutoWhiteBalanceMode failed (camera session may be closed): ${t.message}")
            }
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    private fun getWhiteBalanceModeName(mode: Int): String {
        return when (mode) {
            CaptureResult.CONTROL_AWB_MODE_OFF -> "Off"
            CaptureResult.CONTROL_AWB_MODE_AUTO -> "Auto"
            CaptureResult.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescent"
            CaptureResult.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
            CaptureResult.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm Fluorescent"
            CaptureResult.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
            CaptureResult.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
            CaptureResult.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
            CaptureResult.CONTROL_AWB_MODE_SHADE -> "Shade"
            else -> "Unknown"
        }
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
            return if (settings != null && settings.isActiveFlow.value) {
                settings.exposure.compensation * settings.exposure.availableCompensationStep.toFloat()
            } else {
                0f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                try {
                    settings.exposure.let {
                        if (settings.isActiveFlow.value) {
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
            return if (settings != null && settings.isActiveFlow.value) {
                settings.zoom.zoomRatio
            } else {
                1f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                try {
                    if (settings.isActiveFlow.value) {
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
                val newMode = afModes[(index + 1) % afModes.size]
                it.focus.autoMode = newMode
                
                // Show toast with auto focus mode name
                val modeName = getAutoFocusModeName(newMode)
                _toastMessageLiveData.postValue("Focus: $modeName")
                
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

    private fun getAutoFocusModeName(mode: Int): String {
        return when (mode) {
            CaptureResult.CONTROL_AF_MODE_OFF -> "Manual"
            CaptureResult.CONTROL_AF_MODE_AUTO -> "Auto"
            CaptureResult.CONTROL_AF_MODE_MACRO -> "Macro"
            CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "Continuous Video"
            CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "Continuous Picture"
            CaptureResult.CONTROL_AF_MODE_EDOF -> "EDOF"
            else -> "Unknown"
        }
    }

    val showLensDistanceSlider = MutableLiveData(false)
    val lensDistanceRange = MutableLiveData<Range<Float>>()
    var lensDistance: Float
        @Bindable get() {
            val settings = cameraSettings
            return if ((settings != null) &&
                settings.isActiveFlow.value
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
                        if (settings.isActiveFlow.value) {
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
        if (settings.isActiveFlow.value) {
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

    /**
     * Get user-friendly label for audio source type.
     * Only 2 sources are used in the app:
     * - MicrophoneSourceFactory: Phone Mic
     * - MediaProjectionAudioSourceFactory: Media Projection Audio
     */
    private fun getAudioSourceLabel(audioSource: Any?): String {
        return when {
            audioSource == null -> application.getString(R.string.audio_source_none)
            audioSource.javaClass.simpleName.contains("MediaProjection") -> 
                application.getString(R.string.audio_source_rtmp)
            else -> application.getString(R.string.audio_source_microphone)
        }
    }

    override fun onCleared() {
        super.onCleared()
        
        // Cancel any pending reconnection attempts
        reconnectTimer.stop()
        // isReconnecting managed by service
        rotationIgnoredDuringReconnection = null
        Log.i(TAG, "Cancelled reconnect timer in onCleared()")
        
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
        
        // Clean up UVC camera helper
        uvcCameraHelper?.let { helper ->
            try {
                helper.stopPreview()
                helper.closeCamera()
                helper.release()
                Log.i(TAG, "Released UVC camera helper in onCleared()")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release UVC camera helper: ${e.message}")
            }
        }
        uvcCameraHelper = null
        
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
