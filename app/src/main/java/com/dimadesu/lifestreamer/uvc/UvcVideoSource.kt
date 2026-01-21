package com.dimadesu.lifestreamer.uvc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.pipelines.outputs.SurfaceDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicInteger

/**
 * UVC camera video source for StreamPack.
 * 
 * Provides continuous video frames from a USB camera via UVCAndroid library.
 * Supports dual output (streaming + preview) using surface processor.
 */
class UvcVideoSource(
    private val context: Context,
    private val cameraHelper: CameraHelper,
    private val dispatcherProvider: io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
) : AbstractPreviewableSource(), IVideoSourceInternal {

    companion object {
        private const val TAG = "UvcVideoSource"
    }

    // Cached format values from CameraHelper
    private val cachedWidth = AtomicInteger(1920)
    private val cachedHeight = AtomicInteger(1080)
    
    // Info provider for surface sizing and transformations
    private val _infoProviderFlow = MutableStateFlow<ISourceInfoProvider>(object : ISourceInfoProvider {
        override fun getSurfaceSize(targetResolution: Size): Size {
            val w = cachedWidth.get()
            val h = cachedHeight.get()
            return Size(w, h)
        }

        override val rotationDegrees: Int = 0
        override val isMirror: Boolean = false
    })
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> get() = _infoProviderFlow

    private fun makeInfoProvider(): ISourceInfoProvider {
        return object : ISourceInfoProvider {
            override fun getSurfaceSize(targetResolution: Size): Size {
                val w = cachedWidth.get()
                val h = cachedHeight.get()
                return Size(w, h)
            }

            override val rotationDegrees: Int = 0
            override val isMirror: Boolean = false
        }
    }

    // Streaming state
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> get() = _isStreamingFlow

    // Preview state  
    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean> get() = _isPreviewingFlow

    // Surfaces
    private var outputSurface: Surface? = null
    private var previewSurface: Surface? = null

    // Surface processor for dual output (streaming + preview)
    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var inputSurface: Surface? = null
    private var outputSurfaceOutput: SurfaceOutput? = null
    private var previewSurfaceOutput: SurfaceOutput? = null

    // Main thread handler for UVC operations
    private val mainHandler = Handler(Looper.getMainLooper())

    // Pending cleanup management
    private var pendingCleanupRunnable: Runnable? = null

    // Track if this source has been released to avoid calling methods on dead CameraHelper
    @Volatile
    private var isReleased = false

    override val timebase = Timebase.UPTIME

    init {
        Log.d(TAG, "UvcVideoSource initialized")
        // Get initial camera format if available
        updateCachedFormat()
    }

    override suspend fun startStream() {
        Log.d(TAG, "startStream() called")
        _isStreamingFlow.value = true
        cancelPendingCleanup("startStream")

        mainHandler.post {
            try {
                Log.d(TAG, "Starting UVC stream")
                
                // Update cached format from current camera settings
                updateCachedFormat()

                // Attach camera to appropriate surface
                if (surfaceProcessor != null && inputSurface != null) {
                    Log.d(TAG, "Attaching camera to surface processor input")
                    cameraHelper.addSurface(inputSurface, true)
                    cameraHelper.startPreview()
                } else if (outputSurface != null) {
                    Log.d(TAG, "Attaching camera directly to output surface")
                    cameraHelper.addSurface(outputSurface, true)
                    cameraHelper.startPreview()
                } else {
                    Log.w(TAG, "No surface available for streaming")
                }

                Log.i(TAG, "UVC stream started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting stream: ${e.message}", e)
                _isStreamingFlow.value = false
            }
        }
    }

    override suspend fun stopStream() {
        Log.d(TAG, "stopStream() called")
        _isStreamingFlow.value = false

        mainHandler.post {
            try {
                // If preview is active, keep camera running for preview
                if (!_isPreviewingFlow.value) {
                    Log.d(TAG, "Stopping UVC preview (no preview active)")
                    cameraHelper.stopPreview()
                } else {
                    Log.d(TAG, "Preview active - keeping camera running")
                }

                // Schedule cleanup if both streaming and preview are inactive
                schedulePendingCleanup(500) {
                    if (!_isStreamingFlow.value && !_isPreviewingFlow.value) {
                        Log.d(TAG, "Performing full stop cleanup")
                        cameraHelper.stopPreview()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping stream: ${e.message}", e)
            }
        }
    }

    override suspend fun configure(config: VideoSourceConfig) {
        Log.d(TAG, "configure() called")
        // UVC camera configuration is handled through CameraHelper
        // Resolution/format is set via showVideoFormatDialog in UvcTestActivity
        updateCachedFormat()
    }

    override suspend fun release() {
        Log.d(TAG, "release() called")
        
        // Mark as released FIRST to prevent any pending operations
        isReleased = true
        
        // Cancel any pending cleanup to avoid double-release
        cancelPendingCleanup("release")
        
        mainHandler.post {
            try {
                // Stop camera preview
                cameraHelper.stopPreview()
                
                // Remove surfaces
                inputSurface?.let { cameraHelper.removeSurface(it) }
                outputSurface?.let { cameraHelper.removeSurface(it) }
                previewSurface?.let { cameraHelper.removeSurface(it) }
                
                // Close camera and release CameraHelper
                cameraHelper.closeCamera()
                cameraHelper.release()
                
                Log.d(TAG, "UVC camera released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing: ${e.message}", e)
            }
        }

        // Release surface processor
        try {
            inputSurface?.let { surface ->
                surfaceProcessor?.removeInputSurface(surface)
                inputSurface = null
            }
            surfaceProcessor?.release()
            surfaceProcessor = null
            outputSurfaceOutput = null
            previewSurfaceOutput = null
            Log.d(TAG, "Surface processor released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing surface processor: ${e.message}", e)
        }
    }

    override suspend fun getOutput(): Surface? {
        return outputSurface
    }

    override suspend fun setOutput(surface: Surface) {
        Log.d(TAG, "setOutput() called")
        outputSurface = surface
        initializeSurfaceProcessor()
        
        mainHandler.post {
            try {
                cancelPendingCleanup("setOutput")
                
                // Attach camera to input surface if processor is ready
                inputSurface?.let { input ->
                    cameraHelper.addSurface(input, true)
                    cameraHelper.startPreview()
                    Log.d(TAG, "Attached camera to surface processor input")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting output: ${e.message}", e)
            }
        }
    }

    override suspend fun hasPreview(): Boolean {
        return previewSurface != null
    }

    override suspend fun setPreview(surface: Surface) {
        Log.d(TAG, "setPreview() called")
        previewSurface = surface
        initializeSurfaceProcessor()
        cancelPendingCleanup("setPreview")
    }

    override suspend fun startPreview() {
        Log.d(TAG, "startPreview() called")
        previewSurface?.let {
            _isPreviewingFlow.value = true
            cancelPendingCleanup("startPreview")

            mainHandler.post {
                try {
                    // Start camera preview to appropriate surface
                    if (surfaceProcessor != null && inputSurface != null) {
                        cameraHelper.addSurface(inputSurface, false)
                        cameraHelper.startPreview()
                        Log.d(TAG, "Started preview with surface processor")
                    } else if (previewSurface != null) {
                        cameraHelper.addSurface(previewSurface, false)
                        cameraHelper.startPreview()
                        Log.d(TAG, "Started preview directly")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting preview: ${e.message}", e)
                    _isPreviewingFlow.value = false
                }
            }
        } ?: run {
            _isPreviewingFlow.value = false
        }
    }

    override suspend fun startPreview(previewSurface: Surface) {
        setPreview(previewSurface)
        startPreview()
    }

    override suspend fun stopPreview() {
        Log.d(TAG, "stopPreview() called")
        _isPreviewingFlow.value = false

        // Remove preview surface from processor
        previewSurfaceOutput?.let { surfaceOutput ->
            surfaceProcessor?.removeOutputSurface(surfaceOutput)
            previewSurfaceOutput = null
        }

        mainHandler.post {
            try {
                // If streaming is active, keep camera running
                if (!_isStreamingFlow.value) {
                    cameraHelper.stopPreview()
                    Log.d(TAG, "Stopped camera preview")
                } else {
                    Log.d(TAG, "Streaming active - keeping camera running")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping preview: ${e.message}", e)
            }
        }

        // Schedule cleanup if both are inactive
        schedulePendingCleanup(500) {
            if (!_isStreamingFlow.value && !_isPreviewingFlow.value) {
                Log.d(TAG, "Full cleanup - stopping camera")
                cameraHelper.stopPreview()
            }
        }
    }

    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        val w = cachedWidth.get()
        val h = cachedHeight.get()
        
        if (w <= 0 || h <= 0) {
            Log.d(TAG, "getPreviewSize: No valid dimensions, using target size")
            return targetSize
        }
        
        return Size(w, h)
    }

    override suspend fun resetPreviewImpl() {
        previewSurface = null
        previewSurfaceOutput?.let { surfaceOutput ->
            surfaceProcessor?.removeOutputSurface(surfaceOutput)
        }
        previewSurfaceOutput = null
    }

    override suspend fun resetOutputImpl() {
        outputSurface = null
        outputSurfaceOutput?.let { surfaceOutput ->
            surfaceProcessor?.removeOutputSurface(surfaceOutput)
        }
        outputSurfaceOutput = null
    }

    private fun updateCachedFormat() {
        try {
            val previewSize = cameraHelper.previewSize
            if (previewSize != null) {
                val w = previewSize.width
                val h = previewSize.height
                val prevW = cachedWidth.get()
                val prevH = cachedHeight.get()
                
                if (prevW != w || prevH != h) {
                    Log.d(TAG, "Format changed: ${prevW}x${prevH} -> ${w}x${h}")
                    cachedWidth.set(w)
                    cachedHeight.set(h)
                    
                    try {
                        _infoProviderFlow.value = makeInfoProvider()
                    } catch (ignored: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cached format: ${e.message}")
        }
    }

    private fun initializeSurfaceProcessor() {
        cancelPendingCleanup("initializeSurfaceProcessor")

        if (surfaceProcessor == null && (outputSurface != null || previewSurface != null)) {
            try {
                Log.d(TAG, "Initializing surface processor")

                // Create surface processor
                val processorFactory = DefaultSurfaceProcessorFactory()
                surfaceProcessor = processorFactory.create(DynamicRangeProfile.sdr, dispatcherProvider)

                // Create input surface for camera
                val width = cachedWidth.get()
                val height = cachedHeight.get()
                inputSurface = surfaceProcessor!!.createInputSurface(Size(width, height), timebase)

                Log.d(TAG, "Created input surface: $inputSurface with size ${width}x${height}")

                // Add output surfaces
                addSurfacesToProcessor()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize surface processor: ${e.message}", e)
                surfaceProcessor?.release()
                surfaceProcessor = null
            }
        } else if (surfaceProcessor != null) {
            Log.d(TAG, "Updating surfaces on existing processor")
            addSurfacesToProcessor()
        }
    }

    private fun addSurfacesToProcessor() {
        cancelPendingCleanup("addSurfacesToProcessor")
        
        surfaceProcessor?.let { processor ->
            val width = cachedWidth.get()
            val height = cachedHeight.get()

            // Add output surface for streaming
            outputSurface?.let { surface ->
                if (outputSurfaceOutput != null) {
                    try {
                        val existingSurface = outputSurfaceOutput?.targetSurface
                        if (existingSurface != surface) {
                            processor.removeOutputSurface(outputSurfaceOutput!!)
                            outputSurfaceOutput = null
                            Log.d(TAG, "Recreated output surface due to change")
                        }
                    } catch (ignored: Exception) {
                    }
                }

                if (outputSurfaceOutput == null) {
                    outputSurfaceOutput = SurfaceOutput(
                        targetSurface = surface,
                        targetResolution = Size(width, height),
                        targetRotation = 0,
                        isStreaming = { _isStreamingFlow.value },
                        sourceResolution = Size(width, height),
                        needMirroring = false,
                        sourceInfoProvider = _infoProviderFlow.value
                    )
                    processor.addOutputSurface(outputSurfaceOutput!!)
                    Log.d(TAG, "Added output surface to processor")
                }
            }

            // Add preview surface
            previewSurface?.let { surface ->
                if (previewSurfaceOutput != null) {
                    try {
                        val existingSurface = previewSurfaceOutput?.targetSurface
                        if (existingSurface != surface) {
                            processor.removeOutputSurface(previewSurfaceOutput!!)
                            previewSurfaceOutput = null
                            Log.d(TAG, "Recreated preview surface due to change")
                        }
                    } catch (ignored: Exception) {
                    }
                }

                if (previewSurfaceOutput == null) {
                    previewSurfaceOutput = SurfaceOutput(
                        targetSurface = surface,
                        targetResolution = Size(width, height),
                        targetRotation = 0,
                        isStreaming = { _isPreviewingFlow.value },
                        sourceResolution = Size(width, height),
                        needMirroring = false,
                        sourceInfoProvider = _infoProviderFlow.value
                    )
                    processor.addOutputSurface(previewSurfaceOutput!!)
                    Log.d(TAG, "Added preview surface to processor")
                }
            }
        }
    }

    private fun cancelPendingCleanup(reason: String? = null) {
        pendingCleanupRunnable?.let {
            Log.d(TAG, "Cancelling pending cleanup${reason?.let { r -> ": $r" } ?: ""}")
            mainHandler.removeCallbacks(it)
            pendingCleanupRunnable = null
        }
    }

    private fun schedulePendingCleanup(delayMs: Long = 500L, action: () -> Unit) {
        cancelPendingCleanup("scheduling new cleanup")
        val runnable = Runnable {
            try {
                // Don't execute cleanup if already released
                if (isReleased) {
                    Log.d(TAG, "Skipping scheduled cleanup - already released")
                    return@Runnable
                }
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Error in scheduled cleanup: ${e.message}", e)
            } finally {
                pendingCleanupRunnable = null
            }
        }
        pendingCleanupRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
        Log.d(TAG, "Scheduled pending cleanup in ${delayMs}ms")
    }

    /**
     * Factory for creating UVC video source instances
     */
    class Factory(
        private val cameraHelper: CameraHelper
    ) : IVideoSourceInternal.Factory {
        override suspend fun create(
            context: Context,
            dispatcherProvider: io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
        ): IVideoSourceInternal {
            return UvcVideoSource(context, cameraHelper, dispatcherProvider)
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is UvcVideoSource
        }
    }
}
