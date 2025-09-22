package com.dimadesu.lifestreamer.rtmp.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import androidx.media3.common.VideoSize
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ViewPortUtils
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.AspectRatioMode
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.pipelines.outputs.SurfaceDescriptor
import android.graphics.Rect

class RTMPVideoSource (
    private val exoPlayer: ExoPlayer,
) : AbstractPreviewableSource(), IVideoSourceInternal {
    companion object {
        private const val TAG = "RTMPVideoSource"
    }
    /*
     * ExoPlayer must be accessed from the main thread (see ExoPlayer docs).
     * Reading `exoPlayer.videoFormat` from background coroutines/threads can
     * throw "Player is accessed on the wrong thread". To avoid that, we
     * register a small `Player.Listener` on the main thread that caches the
     * current video width/height/rotation into atomic fields. Background
     * callers (the pipeline and preview sizing) then read those cached values
     * instead of touching ExoPlayer directly.
     */
    private val cachedFormatWidth = AtomicInteger(0)
    private val cachedFormatHeight = AtomicInteger(0)
    private val cachedRotation = AtomicInteger(0)

    // Expose the current provider via a MutableStateFlow. We recreate and emit a
    // new provider instance whenever cached format values change so consumers
    // (the rendering pipeline) can react and recompute viewports immediately.
    private val _infoProviderFlow = MutableStateFlow<ISourceInfoProvider>(object : ISourceInfoProvider {
        override fun getSurfaceSize(targetResolution: Size): Size {
            val w = cachedFormatWidth.get().takeIf { it > 0 } ?: targetResolution.width
            val h = cachedFormatHeight.get().takeIf { it > 0 } ?: targetResolution.height
            val rotation = cachedRotation.get()
            return if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
        }

        override val rotationDegrees: Int
            get() = cachedRotation.get()

        override val isMirror: Boolean = false
    })
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> get() = _infoProviderFlow

    private fun makeInfoProvider(): ISourceInfoProvider {
        return object : ISourceInfoProvider {
            override fun getSurfaceSize(targetResolution: Size): Size {
                val w = cachedFormatWidth.get().takeIf { it > 0 } ?: targetResolution.width
                val h = cachedFormatHeight.get().takeIf { it > 0 } ?: targetResolution.height
                val rotation = cachedRotation.get()
                return if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
            }

            override val rotationDegrees: Int
                get() = cachedRotation.get()

            override val isMirror: Boolean = false
        }
    }
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> get() = _isStreamingFlow
    override suspend fun startStream() {
        // Emit streaming=true synchronously so listeners in the pipeline don't
        // observe a transient 'not streaming' state when startStream is called.
        _isStreamingFlow.value = true

        // Cancel any pending cleanup because we're starting streaming now
        mainHandler.post {
            cancelPendingCleanup("before startStream")
            try {
                // Ensure format listener is registered early so cached values are populated
                try {
                    if (!isFormatListenerRegistered) {
                        exoPlayer.addListener(formatListener)
                        isFormatListenerRegistered = true
                    }
                    updateCachedFormat()
                } catch (ignored: Exception) {
                }
                Log.d(TAG, "Starting stream - playbackState: ${exoPlayer.playbackState}")
                Log.d(TAG, "MediaItem count: ${exoPlayer.mediaItemCount}")
                Log.d(TAG, "Output surface: $outputSurface")
                Log.d(TAG, "RTMPVideoSource.startStream invoked (thread=${Thread.currentThread().name})")
                
                // If a surface processor is initialized we must attach ExoPlayer to
                // the processor input surface so frames are copied to both the
                // encoder output and the preview output. Otherwise attach the
                // provided output surface directly.
                if (surfaceProcessor != null && inputSurface != null) {
                    Log.d(TAG, "Attaching ExoPlayer to surface processor input: $inputSurface")
                    exoPlayer.setVideoSurface(inputSurface)
                } else {
                    // Ensure we have an output surface before starting if no processor
                    if (outputSurface == null) {
                        Log.w(TAG, "No output surface set - cannot start streaming")
                        _isStreamingFlow.value = false
                        return@post
                    }
                    outputSurface?.let { surface ->
                        Log.d(TAG, "Attaching output surface to ExoPlayer: $surface")
                        exoPlayer.setVideoSurface(surface)
                        Log.d(TAG, "Set video surface to output")
                    }
                }
                
                // Streaming already marked true; pipeline can proceed even if ExoPlayer playback fails
                
                // Try to prepare and start ExoPlayer playback, but don't fail streaming if it doesn't work
                try {
                    if (exoPlayer.mediaItemCount > 0) {
                        Log.d(TAG, "ExoPlayer has media items - preparing for playback")
                        if (exoPlayer.playbackState == Player.STATE_IDLE) {
                            Log.d(TAG, "Preparing ExoPlayer")
                            exoPlayer.prepare()
                        }
                        Log.d(TAG, "Setting playWhenReady = true")
                        exoPlayer.playWhenReady = true
                    } else {
                        Log.d(TAG, "No media items - ExoPlayer will be used as surface target only")
                    }
                } catch (e: Exception) {
                    Log.w("RTMPVideoSource", "ExoPlayer preparation failed, but streaming can continue: ${e.message}")
                }
                
                // Add a listener to monitor playback state changes. Use a single stored
                // listener instance to avoid adding multiple anonymous listeners on
                // repeated start/stop calls.
                if (playerListener == null) {
                    playerListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            Log.d(TAG, "Playback state changed to: $playbackState")
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    Log.i(TAG, "ExoPlayer is ready and playing")
                                }
                                Player.STATE_ENDED -> {
                                    Log.w(TAG, "ExoPlayer playback ended")
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                            // Don't stop streaming on ExoPlayer error - it might be used as surface target only
                            Log.w(TAG, "Continuing streaming despite ExoPlayer error")
                        }
                    }
                    exoPlayer.addListener(playerListener!!)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting stream: ${e.message}", e)
                _isStreamingFlow.value = false
            }
        }
    }

    override suspend fun stopStream() {
    Log.d(TAG, "stopStream() called - streaming: ${_isStreamingFlow.value}")
        _isStreamingFlow.value = false
        Handler(Looper.getMainLooper()).post {
            try {
                Log.d(TAG, "Stopping stream - current state: ${exoPlayer.playbackState}")
                // If preview is active, keep playback running for the preview.
                // Only pause playback immediately if no preview is active.
                if (!_isPreviewingFlow.value) {
                    exoPlayer.playWhenReady = false
                    Log.d(TAG, "Set playWhenReady = false (no preview active)")
                } else {
                    Log.d(TAG, "Preview active - keeping playback for preview")
                }

                // Give a small delay before performing a full stop/cleanup. Only
                // perform the full stop if both streaming and previewing are
                // inactive to avoid interrupting an ongoing preview.
                schedulePendingCleanup(500) {
                    if (!_isStreamingFlow.value && !_isPreviewingFlow.value) {
                        Log.d(TAG, "Delayed full stop: stopping ExoPlayer and clearing surface")
                        exoPlayer.stop()
                        exoPlayer.setVideoSurface(null)
                        // Remove any attached listener to prevent leaks
                        playerListener?.let { listener ->
                            try {
                                exoPlayer.removeListener(listener)
                            } catch (ignored: Exception) {
                            }
                            playerListener = null
                        }
                        // Also remove formatListener if it was added
                        if (isFormatListenerRegistered) {
                            try {
                                exoPlayer.removeListener(formatListener)
                            } catch (ignored: Exception) {
                            }
                            isFormatListenerRegistered = false
                        }
                        Log.d(TAG, "Stream stopped and surface cleared")
                    } else {
                        Log.d(TAG, "Skipping full stop because preview or streaming became active")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping ExoPlayer: ${e.message}", e)
            }
        }
    }

    override suspend fun configure(config: VideoSourceConfig) {
        // Using main exoPlayer instance for both streaming and preview
        withContext(Dispatchers.Main) {
            if (!exoPlayer.isCommandAvailable(Player.COMMAND_PREPARE)) {
                return@withContext
            }
            // ExoPlayer will be prepared when startStream() or startPreview() is called
        }
    }

    override fun release() {
        Handler(Looper.getMainLooper()).post {
            try {
                // Clear surfaces first
                exoPlayer.setVideoSurface(null)
                // Stop playback if still playing
                if (exoPlayer.playbackState != Player.STATE_IDLE) {
                    exoPlayer.stop()
                }
                // Remove listener if present to avoid leaks
                playerListener?.let { listener ->
                    try {
                        exoPlayer.removeListener(listener)
                    } catch (ignored: Exception) {
                    }
                    playerListener = null
                }
                // Remove format listener if present
                if (isFormatListenerRegistered) {
                    try {
                        exoPlayer.removeListener(formatListener)
                    } catch (ignored: Exception) {
                    }
                    isFormatListenerRegistered = false
                }
                // Do NOT release the shared ExoPlayer here - caller owns the player.
                // Only clear surfaces and listeners so the shared player can be reused.
                // exoPlayer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing ExoPlayer: ${e.message}", e)
            }
        }

        // Release surface processor resources
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

    // AbstractPreviewableSource required members (stubbed for RTMP source)
    override val timestampOffsetInNs: Long
        get() = 0L

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean>
        get() = _isPreviewingFlow

    private var outputSurface: Surface? = null
    private var previewSurface: Surface? = null

    // Surface processor for dual output (streaming + preview)
    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var inputSurface: Surface? = null
    private var outputSurfaceOutput: SurfaceOutput? = null
    private var previewSurfaceOutput: SurfaceOutput? = null
    // Main thread handler used for scheduling/cancelling delayed cleanup
    private val mainHandler = Handler(Looper.getMainLooper())
    // Single pending cleanup runnable reference so we can cancel it if preview/stream restarts
    private var pendingCleanupRunnable: Runnable? = null
    // Cancel and scheduling helpers to reduce duplication
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
    // Single listener instance to avoid leaks from multiple anonymous listeners
    private var playerListener: Player.Listener? = null
    // Listener to update cached format values on main thread.
    private val formatListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateCachedFormat()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Format info may become available when playback starts/changes
            updateCachedFormat()
        }
    }
    // Track whether formatListener has been added to avoid duplicate registration
    private var isFormatListenerRegistered = false

    private fun updateCachedFormat() {
        try {
            val format = exoPlayer.videoFormat
            val w = format?.width ?: 0
            val h = format?.height ?: 0
            val rot = format?.rotationDegrees ?: 0
            val prevW = cachedFormatWidth.get()
            val prevH = cachedFormatHeight.get()
            val prevRot = cachedRotation.get()
            if (prevW != w || prevH != h || prevRot != rot) {
                cachedFormatWidth.set(w)
                cachedFormatHeight.set(h)
                cachedRotation.set(rot)
                try {
                    _infoProviderFlow.value = makeInfoProvider()
                } catch (ignored: Exception) {
                }
            }
        } catch (ignored: Exception) {
        }
    }

    override suspend fun getOutput(): Surface? {
        return outputSurface
    }

    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        initializeSurfaceProcessor()
        Handler(Looper.getMainLooper()).post {
            try {
                cancelPendingCleanup("setOutput called")
                // Set the input surface (from surface processor) to ExoPlayer
                // The surface processor will copy frames to both output and preview surfaces
                inputSurface?.let { input ->
                    exoPlayer.setVideoSurface(input)
                    Log.d(TAG, "Set ExoPlayer surface to surface processor input surface")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting output surface: ${e.message}", e)
            }
        }
    }

    override suspend fun hasPreview(): Boolean {
        return previewSurface != null
    }

    override suspend fun setPreview(surface: Surface) {
        previewSurface = surface
        initializeSurfaceProcessor()
        Log.d(TAG, "Preview surface set: $surface")
        cancelPendingCleanup("setPreview called")
    }

    override suspend fun startPreview() {
        previewSurface?.let { surface ->
            _isPreviewingFlow.value = true
            Log.d(TAG, "Starting preview with surface processor")

            // Cancel any pending cleanup because we're starting preview
            cancelPendingCleanup("before startPreview")

            Handler(Looper.getMainLooper()).post {
                try {
                    // Start ExoPlayer playback if not already running
                    // The surface processor will handle distributing frames to both surfaces
                    if (exoPlayer.mediaItemCount > 0) {
                        Log.d(TAG, "Starting preview with media items")
                        if (exoPlayer.playbackState == Player.STATE_IDLE) {
                            exoPlayer.prepare()
                        }
                        exoPlayer.playWhenReady = true
                    } else {
                        Log.d(TAG, "Starting preview without media items - surface target only")
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
        _isPreviewingFlow.value = false
        Log.d(TAG, "Stopping preview")

        // Remove preview surface from surface processor
        previewSurfaceOutput?.let { surfaceOutput ->
            surfaceProcessor?.removeOutputSurface(surfaceOutput)
            previewSurfaceOutput = null
        }

        // If streaming is active, keep playback running for streaming; only
        // pause/cleanup if neither streaming nor previewing are active.
        Handler(Looper.getMainLooper()).post {
            try {
                if (!_isStreamingFlow.value) {
                    exoPlayer.playWhenReady = false
                    Log.d(TAG, "Set playWhenReady = false from stopPreview()")
                } else {
                    Log.d(TAG, "Streaming active - keeping playback after stopPreview()")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping preview playback: ${e.message}", e)
            }
        }

        // If both previewing and streaming are now inactive, schedule a full stop
        // to cleanup surfaces/listeners. Use a cancelable pending runnable so
        // surface recreation or quick restarts cancel the cleanup.
        schedulePendingCleanup(500) {
            if (!_isStreamingFlow.value && !_isPreviewingFlow.value) {
                Log.d(TAG, "Both streaming and preview inactive - performing full stop cleanup")
                try {
                    exoPlayer.stop()
                    exoPlayer.setVideoSurface(null)
                } catch (ignored: Exception) {
                }
                playerListener?.let { listener ->
                    try {
                        exoPlayer.removeListener(listener)
                    } catch (ignored: Exception) {
                    }
                    playerListener = null
                }
                if (isFormatListenerRegistered) {
                    try {
                        exoPlayer.removeListener(formatListener)
                    } catch (ignored: Exception) {
                    }
                    isFormatListenerRegistered = false
                }
            }
        }
    }

    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        // Use cached format values to avoid accessing ExoPlayer from non-main threads.
        val w = cachedFormatWidth.get().takeIf { it > 0 } ?: targetSize.width
        val h = cachedFormatHeight.get().takeIf { it > 0 } ?: targetSize.height
        val rotation = cachedRotation.get()
        return if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
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

    private fun initializeSurfaceProcessor() {
        // Cancel any pending cleanup because we're initializing/updating surfaces
        cancelPendingCleanup("initializeSurfaceProcessor called")

        if (surfaceProcessor == null && (outputSurface != null || previewSurface != null)) {
            try {
                Log.d(TAG, "Initializing surface processor for dual output")

                // Create surface processor with SDR profile (most RTMP streams are SDR)
                val processorFactory = DefaultSurfaceProcessorFactory()
                surfaceProcessor = processorFactory.create(DynamicRangeProfile.sdr)

                // Create input surface that ExoPlayer will render to
                val width = cachedFormatWidth.get().takeIf { it > 0 } ?: 1920
                val height = cachedFormatHeight.get().takeIf { it > 0 } ?: 1080
                inputSurface = surfaceProcessor!!.createInputSurface(Size(width, height), timestampOffsetInNs)

                Log.d(TAG, "Created input surface: $inputSurface with size ${width}x${height}")

                // Add output surfaces
                addSurfacesToProcessor()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize surface processor: ${e.message}", e)
                surfaceProcessor?.release()
                surfaceProcessor = null
            }
        } else if (surfaceProcessor != null) {
            // Update surfaces if processor already exists
            addSurfacesToProcessor()
        }
    }

    private fun addSurfacesToProcessor() {
        // Cancel any pending cleanup because we're adding/updating surfaces
        cancelPendingCleanup("addSurfacesToProcessor called")
        surfaceProcessor?.let { processor ->
            // Add output surface for streaming if available
            outputSurface?.let { surface ->
                // If an output SurfaceOutput exists but the underlying Surface
                // instance changed (e.g. due to orientation / view recreation),
                // remove and recreate it so the processor uses the new Surface.
                if (outputSurfaceOutput != null) {
                    try {
                        val existingSurface = outputSurfaceOutput?.descriptor?.surface
                        if (existingSurface != surface) {
                            processor.removeOutputSurface(outputSurfaceOutput!!)
                            outputSurfaceOutput = null
                            Log.d(TAG, "Recreated output surface output due to surface change")
                        }
                    } catch (ignored: Exception) {
                    }
                }

                if (outputSurfaceOutput == null) {
                    val width = cachedFormatWidth.get().takeIf { it > 0 } ?: 1920
                    val height = cachedFormatHeight.get().takeIf { it > 0 } ?: 1080
                    val surfaceDescriptor = SurfaceDescriptor(
                        surface = surface,
                        resolution = Size(width, height),
                        targetRotation = 0,
                        isEncoderInputSurface = true
                    )
                    val transformationInfo = SurfaceOutput.TransformationInfo(
                        aspectRatioMode = AspectRatioMode.PRESERVE,
                        targetRotation = 0,
                        cropRect = Rect(0, 0, width, height),
                        needMirroring = false,
                        infoProvider = _infoProviderFlow.value
                    )
                    outputSurfaceOutput = SurfaceOutput(
                        surfaceDescriptor,
                        { _isStreamingFlow.value },
                        transformationInfo
                    )
                    processor.addOutputSurface(outputSurfaceOutput!!)
                    Log.d(TAG, "Added output surface to processor: $surface")
                }
            }

            // Add preview surface if available
            previewSurface?.let { surface ->
                // If preview output exists but the surface changed (view recreated),
                // remove and recreate the preview output so the processor uses the
                // new Surface instance.
                if (previewSurfaceOutput != null) {
                    try {
                        val existingSurface = previewSurfaceOutput?.descriptor?.surface
                        if (existingSurface != surface) {
                            processor.removeOutputSurface(previewSurfaceOutput!!)
                            previewSurfaceOutput = null
                            Log.d(TAG, "Recreated preview surface output due to surface change")
                        }
                    } catch (ignored: Exception) {
                    }
                }

                if (previewSurfaceOutput == null) {
                    val width = cachedFormatWidth.get().takeIf { it > 0 } ?: 1920
                    val height = cachedFormatHeight.get().takeIf { it > 0 } ?: 1080
                    val surfaceDescriptor = SurfaceDescriptor(
                        surface = surface,
                        resolution = Size(width, height),
                        targetRotation = 0,
                        isEncoderInputSurface = false
                    )
                    val transformationInfo = SurfaceOutput.TransformationInfo(
                        aspectRatioMode = AspectRatioMode.PRESERVE,
                        targetRotation = 0,
                        cropRect = Rect(0, 0, width, height),
                        needMirroring = false,
                        infoProvider = _infoProviderFlow.value
                    )
                    previewSurfaceOutput = SurfaceOutput(
                        surfaceDescriptor,
                        { _isPreviewingFlow.value },
                        transformationInfo
                    )
                    processor.addOutputSurface(previewSurfaceOutput!!)
                    Log.d(TAG, "Added preview surface to processor: $surface")
                }
            }
        }
    }

    class Factory(
        private val exoPlayer: ExoPlayer,
    ) : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {
            val customSrc = RTMPVideoSource(exoPlayer)
            return customSrc
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is RTMPVideoSource
        }
    }
}
