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
import io.github.thibaultbee.streampack.core.interfaces.IStreamer
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IPreviewableSource
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast

class PreviewFragment : Fragment(R.layout.main_fragment) {
    private lateinit var binding: MainFragmentBinding

    private val previewViewModel: PreviewViewModel by viewModels {
        PreviewViewModelFactory(requireActivity().application)
    }

    // Remember the orientation AND rotation that was locked when streaming started
    // This allows us to restore the exact same orientation when returning from background
    private var rememberedLockedOrientation: Int? = null
    private var rememberedRotation: Int? = null

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
        binding.liveButton.setOnClickListener {
            // Use streamStatus as single source of truth for determining action
            val currentStatus = previewViewModel.streamStatus.value
            Log.d(TAG, "Live button clicked - currentStatus: $currentStatus")
            
            when (currentStatus) {
                StreamStatus.NOT_STREAMING, StreamStatus.ERROR -> {
                    // Start streaming
                    Log.d(TAG, "Starting stream...")
                    startStreamIfPermissions(previewViewModel.requiredPermissions)
                }
                StreamStatus.STARTING, StreamStatus.CONNECTING, StreamStatus.STREAMING -> {
                    // Stop streaming or cancel connection attempt
                    Log.d(TAG, "Stopping stream...")
                    stopStream()
                }
            }
            // Note: Button state will be updated by streamStatus observer
        }

        // Commented out along with the switchCameraButton in XML
        /*
        binding.switchCameraButton.setOnClickListener {
            showCameraSelectionDialog()
        }
        */

        binding.switchSourceButton.setOnClickListener {
            previewViewModel.toggleVideoSource(mediaProjectionLauncher)
        }

        previewViewModel.streamerErrorLiveData.observe(viewLifecycleOwner) { error ->
            error?.let {
                showError("Oops", it)
                previewViewModel.clearStreamerError() // Clear after showing to prevent re-show on rotation
            }
        }

        previewViewModel.endpointErrorLiveData.observe(viewLifecycleOwner) { error ->
            error?.let {
                showError("Endpoint error", it)
                previewViewModel.clearEndpointError() // Clear after showing to prevent re-show on rotation
            }
        }

