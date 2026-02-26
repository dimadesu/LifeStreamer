package com.dimadesu.lifestreamer.ui.main

import android.app.Application
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.graphics.Bitmap
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.media.projection.MediaProjection
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionAudioSourceFactory
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionHelper
import com.dimadesu.lifestreamer.player.SrtDataSourceFactory
import com.dimadesu.lifestreamer.player.TsOnlyExtractorFactory
import kotlinx.coroutines.isActive

internal object RtmpSourceSwitchHelper {
    private const val TAG = "RtmpSourceSwitchHelper"

    /**
     * Check if the URL is an SRT URL.
     */
    private fun isSrtUrl(url: String): Boolean {
        return url.lowercase().startsWith("srt://")
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun createExoPlayer(application: Application, url: String, bufferForPlaybackMs: Int): ExoPlayer =
        withContext(Dispatchers.Main) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,      // 50 seconds
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,      // 50 seconds
                    bufferForPlaybackMs, // Start playback after buffering this many ms
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS  // 5 seconds
                )
                .build()

            val exoPlayer = ExoPlayer.Builder(application)
                .setLoadControl(loadControl)
                .build()

            val mediaItem = MediaItem.fromUri(url)
            
            // Use SRT data source for srt:// URLs, otherwise use default (supports RTMP, etc.)
            val mediaSource = if (isSrtUrl(url)) {
                Log.i(TAG, "Creating SRT media source for: $url")
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    SrtDataSourceFactory(),
                    TsOnlyExtractorFactory()
                ).createMediaSource(mediaItem)
            } else {
                Log.i(TAG, "Creating default media source for: $url")
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(application),
                    DefaultExtractorsFactory()
                ).createMediaSource(mediaItem)
            }

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.volume = 0f
            
            // Add a lightweight error listener so callers can observe failures in logs
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val protocol = if (isSrtUrl(url)) "SRT" else "RTMP"
                    Log.w(TAG, "ExoPlayer $protocol error: ${error.message}")
                }
            })
            exoPlayer
        }

    suspend fun awaitReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                try { player.removeListener(this) } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(true) {}
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            try { player.removeListener(this) } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                    // If the player is already ready, avoid registering the listener which
                    // could miss the READY event if it happened before listener registration.
                    val currentState = try { player.playbackState } catch (_: Exception) { -1 }
                    if (currentState == androidx.media3.common.Player.STATE_READY) {
                        if (cont.isActive) cont.resume(true) {}
                        return@suspendCancellableCoroutine
                    }
                    player.addListener(listener)
                    cont.invokeOnCancellation {
                        try { player.removeListener(listener) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "awaitReady failed or timed out: ${e.message}")
            false
        }
    }

    /**
     * Switch to bitmap fallback for RTMP source.
     * Uses MediaProjection audio to capture RTMP player audio.
     */
    suspend fun switchToBitmapFallback(
        streamer: SingleStreamer,
        bitmap: Bitmap,
        mediaProjection: MediaProjection? = null,
        mediaProjectionHelper: MediaProjectionHelper? = null
    ) {
        try {
            // Add delay before switching sources to allow previous sources to fully release
            // This prevents resource conflicts when hot-swapping sources
            delay(300)
            
            // Set video to bitmap first
            streamer.setVideoSource(BitmapSourceFactory(bitmap))
            
            // Audio follows video: For RTMP/Bitmap, prefer MediaProjection, fallback to mic
            val projection = mediaProjection ?: mediaProjectionHelper?.getMediaProjection()
            if (projection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    streamer.setAudioSource(MediaProjectionAudioSourceFactory(projection))
                    Log.i(TAG, "Switched to bitmap fallback with MediaProjection audio")
                } catch (e: Exception) {
                    Log.w(TAG, "MediaProjection audio failed, using microphone: ${e.message}")
                    streamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                    Log.i(TAG, "Switched to bitmap fallback with microphone audio")
                }
            } else {
                // No MediaProjection available - use microphone
                streamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                Log.i(TAG, "Switched to bitmap fallback with microphone audio (no MediaProjection)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bitmap fallback source: ${e.message}", e)
        }
    }

    /**
     * Full flow to switch a streamer from camera to an RTMP source without
     * stopping or restarting the streamer service itself:
     * - switch to bitmap fallback immediately (so UI doesn't freeze)
     * - prepare ExoPlayer for RTMP preview
     * - wait until ready (with timeout)
     * - attach RTMP video and appropriate audio source
     * - retry every 5 seconds if connection fails
     * - call onRtmpConnected with ExoPlayer instance when successfully connected
     * Returns the Job for the retry loop so it can be cancelled if needed.
     */
    suspend fun switchToRtmpSource(
        application: Application,
        currentStreamer: SingleStreamer,
        testBitmap: Bitmap,
        videoSourceUrl: String,
        storageRepository: DataStoreRepository,
        mediaProjectionHelper: MediaProjectionHelper,
        streamingMediaProjection: MediaProjection?,
        postError: (String) -> Unit,
        postRtmpStatus: (String?) -> Unit,
        onRtmpConnected: ((ExoPlayer) -> Unit)? = null
    ): kotlinx.coroutines.Job {
        var attemptCount = 0
        val maxAttempts = Int.MAX_VALUE // Keep retrying indefinitely
        
        // Start retry loop - use Main dispatcher for ExoPlayer thread safety
        // Return the Job so caller can cancel it when switching back to camera
        return CoroutineScope(Dispatchers.Main).launch {
            while (attemptCount < maxAttempts && isActive) {
                attemptCount++
                val isFirstAttempt = attemptCount == 1
                
                try {
                    // Check cancellation before showing status
                    if (!isActive) break
                    
                    // Show status message
                    if (isFirstAttempt) {
                        postRtmpStatus("Playing RTMP")
                        Log.i(TAG, "Attempting to connect to RTMP source: $videoSourceUrl (first attempt)")
                    } else {
                        postRtmpStatus("Trying to play RTMP")
                        Log.i(TAG, "Retrying RTMP connection: $videoSourceUrl (attempt $attemptCount)")
                    }

                    val bufferForPlaybackMs = try {
                        withContext(Dispatchers.IO) {
                            storageRepository.rtmpSourceBufferForPlaybackMsFlow.first()
                        }
                    } catch (e: Exception) {
                        application.getString(com.dimadesu.lifestreamer.R.string.default_rtmp_buffer_for_playback_ms).toInt()
                    }

                    val exoPlayerInstance = try {
                        createExoPlayer(application, videoSourceUrl, bufferForPlaybackMs)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare ExoPlayer for RTMP preview: ${e.message}", e)
                        null
                    }

                    if (exoPlayerInstance == null) {
                        // Set bitmap fallback on first attempt only
                        if (isFirstAttempt) {
                            switchToBitmapFallback(currentStreamer, testBitmap, streamingMediaProjection, mediaProjectionHelper)
                        }
                        if (isActive) {
                            postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 seconds")
                        }
                        delay(5000)
                        continue
                    }

                    try {
                        // Prepare and wait for the RTMP player to be ready before touching streamer
                        // ExoPlayer operations must run on Main thread
                        exoPlayerInstance.prepare()
                        exoPlayerInstance.playWhenReady = true
                        val ready = awaitReady(exoPlayerInstance, bufferForPlaybackMs.toLong() + 5000L)
                        if (!ready) throw Exception("ExoPlayer did not become ready")

                        // ExoPlayer appears ready. Attach RTMP video and audio to the streamer.
                        try {
                            // Add delay before switching sources to allow previous sources to fully release
                            // This prevents resource conflicts when hot-swapping sources
                            delay(300)
                            
                            // Switch video source
                            currentStreamer.setVideoSource(RTMPVideoSource.Factory(exoPlayerInstance))
                            
                            // Switch audio source immediately after video
                            // Set audio source: prefer MediaProjection if streaming, otherwise microphone
                            val isStreaming = currentStreamer.isStreamingFlow.value == true
                            val projection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                            val currentAudioSource = currentStreamer.audioInput?.sourceFlow?.value
                            val currentAudioIsMediaProjection = currentAudioSource is io.github.thibaultbee.streampack.core.elements.sources.IMediaProjectionSource
                            
                            if (isStreaming && projection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                // Use MediaProjection audio when streaming
                                // But check if we already have MediaProjection audio to avoid "audio policy" error
                                if (currentAudioIsMediaProjection) {
                                    Log.i(TAG, "MediaProjection audio already set, keeping it for RTMP")
                                } else {
                                    try {
                                        currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(projection))
                                        Log.i(TAG, "Set MediaProjection audio for RTMP")
                                    } catch (ae: Exception) {
                                        Log.w(TAG, "MediaProjection audio failed, using conditional source: ${ae.message}")
                                        try {
                                            currentStreamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                                        } catch (micEx: Exception) {
                                            Log.w(TAG, "Conditional source fallback failed: ${micEx.message}")
                                        }
                                    }
                                }
                            } else {
                                // Use conditional source when not streaming or MediaProjection unavailable
                                try {
                                    currentStreamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                                } catch (ae: Exception) {
                                    Log.w(TAG, "Failed to set conditional audio source: ${ae.message}")
                                }
                                
                                // Launch background task to upgrade to MediaProjection if streaming starts
                                if (!isStreaming && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    CoroutineScope(Dispatchers.Default).launch {
                                        try {
                                            val deadline = System.currentTimeMillis() + 10_000L
                                            var upgradedProjection = projection ?: mediaProjectionHelper.getMediaProjection()
                                            if (upgradedProjection == null) {
                                                while (System.currentTimeMillis() < deadline) {
                                                    delay(500L)
                                                    upgradedProjection = mediaProjectionHelper.getMediaProjection()
                                                    if (upgradedProjection != null) break
                                                }
                                            }
                                            upgradedProjection?.let { mp ->
                                                if (currentStreamer.isStreamingFlow.value == true) {
                                                    try {
                                                        currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(mp))
                                                        Log.d(TAG, "Upgraded to MediaProjection audio")
                                                    } catch (upgradeEx: Exception) {
                                                        Log.w(TAG, "MediaProjection upgrade failed: ${upgradeEx.message}")
                                                    }
                                                }
                                            }
                                        } catch (bgEx: Exception) {
                                            Log.w(TAG, "Background MediaProjection upgrade failed: ${bgEx.message}")
                                        }
                                    }
                                }
                            }
                            
                            // Clear status message and notify caller after both video and audio are set
                            postRtmpStatus(null)
                            Log.i(TAG, "Successfully connected to RTMP source (video + audio)")
                            
                            // Notify caller that RTMP is connected (for monitoring)
                            onRtmpConnected?.invoke(exoPlayerInstance)
                            
                            // Success - exit retry loop
                            return@launch
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to attach RTMP exoplayer to streamer: ${e.message}", e)
                            try { exoPlayerInstance.release() } catch (_: Exception) {}
                            
                            if (isFirstAttempt) {
                                switchToBitmapFallback(currentStreamer, testBitmap, streamingMediaProjection, mediaProjectionHelper)
                            }
                            // Wait and retry
                            if (isActive) {
                                postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 seconds")
                            }
                            delay(5000)
                            continue
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "RTMP playback failed or timed out: ${t.message}", t)
                        try { exoPlayerInstance.release() } catch (_: Exception) {}
                        
                        if (isFirstAttempt) {
                            switchToBitmapFallback(currentStreamer, testBitmap, streamingMediaProjection, mediaProjectionHelper)
                        }
                        // Wait and retry
                        if (isActive) {
                            postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 seconds")
                        }
                        delay(5000)
                        continue
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "switchToRtmpSource unexpected error: ${e.message}", e)
                    // Wait and retry
                    if (isActive) {
                        postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 seconds")
                    }
                    delay(5000)
                    continue
                }
            }
        }
    }
}
