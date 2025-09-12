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
import com.dimadesu.lifestreamer.utils.DialogUtils
import com.dimadesu.lifestreamer.utils.PermissionManager
import io.github.thibaultbee.streampack.core.interfaces.IStreamer
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IPreviewableSource
import io.github.thibaultbee.streampack.core.streamers.lifecycle.StreamerViewModelLifeCycleObserver
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.launch

class PreviewFragment : Fragment(R.layout.main_fragment) {
    private lateinit var binding: MainFragmentBinding

    private val previewViewModel: PreviewViewModel by viewModels {
        PreviewViewModelFactory(requireActivity().application)
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

        binding.switchSourceButton.setOnClickListener {
            previewViewModel.toggleVideoSource()
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
        previewViewModel.startStream()
    }

    private fun stopStream() {
        previewViewModel.stopStream()
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
            val streamer = previewViewModel.streamerLiveData.value
            if (streamer is SingleStreamer) {
                lifecycleScope.launch {
                    try {
                        // Check if preview is already running before trying to start it
                        val videoSource = (streamer as? IWithVideoSource)?.videoInput?.sourceFlow?.value
                        if (videoSource is IPreviewableSource && !videoSource.isPreviewingFlow.value) {
                            binding.preview.startPreview()
                            Log.d(TAG, "Preview restarted for foreground mode")
                        } else {
                            Log.d(TAG, "Preview was already running")
                        }
                        
                        // Re-request audio focus when app returns to foreground if streaming
                        val isCurrentlyStreaming = previewViewModel.isStreamingLiveData.value ?: false
                        if (isCurrentlyStreaming) {
                            Log.d(TAG, "App returned to foreground while streaming - handling recovery")
                            // Get service and handle foreground recovery
                            previewViewModel.service?.let { service ->
                                service.handleForegroundRecovery()
                                Log.d(TAG, "Foreground recovery completed")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error ensuring preview is running: ${e.message}")
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

        // Wait till streamer exists to set it to the SurfaceView.
        preview.streamer = streamer
        if (PermissionManager.hasPermissions(requireContext(), Manifest.permission.CAMERA)) {
            lifecycleScope.launch {
                try {
                    preview.startPreview()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error starting preview", t)
                }
            }
        } else {
            Log.e(TAG, "Camera permission not granted. Preview will not start.")
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

    @SuppressLint("MissingPermission")
    private fun requestCameraAndMicrophonePermissions() {
        when {
            PermissionManager.hasPermissions(
                requireContext(), Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
            ) -> {
                inflateStreamerPreview()
                previewViewModel.configureAudio()
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
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
                    )
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
            previewViewModel.configureAudio()
        }
        if (missingPermissions.isNotEmpty()) {
            showPermissionError(*missingPermissions.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "PreviewFragment"
    }
}