        previewViewModel.toastMessageLiveData.observe(viewLifecycleOwner) { message ->
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                previewViewModel.clearToastMessage() // Clear after showing to prevent re-show on rotation
            }
        }

        // Reconnection status is now displayed via data binding in the layout XML
        // No need for manual observer - the TextView will automatically show/hide

        // Lock/unlock orientation based on streaming state and reconnection status
        // Keep orientation locked during STARTING, CONNECTING, and STREAMING
        previewViewModel.isStreamingLiveData.observe(viewLifecycleOwner) { isStreaming ->
            val currentStatus = previewViewModel.streamStatus.value
            val isReconnecting = previewViewModel.isReconnectingLiveData.value ?: false
            Log.d(TAG, "Streaming state changed to: $isStreaming, status: $currentStatus, reconnecting: $isReconnecting")
            if (isStreaming) {
                // Check if Service already has a saved orientation (lifecycle restoration)
                // If so, don't lock again - onStart()/onResume() will restore it
                if (shouldLockOrientation("isStreamingLiveData observer")) {
                    lockOrientation()
                }
            } else {
                // Only unlock if we're truly stopped AND not reconnecting
                val shouldStayLocked = currentStatus == com.dimadesu.lifestreamer.models.StreamStatus.STARTING ||
                                      currentStatus == com.dimadesu.lifestreamer.models.StreamStatus.CONNECTING ||
                                      isReconnecting
                if (!shouldStayLocked) {
                    unlockOrientation()
                } else {
                    Log.d(TAG, "Keeping orientation locked - status: $currentStatus, reconnecting: $isReconnecting")
                }
            }
        }
        
        // Also observe streamStatus to handle orientation during state transitions
        lifecycleScope.launch {
            previewViewModel.streamStatus.collect { status ->
                when (status) {
                    com.dimadesu.lifestreamer.models.StreamStatus.STARTING -> {
                        // Lock orientation as soon as we start attempting to stream
                        // But check Service first - if already saved, don't re-lock
                        if (shouldLockOrientation("STARTING")) {
                            lockOrientation()
                            Log.d(TAG, "Locked orientation during STARTING")
                        }
                    }
                    com.dimadesu.lifestreamer.models.StreamStatus.CONNECTING -> {
                        // Ensure orientation stays locked during reconnection
                        // But check Service first - if already saved, don't re-lock
                        if (shouldLockOrientation("CONNECTING")) {
                            lockOrientation()
                            Log.d(TAG, "Locked orientation during CONNECTING/reconnection")
                        }
                    }
                    com.dimadesu.lifestreamer.models.StreamStatus.NOT_STREAMING,
                    com.dimadesu.lifestreamer.models.StreamStatus.ERROR -> {
                        // Unlock orientation only when truly stopped and not reconnecting
                        val isReconnecting = previewViewModel.isReconnectingLiveData.value ?: false
                        if (previewViewModel.isStreamingLiveData.value == false && !isReconnecting) {
                            unlockOrientation()
                        } else {
                            Log.d(TAG, "Keeping lock despite $status - streaming: ${previewViewModel.isStreamingLiveData.value}, reconnecting: $isReconnecting")
                        }
                    }
                    com.dimadesu.lifestreamer.models.StreamStatus.STREAMING -> {
                        // Orientation should already be locked by isStreamingLiveData observer
                        // This is just a safety check - but DON'T re-lock if we already have one
                        // Check both Service and Fragment to avoid overwriting during lifecycle
                        if (shouldLockOrientation("STREAMING safety check")) {
                            lockOrientation()
                            Log.d(TAG, "Safety lock during STREAMING")
                        }
                    }
                }
            }
        }

        // Observe streamStatus as single source of truth for button state
        lifecycleScope.launch {
            previewViewModel.streamStatus.collect { status ->
                Log.d(TAG, "Stream status changed to: $status")
                // Check if we're reconnecting - if so, keep button as "Stop"
                val isReconnecting = previewViewModel.isReconnectingLiveData.value ?: false
                
                when (status) {
                    StreamStatus.ERROR, StreamStatus.NOT_STREAMING -> {
                        // Only reset button to "Start" if NOT reconnecting
                        if (!isReconnecting && binding.liveButton.isChecked) {
                            Log.d(TAG, "Stream error/stopped - resetting button to Start")
                            binding.liveButton.isChecked = false
                        } else if (isReconnecting && !binding.liveButton.isChecked) {
                            Log.d(TAG, "Stream stopped but reconnecting - keeping button as Stop")
                            binding.liveButton.isChecked = true
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
                inflateStreamerPreview(streamer)
            } else {
                Log.e(TAG, "Can't start preview, streamer is not a IVideoStreamer")
            }
        }

        // Observe available cameras and create buttons dynamically
        previewViewModel.availableCamerasLiveData.observe(viewLifecycleOwner) { cameras ->
            binding.cameraButtonsContainer.removeAllViews()
            
            if (cameras.isNotEmpty()) {
                cameras.forEach { camera ->
                    val button = android.widget.Button(requireContext()).apply {
                        text = camera.displayName
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = 8 // 8dp spacing between buttons
                        }
                        setOnClickListener {
                            lifecycleScope.launch {
                                try {
                                    (previewViewModel.streamer as? IWithVideoSource)?.setCameraId(camera.id)
                                    Log.i(TAG, "Switched to camera: ${camera.displayName}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to switch camera: ${e.message}", e)
                                    Toast.makeText(requireContext(), "Failed to switch camera", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    binding.cameraButtonsContainer.addView(button)
                }
            }
        }
        
        // Show/hide camera buttons based on current source
        previewViewModel.isCameraSource.observe(viewLifecycleOwner) { isCameraSource ->
            binding.cameraButtonsContainer.visibility = if (isCameraSource && 
                binding.cameraButtonsContainer.childCount > 0) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        // Rebind preview when streaming stops to ensure preview is active
        previewViewModel.isStreamingLiveData.observe(viewLifecycleOwner) { isStreaming ->
            if (isStreaming == false) {
                Log.d(TAG, "Streaming stopped - re-attaching preview if possible")
                try {
                    inflateStreamerPreview()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to re-attach preview after stop: ${t.message}")
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

    /**
     * Helper to check if we should lock orientation for a NEW stream.
     * Returns true if orientation should be locked (no saved state exists).
     * Logs appropriate message if orientation is already saved.
     */
    private fun shouldLockOrientation(context: String): Boolean {
        val savedRotation = previewViewModel.service?.getSavedStreamingOrientation()
        return if (savedRotation == null && rememberedLockedOrientation == null) {
            true // No saved orientation, proceed with lock
        } else {
            Log.d(TAG, "$context: Already have saved orientation (Service: $savedRotation, Fragment: $rememberedLockedOrientation), not re-locking")
            false
        }
    }

    private fun lockOrientation() {
        /**
         * Lock orientation to current position while streaming to prevent disorienting
         * rotations mid-stream. The user can choose their preferred orientation before
         * starting the stream (UI follows sensor via ApplicationConstants.supportedOrientation),
         * and it will stay locked to that orientation until streaming stops.
         * 
         * We remember the current orientation first, then lock to it. This allows us to
         * restore the exact same orientation if the app goes to background and returns.
         */
        // Get the actual current orientation from the display
        val rotation = requireActivity().windowManager.defaultDisplay.rotation
        val currentOrientation = when (rotation) {
            android.view.Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            android.view.Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        rememberedLockedOrientation = currentOrientation
        rememberedRotation = rotation  // Store the rotation value too
        requireActivity().requestedOrientation = currentOrientation
        Log.d(TAG, "Orientation locked to: $currentOrientation (rotation: $rotation)")
        
        // Also lock the stream rotation in the service to match the UI orientation
        previewViewModel.service?.lockStreamRotation(rotation)
    }

    private fun unlockOrientation() {
        /**
         * Unlock orientation after streaming stops, returning to sensor-based rotation.
         * This allows the user to freely rotate the device and choose a new orientation
         * for the next stream.
         */
        rememberedLockedOrientation = null
        rememberedRotation = null  // Clear rotation too
        requireActivity().requestedOrientation = ApplicationConstants.supportedOrientation
        Log.d(TAG, "Orientation unlocked and remembered orientation cleared")
    }

    private fun startStream() {
        Log.d(TAG, "startStream() called - checking if MediaProjection is required")

        // Check if MediaProjection is required for this streaming setup
        if (previewViewModel.requiresMediaProjection()) {
            Log.d(TAG, "MediaProjection required - using startStreamWithMediaProjection")
            // Use MediaProjection-enabled streaming for RTMP sources
            // Note: Errors are displayed via streamerErrorLiveData observer, no need for onError callback
            previewViewModel.startStreamWithMediaProjection(
                mediaProjectionLauncher,
                onSuccess = {
                    Log.d(TAG, "MediaProjection stream started successfully")
                },
                onError = { error ->
                    // Error already posted to streamerErrorLiveData by ViewModel
                    // Just log it here to avoid double error dialogs
                    Log.e(TAG, "MediaProjection stream failed: $error")
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
        
        // Restore orientation lock IMMEDIATELY in onStart() (before onResume()) to prevent
        // the Activity from rotating when returning from background during streaming.
        // Use the Service's saved orientation as the source of truth, since Fragment member
        // variables can be reset during lifecycle transitions.
        val isInStreamingProcess = previewViewModel.streamStatus.value?.let { status ->
            status == com.dimadesu.lifestreamer.models.StreamStatus.STARTING ||
            status == com.dimadesu.lifestreamer.models.StreamStatus.CONNECTING ||
            status == com.dimadesu.lifestreamer.models.StreamStatus.STREAMING
        } ?: false
        
        if (isInStreamingProcess) {
            // Get saved orientation from Service (source of truth)
            val savedRotation = previewViewModel.service?.getSavedStreamingOrientation()
            if (savedRotation != null) {
                val orientation = when (savedRotation) {
                    android.view.Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    android.view.Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                requireActivity().requestedOrientation = orientation
                rememberedLockedOrientation = orientation
                rememberedRotation = savedRotation
                Log.d(TAG, "onStart: Restored orientation from Service: $orientation (rotation: $savedRotation)")
            } else {
                Log.d(TAG, "onStart: No saved orientation in Service, will lock in observer")
            }
        }
        
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
        
        if (PermissionManager.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            // FIRST: Restore orientation lock if streaming, BEFORE restarting preview
            // Get saved orientation from Service (source of truth)
            val currentStatus = previewViewModel.streamStatus.value
            val isInStreamingProcess = currentStatus == com.dimadesu.lifestreamer.models.StreamStatus.STARTING ||
                                       currentStatus == com.dimadesu.lifestreamer.models.StreamStatus.CONNECTING ||
                                       currentStatus == com.dimadesu.lifestreamer.models.StreamStatus.STREAMING
            
            if (isInStreamingProcess) {
                Log.d(TAG, "onResume: Secondary check - restoring orientation from Service (status: $currentStatus)")
                
                // Get saved rotation from Service (survives Fragment lifecycle)
                val savedRotation = previewViewModel.service?.getSavedStreamingOrientation()
                if (savedRotation != null) {
                    val orientation = when (savedRotation) {
                        android.view.Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        android.view.Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    requireActivity().requestedOrientation = orientation
                    rememberedLockedOrientation = orientation
                    rememberedRotation = savedRotation
                    Log.d(TAG, "Restored orientation from Service: $orientation (rotation: $savedRotation)")
                } else {
                    // Fallback to locking current orientation if we don't have a saved one
                    lockOrientation()
                    Log.d(TAG, "No saved orientation in Service, locked to current position")
                }
                
                // SECOND: Give the orientation change time to propagate before restarting preview
                // This prevents the preview from starting in the wrong orientation
                lifecycleScope.launch {
                    delay(100) // Small delay to allow orientation to stabilize
                    try {
                        inflateStreamerPreview()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error while inflating/starting preview on resume: ${e.message}")
                    }
                }
            } else {
                Log.d(TAG, "App returned to foreground - not streaming, orientation remains unlocked")
                // Not streaming, start preview immediately
                try {
                    inflateStreamerPreview()
                } catch (e: Exception) {
                    Log.w(TAG, "Error while inflating/starting preview on resume: ${e.message}")
                }
            }

            // THIRD: Handle service foreground recovery if streaming
            if (isInStreamingProcess) {
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
            // Set the streamer on the preview. Since all sources support dual output
            // (preview + encoder), we can safely set the preview even if streaming is active.
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
                // Defensive catch: if PreviewView rejects the streamer for any reason,
                // log and continue without crashing.
                Log.w(TAG, "Failed to set streamer on preview: ${e.message}")
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
                val isVisible = preview.visibility == View.VISIBLE
                val windowVisible = preview.windowVisibility == View.VISIBLE
                
                if (!isAttached || !hasSize) {
                    val which = if (posted) "posted start" else "start"
                    Log.d(TAG, "Preview not attached or has no size ($which attempt=$attempt) - will retry")
                } else if (!isVisible || !windowVisible) {
                    // View or window not visible yet - wait for it to become visible
                    // This is normal during activity resume, so keep retrying
                    Log.d(TAG, "Preview or window not visible yet (view=$isVisible, window=$windowVisible, attempt=$attempt) - will retry")
                } else {
                    // Wait a bit longer on first attempt to give the PreviewView's surfaceFlow time to update
                    // This prevents race condition where surface is recreated but flow hasn't updated yet
                    if (attempt == 1) {
                        Log.d(TAG, "First attempt - waiting for surface flow to update")
                        delay(150)
                    }
                    
                    // Always try to start preview, even if source reports it's already previewing
                    // This is important after returning from background when the surface was recreated
                    // The camera session needs to be updated with the new surface reference
                    try {
                        preview.startPreview()
                        Log.d(TAG, "Preview started (${if (posted) "posted " else ""}attempt=$attempt)")
                        started = true
                        break
                    } catch (e: IllegalArgumentException) {
                        // Surface was abandoned - likely window went invisible mid-operation
                        if (e.message?.contains("Surface was abandoned") == true) {
                            Log.w(TAG, "Surface abandoned during preview start (attempt=$attempt) - window may have gone invisible")
                            // Continue retrying in case window becomes visible again
                        } else {
                            Log.w(TAG, "startPreview ${if (posted) "(posted)" else ""} attempt=$attempt failed: ${e.message}")
                        }
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
                // Load available cameras for button creation
                previewViewModel.loadAvailableCameras()
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
            // Load available cameras for button creation
            previewViewModel.loadAvailableCameras()
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
