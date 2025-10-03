package com.dimadesu.lifestreamer.ui.main

import android.app.Application
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.graphics.Bitmap
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
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

internal object RtmpSourceSwitchHelper {
    private const val TAG = "RtmpSourceSwitchHelper"

    suspend fun createExoPlayer(application: Application, url: String): ExoPlayer =
        withContext(Dispatchers.Main) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    250, // Start playback after only 250ms of buffering
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()

            val exoPlayer = ExoPlayer.Builder(application)
                .setLoadControl(loadControl)
                .build()

            val mediaItem = MediaItem.fromUri(url)
            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                DefaultDataSource.Factory(application)
            ).createMediaSource(mediaItem)

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.volume = 0f
            // Add a lightweight error listener so callers can observe failures in logs
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.w(TAG, "ExoPlayer RTMP error: ${error.message}")
                }
            })
            exoPlayer
        }

    suspend fun awaitReady(player: ExoPlayer, timeoutMs: Long = 2000): Boolean {
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

    suspend fun switchToBitmapFallback(streamer: SingleStreamer, bitmap: Bitmap) {
        try {
            streamer.setVideoSource(BitmapSourceFactory(bitmap))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set bitmap fallback source: ${e.message}")
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
     * Returns true if RTMP attach succeeded, false otherwise.
     */
    suspend fun switchToRtmpSource(
        application: Application,
        currentStreamer: SingleStreamer,
        testBitmap: Bitmap,
        storageRepository: DataStoreRepository,
        mediaProjectionHelper: MediaProjectionHelper,
        streamingMediaProjection: MediaProjection?,
        postError: (String) -> Unit,
        postRtmpStatus: (String?) -> Unit
    ): Boolean {
        var attemptCount = 0
        val maxAttempts = Int.MAX_VALUE // Keep retrying indefinitely
        
        // Start retry loop - use Main dispatcher for ExoPlayer thread safety
        CoroutineScope(Dispatchers.Main).launch {
            while (attemptCount < maxAttempts) {
                attemptCount++
                val isFirstAttempt = attemptCount == 1
                
                try {
                    // Show status message
                    if (isFirstAttempt) {
                        postRtmpStatus("Playing RTMP")
                        Log.i(TAG, "Attempting to connect to RTMP source (first attempt)")
                    } else {
                        postRtmpStatus("Trying to play RTMP")
                        Log.i(TAG, "Retrying RTMP connection (attempt $attemptCount)")
                    }

                    val videoSourceUrl = try {
                        withContext(Dispatchers.IO) {
                            storageRepository.rtmpVideoSourceUrlFlow.first()
                        }
                    } catch (e: Exception) {
                        application.getString(com.dimadesu.lifestreamer.R.string.rtmp_source_default_url)
                    }

                    val exoPlayerInstance = try {
                        createExoPlayer(application, videoSourceUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare ExoPlayer for RTMP preview: ${e.message}", e)
                        null
                    }

                    if (exoPlayerInstance == null) {
                        // Wait and retry
                        if (isFirstAttempt) {
                            postError("RTMP preview preparation failed, will retry")
                            switchToBitmapFallback(currentStreamer, testBitmap)
                        }
                        postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                        delay(5000)
                        continue
                    }

                    try {
                        // Prepare and wait for the RTMP player to be ready before touching streamer
                        // ExoPlayer operations must run on Main thread
                        withTimeout(3000) {
                            exoPlayerInstance.prepare()
                            exoPlayerInstance.playWhenReady = true
                            val ready = awaitReady(exoPlayerInstance)
                            if (!ready) throw Exception("ExoPlayer did not become ready")
                        }

                        // ExoPlayer appears ready. Attach RTMP video to the streamer.
                        try {
                            currentStreamer.setVideoSource(RTMPVideoSource.Factory(exoPlayerInstance))
                            
                            // Clear status message on success
                            postRtmpStatus(null)
                            Log.i(TAG, "Successfully connected to RTMP source")

                            // Attach microphone immediately as a safe default for audio
                            try {
                                currentStreamer.setAudioSource(MicrophoneSourceFactory())
                            } catch (ae: Exception) {
                                Log.w(TAG, "Attaching microphone audio failed: ${ae.message}")
                            }

                            // Background task: attempt to upgrade to MediaProjection audio
                            CoroutineScope(Dispatchers.Default).launch {
                                try {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        val deadline = System.currentTimeMillis() + 10_000L
                                        var projection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                                        if (projection == null) {
                                            while (System.currentTimeMillis() < deadline) {
                                                delay(500L)
                                                projection = mediaProjectionHelper.getMediaProjection()
                                                if (projection != null) break
                                            }
                                        }
                                        projection?.let { mp ->
                                            try {
                                                currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(mp))
                                                Log.d(TAG, "Upgraded audio source to MediaProjection")
                                            } catch (upgradeEx: Exception) {
                                                Log.w(TAG, "MediaProjection audio attach failed on upgrade, keeping microphone: ${upgradeEx.message}")
                                            }
                                        }
                                    }
                                } catch (bgEx: Exception) {
                                    Log.w(TAG, "Background MediaProjection upgrade failed: ${bgEx.message}")
                                }
                            }
                            
                            // Success - exit retry loop
                            return@launch
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to attach RTMP exoplayer to streamer: ${e.message}", e)
                            try { exoPlayerInstance.release() } catch (_: Exception) {}
                            
                            if (isFirstAttempt) {
                                postError("Failed to attach RTMP source, will retry")
                                switchToBitmapFallback(currentStreamer, testBitmap)
                            }
                            // Wait and retry
                            postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                            delay(5000)
                            continue
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "RTMP playback failed or timed out: ${t.message}", t)
                        try { exoPlayerInstance.release() } catch (_: Exception) {}
                        
                        if (isFirstAttempt) {
                            postError("RTMP playback failed, will retry")
                            switchToBitmapFallback(currentStreamer, testBitmap)
                        }
                        // Wait and retry
                        postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                        delay(5000)
                        continue
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "switchToRtmpSource unexpected error: ${e.message}", e)
                    if (isFirstAttempt) {
                        postError("RTMP switch failed, will retry")
                    }
                    // Wait and retry
                    postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                    delay(5000)
                    continue
                }
            }
        }
        
        // Return true immediately to indicate we've started the process
        return true
    }
}
