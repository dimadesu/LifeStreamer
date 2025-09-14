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
import com.dimadesu.lifestreamer.utils.ObservableViewModel
import com.dimadesu.lifestreamer.utils.dataStore
import com.dimadesu.lifestreamer.utils.isEmpty
import com.dimadesu.lifestreamer.utils.setNextCameraId
import com.dimadesu.lifestreamer.utils.toggleBackToFront
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.IAudioRecordSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSettings
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrameRateSupported
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import com.dimadesu.lifestreamer.services.CameraStreamerService
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.DefaultSrtBitrateRegulatorController
import com.dimadesu.lifestreamer.bitrate.MoblinSrtFightBitrateRegulatorController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)


class PreviewViewModel(private val application: Application) : ObservableViewModel() {
    private val storageRepository = DataStoreRepository(application, application.dataStore)
    private val rotationRepository = RotationRepository.getInstance(application)

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
    private var serviceConnection: ServiceConnection? = null
    private val _serviceReady = MutableStateFlow(false)
    private val streamerFlow = MutableStateFlow<SingleStreamer?>(null)
    
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
            
            // Add notification permission for Android 13+ (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            return permissions
        }

    // Streamer errors
    private val _streamerErrorLiveData: MutableLiveData<String> = MutableLiveData()
    val streamerErrorLiveData: LiveData<String> = _streamerErrorLiveData
    private val _endpointErrorLiveData: MutableLiveData<String> = MutableLiveData()
    val endpointErrorLiveData: LiveData<String> = _endpointErrorLiveData

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
                    streamerFlow.value = serviceStreamer
                    _serviceReady.value = true
                    Log.i(TAG, "CameraStreamerService connected and ready - streaming state: ${binder.streamer.isStreamingFlow.value}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "CameraStreamerService disconnected: $name")
                serviceStreamer = null
                streamerService = null
                streamerFlow.value = null
                _serviceReady.value = false
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
                    serviceStreamer?.isStreamingFlow?.collect {
                        Log.i(TAG, "Streamer is streaming: $it")
                    }
                }
            }
        }
        viewModelScope.launch {
            rotationRepository.rotationFlow
                .collect {
                    serviceStreamer?.setTargetRotation(it)
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
            try {
                val descriptor = storageRepository.endpointDescriptorFlow.first()
                Log.i(TAG, "Starting stream with descriptor: $descriptor")
                val success = startServiceStreaming(descriptor)
                if (!success) {
                    Log.e(TAG, "Stream start failed - startServiceStreaming returned false")
                    _streamerErrorLiveData.postValue("Failed to start stream")
                    return@launch
                }
                Log.i(TAG, "Stream started successfully")

                if (descriptor.type.sinkType == MediaSinkType.SRT) {
                    val bitrateRegulatorConfig =
                        storageRepository.bitrateRegulatorConfigFlow.first()
                    if (bitrateRegulatorConfig != null) {
                        Log.i(TAG, "Add Moblin SrtFight bitrate regulator controller")
                        // Read user preference for regulator mode (fast/slow/belabox)
                        val selectedMode = storageRepository.regulatorModeFlow.first()
                        streamer?.addBitrateRegulatorController(
                            MoblinSrtFightBitrateRegulatorController.Factory(
                                bitrateRegulatorConfig = bitrateRegulatorConfig,
                                mode = selectedMode
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startStream failed", e)
                _streamerErrorLiveData.postValue("startStream: ${e.message ?: "Unknown error"}")
            } finally {
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
            } finally {
                _isTryingConnectionLiveData.postValue(false)
            }
        }
    }

    fun setMute(isMuted: Boolean) {
        streamer?.audioInput?.isMuted = isMuted
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

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleVideoSource() {
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "Streamer service not available for video source toggle")
            _streamerErrorLiveData.postValue("Service not available")
            return
        }
        
        val videoSource = currentStreamer.videoInput?.sourceFlow?.value
        val isCurrentlyStreaming = isStreamingLiveData.value == true
        
        viewModelScope.launch {
            when (videoSource) {
                is ICameraSource -> {
                    Log.i(TAG, "Switching from Camera to Bitmap source (streaming: $isCurrentlyStreaming)")
                    
                    // If we're currently streaming, temporarily stop to prepare for source switch
                    var wasStreaming = false
                    if (isCurrentlyStreaming) {
                        Log.i(TAG, "Temporarily stopping camera stream for source switch")
                        wasStreaming = true
                        try {
                            stopServiceStreaming()
                            // Brief delay to ensure clean stop
                            kotlinx.coroutines.delay(100)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
                        }
                    }
                    
                    // Switch to bitmap source
                    currentStreamer.setVideoSource(BitmapSourceFactory(testBitmap))
                    
                    // If we were streaming before, restart with bitmap source
                    if (wasStreaming) {
                        Log.i(TAG, "Restarting stream with bitmap source")
                        try {
                            // Small delay to let bitmap source initialize
                            kotlinx.coroutines.delay(300)
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            startServiceStreaming(descriptor)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restarting stream with bitmap: ${e.message}")
                            _streamerErrorLiveData.postValue("Failed to restart stream with bitmap: ${e.message}")
                        }
                    }
                }
                else -> {
                    Log.i(TAG, "Switching from Bitmap back to Camera source (streaming: $isCurrentlyStreaming)")
                    
                    // If we're currently streaming, we need to stop the current source first
                    var wasStreaming = false
                    if (isCurrentlyStreaming) {
                        Log.i(TAG, "Stopping bitmap streaming before switch")
                        wasStreaming = true
                        try {
                            stopServiceStreaming()
                            // Small delay to ensure stream stops properly
                            kotlinx.coroutines.delay(100)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
                        }
                    }
                    
                    // Switch to camera source
                    currentStreamer.setVideoSource(CameraSourceFactory())
                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
                    
                    // If we were streaming before, restart with camera
                    if (wasStreaming) {
                        Log.i(TAG, "Restarting stream with camera source")
                        try {
                            // Small delay to let camera source initialize
                            kotlinx.coroutines.delay(200)
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            startServiceStreaming(descriptor)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restarting stream with camera: ${e.message}")
                            _streamerErrorLiveData.postValue("Failed to restart stream with camera: ${e.message}")
                        }
                    }
                }
            }
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
        
        Log.i(TAG, "PreviewViewModel cleared but service continues running for background streaming")
    }

    companion object {
        private const val TAG = "PreviewViewModel"
    }
}
