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
        Handler(Looper.getMainLooper()).post {
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
                
                // Ensure we have an output surface before starting
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
                
                // Set streaming to true - streaming pipeline can work even if ExoPlayer preview fails
                _isStreamingFlow.value = true
                
                // Try to prepare and start ExoPlayer for preview, but don't fail streaming if it doesn't work
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
                // Only stop playback, don't clear surface immediately to avoid rebuffering
                exoPlayer.playWhenReady = false
        Log.d(TAG, "Set playWhenReady = false")
                
                // Give a small delay before stopping completely
                Handler(Looper.getMainLooper()).postDelayed({
                        try {
                        // Only stop and clear surface if we're still not streaming
                        if (!_isStreamingFlow.value) {
                            Log.d(TAG, "Delaying full stop: stopping ExoPlayer and clearing surface")
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
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in delayed stop: ${e.message}", e)
                    }
                }, 100) // Slightly longer delay to avoid immediate restarts
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
    }

    // AbstractPreviewableSource required members (stubbed for RTMP source)
    override val timestampOffsetInNs: Long
        get() = 0L

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean>
        get() = _isPreviewingFlow

    private var outputSurface: Surface? = null
    private var previewSurface: Surface? = null
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
        Handler(Looper.getMainLooper()).post {
            try {
                // Always attach output surface: streaming must render to encoder output.
                exoPlayer.setVideoSurface(surface)
                // Since an output surface is attached, preview should be considered inactive
                _isPreviewingFlow.value = false
            } catch (e: Exception) {
                Log.e("RTMPVideoSource", "Error setting output surface: ${e.message}", e)
            }
        }
    }

    override suspend fun hasPreview(): Boolean {
        return previewSurface != null
    }

    override suspend fun setPreview(surface: Surface) {
//        previewSurface = surface
//        // Use main exoPlayer for preview - no separate instance needed
//        Handler(Looper.getMainLooper()).post {
//            try {
//                // Do not override the output surface while streaming. If streaming is active,
//                // the output surface must stay attached so the encoded stream receives frames.
//                if (!_isStreamingFlow.value) {
//                    exoPlayer.setVideoSurface(surface)
//                } else {
//                    Log.d(TAG, "setPreview called while streaming - not attaching preview surface to avoid overriding output")
//                }
//            } catch (e: Exception) {
//                Log.e("RTMPVideoSource", "Error setting preview surface: ${e.message}", e)
//            }
//        }
    }

    override suspend fun startPreview() {
//        previewSurface?.let { surface ->
//            Handler(Looper.getMainLooper()).post {
//                try {
//                    // Only attach preview surface and start playback when not streaming.
//                    if (!_isStreamingFlow.value) {
//                        exoPlayer.setVideoSurface(surface)
//                        // Only prepare and start playback if we have media items for preview
//                        if (exoPlayer.mediaItemCount > 0) {
//                            Log.d(TAG, "Starting preview with media items")
//                            if (exoPlayer.playbackState == Player.STATE_IDLE) {
//                                exoPlayer.prepare()
//                            }
//                            exoPlayer.playWhenReady = true
//                        } else {
//                            Log.d(TAG, "Starting preview without media items - surface target only")
//                        }
//                    } else {
//                        Log.d(TAG, "startPreview called while streaming - deferring preview to keep output surface attached")
//                    }
//                } catch (e: Exception) {
//                    Log.e(TAG, "Error starting preview: ${e.message}", e)
//                }
//            }
//            // Preview is considered active only when it is actually attached (i.e. not streaming)
//            _isPreviewingFlow.value = !_isStreamingFlow.value
//        } ?: run {
//            _isPreviewingFlow.value = false
//        }
    }

    override suspend fun startPreview(previewSurface: Surface) {
//        setPreview(previewSurface)
//        startPreview()
    }

    override suspend fun stopPreview() {
//        _isPreviewingFlow.value = false
//        Handler(Looper.getMainLooper()).post {
//            try {
//                // Only stop if we're not streaming - if streaming, keep playing but just remove preview surface
//                if (!_isStreamingFlow.value) {
//                    exoPlayer.setVideoSurface(null)
//                    exoPlayer.stop()
//                } else {
//                    // If streaming, switch back to output surface if different from preview
//                    if (outputSurface != previewSurface && outputSurface != null) {
//                        exoPlayer.setVideoSurface(outputSurface)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e("RTMPVideoSource", "Error stopping preview: ${e.message}", e)
//            }
//        }
    }

    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        // Use cached format values to avoid accessing ExoPlayer from non-main threads.
        val width = cachedFormatWidth.get().takeIf { it > 0 } ?: 1920
        val height = cachedFormatHeight.get().takeIf { it > 0 } ?: 1080
        return Size(width, height)
    }

    override suspend fun resetPreviewImpl() {
    }

    override suspend fun resetOutputImpl() {
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
