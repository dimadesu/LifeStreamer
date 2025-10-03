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
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.PI
import androidx.appcompat.app.AlertDialog
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ToggleButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.dimadesu.lifestreamer.ApplicationConstants
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.databinding.MainFragmentBinding
import com.dimadesu.lifestreamer.models.StreamStatus
import com.dimadesu.lifestreamer.utils.DialogUtils
import com.dimadesu.lifestreamer.utils.PermissionManager
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import io.github.thibaultbee.streampack.core.interfaces.IStreamer
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IPreviewableSource
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import com.dimadesu.lifestreamer.ui.views.PreviewView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

class PreviewFragment : Fragment(R.layout.main_fragment) {
    private lateinit var binding: MainFragmentBinding

    private val previewViewModel: PreviewViewModel by viewModels {
        PreviewViewModelFactory(requireActivity().application)
    }

    // MediaProjection permission launcher - connects to MediaProjectionHelper
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    // UI messages from service (notification-start feedback)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MediaProjection launcher with helper
        mediaProjectionLauncher = previewViewModel.mediaProjectionHelper.registerLauncher(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = this
        binding.viewmodel = previewViewModel
        bindProperties()
        return binding.root
    }

    @SuppressLint("MissingPermission")
    private fun bindProperties() {
        binding.liveButton.setOnClickListener { view ->
            view as ToggleButton
            Log.d(TAG, "Live button clicked - isChecked: ${view.isChecked}, streaming: ${previewViewModel.isStreamingLiveData.value}, trying: ${previewViewModel.isTryingConnectionLiveData.value}")
            
            // Check current state to determine action
            val isCurrentlyStreaming = previewViewModel.isStreamingLiveData.value == true
            val isTryingConnection = previewViewModel.isTryingConnectionLiveData.value == true
            
            // Also check the actual streamer state directly as backup
            val actualStreamingState = previewViewModel.serviceStreamer?.isStreamingFlow?.value == true
            Log.d(TAG, "Live button - actualStreamingState from serviceStreamer: $actualStreamingState")
            
            // Use either LiveData or direct streamer state
            val isReallyStreaming = isCurrentlyStreaming || actualStreamingState
            
            if (!isReallyStreaming && !isTryingConnection) {
                // Not streaming and not trying - start stream
                Log.d(TAG, "Starting stream...")
                // Set button to "Stop" immediately and keep it there
                view.isChecked = true
                startStreamIfPermissions(previewViewModel.requiredPermissions)
            } else if (isReallyStreaming || isTryingConnection) {
                // Streaming or trying to connect - stop stream
                Log.d(TAG, "Stopping stream...")
                // Set button to "Start" immediately
                view.isChecked = false
                stopStream()
            } else {
                Log.w(TAG, "Uncertain state - button clicked but unclear action needed")
                // Reset button to reflect actual state
                view.isChecked = isReallyStreaming || isTryingConnection
            }
        }

        binding.switchCameraButton.setOnClickListener {
            showCameraSelectionDialog()
        }

        binding.switchSourceButton.setOnClickListener {
            previewViewModel.toggleVideoSource(mediaProjectionLauncher)
        }

        previewViewModel.streamerErrorLiveData.observe(viewLifecycleOwner) {
            showError("Oops", it)
        }

        previewViewModel.endpointErrorLiveData.observe(viewLifecycleOwner) {
            showError("Endpoint error", it)
        }

        previewViewModel.isStreamingLiveData.observe(viewLifecycleOwner) { isStreaming ->
            Log.d(TAG, "Streaming state changed to: $isStreaming")
            if (isStreaming) {
                lockOrientation()
            } else {
                unlockOrientation()
            }
            if (isStreaming) {
                // Ensure button shows "Stop" when definitely streaming
                if (!binding.liveButton.isChecked) {
                    Log.d(TAG, "Streaming confirmed - ensuring button shows Stop")
                    binding.liveButton.isChecked = true
                }
            } else {
                // Only set to "Start" if we're not in a connecting state
                if (previewViewModel.isTryingConnectionLiveData.value != true && binding.liveButton.isChecked) {
                    Log.d(TAG, "Streaming stopped and not trying - ensuring button shows Start")
                    binding.liveButton.isChecked = false
                }
            }
        }

        previewViewModel.isTryingConnectionLiveData.observe(viewLifecycleOwner) { isWaitingForConnection ->
            Log.d(TAG, "Trying connection state changed to: $isWaitingForConnection")
            // Don't change button state here - let the click handler manage it
            // if (isWaitingForConnection) {
            //     binding.liveButton.isChecked = true
            // } else if (previewViewModel.isStreamingLiveData.value == true) {
            //     binding.liveButton.isChecked = true
            // } else {
            //     binding.liveButton.isChecked = false
            // }
        }

        // Observe streamStatus to handle error states properly
        lifecycleScope.launch {
            previewViewModel.streamStatus.collect { status ->
                Log.d(TAG, "Stream status changed to: $status")
                when (status) {
                    StreamStatus.ERROR, StreamStatus.NOT_STREAMING -> {
                        // Reset button to "Start" state when error occurs or stream stops
                        if (binding.liveButton.isChecked) {
                            Log.d(TAG, "Stream error/stopped - resetting button to Start")
                            binding.liveButton.isChecked = false
                        }
                    }
                    StreamStatus.STARTING, StreamStatus.CONNECTING -> {
                        // Keep button in "Stop" state during connection attempts
                        if (!binding.liveButton.isChecked) {
                            Log.d(TAG, "Stream starting/connecting - setting button to Stop")
                            binding.liveButton.isChecked = true
                        }
                    }
                    StreamStatus.STREAMING -> {
                        // Ensure button shows "Stop" when streaming
                        if (!binding.liveButton.isChecked) {
                            Log.d(TAG, "Stream active - ensuring button shows Stop")
                            binding.liveButton.isChecked = true
                        }
                    }
                }
            }
        }

        previewViewModel.streamerLiveData.observe(viewLifecycleOwner) { streamer ->
            if (streamer is IStreamer) {
                // TODO: For background streaming, we don't want to automatically stop streaming
                // when the app goes to background. The service should handle this.
                // TODO: Remove this observer when streamer is released
                // lifecycle.addObserver(StreamerViewModelLifeCycleObserver(streamer))
                Log.d(TAG, "Streamer lifecycle observer disabled for background streaming support")
            } else {
                Log.e(TAG, "Streamer is not a ICoroutineStreamer")
            }
            if (streamer is IWithVideoSource) {
                    val videoSource = streamer.videoInput?.sourceFlow?.value
                    val isRtmpSource = videoSource is RTMPVideoSource

                    // For RTMP sources, we can enable preview alongside streaming
                    // thanks to the surface processor that handles dual output
                    if (streamer.isStreamingFlow.value == true && !isRtmpSource) {
                        Log.i(TAG, "Streamer is streaming - skipping preview while live (non-RTMP source)")
                        // Ensure preview view has no streamer assigned while streaming
                        try {
                            if (binding.preview.streamer == streamer) {
                                binding.preview.streamer = null
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to clear preview streamer while streaming: ${t.message}")
                        }
                    } else {
                        if (isRtmpSource && streamer.isStreamingFlow.value == true) {
                            Log.i(TAG, "RTMP source streaming - preview enabled alongside streaming")
                        }
                        inflateStreamerPreview(streamer)
                    }
            } else {
                Log.e(TAG, "Can't start preview, streamer is not a IVideoStreamer")
            }
        }

        // Rebind preview when streaming stops so the UI preview returns to normal
        // For RTMP sources, preview continues during streaming, so no need to rebind
        previewViewModel.isStreamingLiveData.observe(viewLifecycleOwner) { isStreaming ->
            if (isStreaming == false) {
                Log.d(TAG, "Streaming stopped - re-attaching preview if possible")
                val streamer = previewViewModel.streamerLiveData.value
                val videoSource = (streamer as? IWithVideoSource)?.videoInput?.sourceFlow?.value
                val isRtmpSource = videoSource is RTMPVideoSource

                if (!isRtmpSource) {
                    // Only rebind for non-RTMP sources since RTMP sources maintain preview during streaming
                    try {
                        inflateStreamerPreview()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to re-attach preview after stop: ${t.message}")
                    }
                } else {
                    Log.d(TAG, "RTMP source - preview was maintained during streaming, no rebind needed")
                }
            }
        }

        // Show current bitrate if available (render nothing when null)
        previewViewModel.bitrateLiveData.observe(viewLifecycleOwner) { text ->
            try {
                binding.bitrateText.text = text ?: ""
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to update bitrate text: ${t.message}")
            }
        }
    }

    private fun lockOrientation() {
        /**
         * Lock orientation while stream is running to avoid stream interruption if
         * user turns the device.
         * For landscape only mode, set [requireActivity().requestedOrientation] to
         * [ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE].
         */
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun unlockOrientation() {
        requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
    }

    private fun startStream() {
        Log.d(TAG, "startStream() called - checking if MediaProjection is required")

        // Check if MediaProjection is required for this streaming setup
        if (previewViewModel.requiresMediaProjection()) {
            Log.d(TAG, "MediaProjection required - using startStreamWithMediaProjection")
            // Use MediaProjection-enabled streaming for RTMP sources
            previewViewModel.startStreamWithMediaProjection(
                mediaProjectionLauncher,
                onSuccess = {
                    Log.d(TAG, "MediaProjection stream started successfully")
                },
                onError = { error ->
                    Log.e(TAG, "MediaProjection stream failed: $error")
                    showError("Streaming Error", error)
                }
            )
        } else {
            Log.d(TAG, "Regular streaming - using standard startStream")
            // Use the main startStream method for camera sources
            previewViewModel.startStream()
        }
    }

    private fun stopStream() {
        previewViewModel.stopStream()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun showPermissionError(vararg permissions: String) {
        Log.e(TAG, "Permission not granted: ${permissions.joinToString { ", " }}")
        DialogUtils.showPermissionAlertDialog(requireContext())
    }

    private fun showError(title: String, message: String) {
        Log.e(TAG, "Error: $title, $message")
        DialogUtils.showAlertDialog(requireContext(), "Error: $title", message)
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        requestCameraAndMicrophonePermissions()
    }

    override fun onPause() {
        super.onPause()
        // DO NOT stop streaming when going to background - the service should continue streaming
        // DO NOT stop preview either when the camera is being used for streaming -
        // the camera source is shared between preview and streaming, so stopping preview
        // would also stop the streaming. Instead, let the preview continue running.
        Log.d(TAG, "onPause() - app going to background, keeping both preview and stream active via service")
        
        // Note: We used to stop preview here, but that was causing streaming to stop
        // because the camera source is shared. For background streaming to work properly,
        // we need to keep the camera active.
        // stopStream()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() - app returning to foreground, preview should already be active")
        
        // Since we no longer stop preview in onPause(), the preview should still be running
        // We only need to ensure preview is started if it's not already running
        if (PermissionManager.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            // Use the same guarded preview inflation logic to avoid races when the
            // activity is re-created or the SurfaceView is being detached/attached
            // (for example when tapping the notification). inflateStreamerPreview()
            // sets the streamer and starts the preview only when safe.
            try {
                inflateStreamerPreview()
            } catch (e: Exception) {
                Log.w(TAG, "Error while inflating/starting preview on resume: ${e.message}")
            }

            // Re-request audio focus / handle foreground recovery if streaming
            val isCurrentlyStreaming = previewViewModel.isStreamingLiveData.value ?: false
            if (isCurrentlyStreaming) {
                Log.d(TAG, "App returned to foreground while streaming - handling recovery")
                previewViewModel.service?.let { service ->
                    try {
                        service.handleForegroundRecovery()
                        Log.d(TAG, "Foreground recovery completed")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Foreground recovery failed: ${t.message}")
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun inflateStreamerPreview() {
        val streamer = previewViewModel.streamerLiveData.value
        if (streamer is SingleStreamer) {
            inflateStreamerPreview(streamer)
        } else {
            Log.e(TAG, "Can't start preview, streamer is not a SingleStreamer")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun inflateStreamerPreview(streamer: SingleStreamer) {
        val preview = binding.preview
        // Set camera settings button when camera is started
        preview.listener = object : PreviewView.Listener {
            override fun onPreviewStarted() {
                Log.i(TAG, "Preview started")
            }

            override fun onZoomRationOnPinchChanged(zoomRatio: Float) {
                previewViewModel.onZoomRationOnPinchChanged()
            }
        }

        // If the preview already uses the same streamer, no need to set it again.
        var assignedStreamer = false
        if (preview.streamer == streamer) {
            Log.d(TAG, "Preview already bound to streamer - skipping set")
            assignedStreamer = true
        } else {
            // Defensive check: PreviewView will throw if the new source is already
            // previewing. Avoid setting the streamer in that case because the
            // preview is already active and managed elsewhere (service/background).
            val newSource = streamer.videoInput?.sourceFlow?.value as? IPreviewableSource
            if (newSource?.isPreviewingFlow?.value == true) {
                Log.w(TAG, "New streamer's video source is already previewing - skipping set to avoid exception")
            } else {
                // Wait till streamer exists to set it to the SurfaceView. Setting the
                // `streamer` property requires the PreviewView to have its internal
                // coroutine scope (set in onAttachedToWindow). If the view is not yet
                // attached, posting the assignment ensures it happens on the UI
                // thread after attachment, preventing "lifecycleScope is not
                // available" errors.
                try {
                    if (preview.isAttachedToWindow) {
                        preview.streamer = streamer
                        assignedStreamer = true
                    } else {
                        preview.post {
                            try {
                                preview.streamer = streamer
                                Log.d(TAG, "Preview streamer assigned via post after attach")
                                // Start preview asynchronously after assignment
                                lifecycleScope.launch {
                                    try {
                                        startPreviewWhenReady(preview, streamer, posted = true)
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "Posted preview start failed: ${t.message}")
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to set streamer on preview (posted): ${t.message}")
                            }
                        }
                        // We optimistically mark as assigned since assignment will occur shortly
                        assignedStreamer = true
                    }
                } catch (e: IllegalArgumentException) {
                    // Defensive catch: if PreviewView rejects the streamer because the
                    // source is already previewing, log and continue without crashing.
                    Log.w(TAG, "Failed to set streamer on preview: ${e.message}")
                }
            }
        }

        // Only start preview if we actually have the streamer assigned (or it was
        // already assigned). This prevents calling startPreview() when the view
        // hasn't been bound and would throw "Streamer is not set".
        // If streamer was assigned, try to start preview when both the UI surface and
        // the video source are ready. This adds an app-level readiness gate without
        // modifying StreamPack.
        if (assignedStreamer && PermissionManager.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            // Retry starting preview locally without modifying StreamPack.
            // Check common surface readiness signals (view attached + surface holder valid)
            lifecycleScope.launch {
                startPreviewWhenReady(preview, streamer, posted = false)
            }
        } else if (!PermissionManager.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            Log.e(TAG, "Camera permission not granted. Preview will not start.")
        } else {
            Log.d(TAG, "Preview streamer not assigned - skipping startPreview()")
        }
    }

    // Helper: tries to start preview when the view is attached and sized.
    // If `posted` is true, the helper logs slightly different messages to
    // make the log traces easier to read. Behavior mirrors the previous loops
    // (same delays and attempt counts).
    private suspend fun startPreviewWhenReady(preview: PreviewView, streamer: SingleStreamer, posted: Boolean) {
        val maxAttempts = if (posted) 6 else 10
        var attempt = 1
        var started = false
        val source = streamer.videoInput?.sourceFlow?.value as? IPreviewableSource

        while (attempt <= maxAttempts && !started) {
            try {
                val isAttached = preview.isAttachedToWindow
                val hasSize = preview.width > 0 && preview.height > 0
                if (!isAttached || !hasSize) {
                    val which = if (posted) "posted start" else "start"
                    Log.d(TAG, "Preview not attached or has no size ($which attempt=$attempt) - will retry")
                } else {
                    val sourceAlreadyPreviewing = source?.isPreviewingFlow?.value == true
                    if (sourceAlreadyPreviewing) {
                        Log.w(TAG, "Source is already previewing elsewhere - skipping startPreview${if (posted) " (posted)" else ""}")
                        started = true
                        break
                    }
                    try {
                        preview.startPreview()
                        Log.d(TAG, "Preview started (${if (posted) "posted " else ""}attempt=$attempt)")
                        started = true
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "startPreview ${if (posted) "(posted)" else ""} attempt=$attempt failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error checking/starting preview on attempt=$attempt: ${t.message}")
            }
            attempt++
            delay(250)
        }

        if (!started) {
            Log.e(TAG, "Failed to start preview after $maxAttempts attempts")
        }
    }

    private fun startStreamIfPermissions(permissions: List<String>) {
        when {
            PermissionManager.hasPermissions(
                requireContext(), *permissions.toTypedArray()
            ) -> {
                // Log detailed permission status before starting stream
                permissions.forEach { permission ->
                    val isGranted = ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
                    Log.i(TAG, "Permission $permission: granted=$isGranted")
                }
                
                // Special check for RECORD_AUDIO AppOps
                if (permissions.contains(Manifest.permission.RECORD_AUDIO)) {
                    try {
                        val appOpsManager = requireContext().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            appOpsManager.checkOpNoThrow(
                                AppOpsManager.OPSTR_RECORD_AUDIO,
                                android.os.Process.myUid(),
                                requireContext().packageName
                            )
                        } else {
                            AppOpsManager.MODE_ALLOWED
                        }
                        Log.i(TAG, "RECORD_AUDIO AppOps mode: $mode (${if (mode == AppOpsManager.MODE_ALLOWED) "ALLOWED" else "BLOCKED"})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to check RECORD_AUDIO AppOps", e)
                    }
                }
                
                startStream()
            }

            else -> {
                Log.w(TAG, "Missing permissions, requesting: ${permissions.joinToString()}")
                requestLiveStreamPermissionsLauncher.launch(
                    permissions.toTypedArray()
                )
            }
        }
    }

    private fun showCameraSelectionDialog() {
        val ctx = requireContext()
        val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = try {
            cameraManager.cameraIdList
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to list camera ids: ${t.message}")
            return
        }

        if (cameraIds.isEmpty()) {
            Log.w(TAG, "No camera devices available on this device")
            return
        }

        val items = cameraIds.map { id ->
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facingConst = chars.get(CameraCharacteristics.LENS_FACING)
                val facing = when (facingConst) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    else -> "External"
                }

                // Use available focal lengths only. Sensor physical size isn't always
                // provided on all devices, and focal length values alone are often
                // the most consistently available data we can show.
                val focalArr = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) as? SizeF
                val fovLabel = if (focalArr != null && focalArr.isNotEmpty()) {
                    // Prefer showing diagonal FOV when sensor physical size is available.
                    val firstFocal = focalArr.first()
                    val focalFormatted = "%.1f".format(firstFocal)
                    if (physicalSize != null) {
                        val sensorDiag = sqrt(physicalSize.width * physicalSize.width + physicalSize.height * physicalSize.height)
                        // diagonal FOV in degrees = 2 * atan(sensorDiag / (2 * focal))
                        val fovRad = 2.0 * atan((sensorDiag / (2.0 * firstFocal)).toDouble())
                        val fovDeg = (fovRad * 180.0 / PI).toInt()
                        "${fovDeg}°"
                    } else {
                        // If physical size is missing, show available focal(s)
                        val formattedAll = focalArr.joinToString(",") { "%.1f".format(it) }
                        "f=${formattedAll}mm"
                    }
                } else {
                    "focal unknown"
                }

                "$id $facing $fovLabel"
            } catch (t: Throwable) {
                "Camera $id"
            }
        }.toTypedArray()

        // Determine currently selected camera id if any
        val currentSource = previewViewModel.streamer?.videoInput?.sourceFlow?.value
        val currentCameraId = (currentSource as? ICameraSource)?.cameraId
        val currentIndex = if (currentCameraId != null) cameraIds.indexOf(currentCameraId).coerceAtLeast(0) else -1

        AlertDialog.Builder(ctx)
            .setTitle("Select camera")
            .setSingleChoiceItems(items, if (currentIndex >= 0) currentIndex else -1) { dialog, which ->
                val selectedId = cameraIds[which]
                lifecycleScope.launch {
                    try {
                        (previewViewModel.streamer as? IWithVideoSource)?.setCameraId(selectedId)
                        // Update button text to show chosen camera and orientation
                        binding.switchSourceButton.contentDescription = items[which]
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to set camera id $selectedId: ${t.message}")
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun requestCameraAndMicrophonePermissions() {
        // Include POST_NOTIFICATIONS on API 33+ so the app asks for it during app open
        val permissionsToCheck = mutableListOf<String>().apply {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        when {
            PermissionManager.hasPermissions(
                requireContext(), *permissionsToCheck.toTypedArray()
            ) -> {
                inflateStreamerPreview()
                // Don't call configureAudio() here - it will be handled by service connection
                // when the service is ready and only if not already streaming
                // previewViewModel.configureAudio()
                previewViewModel.initializeVideoSource()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionError(Manifest.permission.RECORD_AUDIO)
                requestCameraAndMicrophonePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionError(Manifest.permission.CAMERA)
                requestCameraAndMicrophonePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA
                    )
                )
            }

            else -> {
                requestCameraAndMicrophonePermissionsLauncher.launch(
                    permissionsToCheck.toTypedArray()
                )
            }
        }
    }

    private val requestLiveStreamPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val missingPermissions = permissions.toList().filter {
            !it.second
        }.map { it.first }

        if (missingPermissions.isEmpty()) {
            startStream()
        } else {
            showPermissionError(*missingPermissions.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private val requestCameraAndMicrophonePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val missingPermissions = permissions.toList().filter {
            !it.second
        }.map { it.first }

        if (permissions[Manifest.permission.CAMERA] == true) {
            inflateStreamerPreview()
            previewViewModel.initializeVideoSource()
        } else if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            // Don't call configureAudio() here - it will be handled by service connection
            // when the service is ready and only if not already streaming
            // previewViewModel.configureAudio()
            Log.d(TAG, "RECORD_AUDIO permission granted - audio will be configured via service")
        }
        // POST_NOTIFICATIONS is optional for preview; if granted, we can create
        // or update our notification channel. If not granted, continue normally.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
            Log.d(TAG, "POST_NOTIFICATIONS granted=$notifGranted")
            // Optionally create silent notification channel here if granted
            if (notifGranted) {
                try {
                    previewViewModel.service?.let { service ->
                        // Ensure channel exists
                        service.run {
                            // customNotificationUtils exists in the service - calling via reflection
                            // would be heavy; just log for now and allow service to recreate channel when needed
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to ensure notification channel after permission grant: ${t.message}")
                }
            }
        }
        if (missingPermissions.isNotEmpty()) {
            showPermissionError(*missingPermissions.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "PreviewFragment"
    }
}
