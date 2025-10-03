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
     * Returns true if RTMP attach succeeded, false otherwise.
     */
    suspend fun switchToRtmpSource(
        application: Application,
        currentStreamer: SingleStreamer,
        testBitmap: Bitmap,
        storageRepository: DataStoreRepository,
        mediaProjectionHelper: MediaProjectionHelper,
        streamingMediaProjection: MediaProjection?,
        postError: (String) -> Unit
    ): Boolean {
        try {

            val videoSourceUrl = try {
                storageRepository.rtmpVideoSourceUrlFlow.first()
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
                // Nothing we can do, fallback to bitmap
                postError("RTMP preview preparation failed, staying on bitmap")
                switchToBitmapFallback(currentStreamer, testBitmap)
                return false
            }

            try {
                // Prepare and wait for the RTMP player to be ready before touching streamer
                withTimeout(3000) {
                    exoPlayerInstance.prepare()
                    exoPlayerInstance.playWhenReady = true
                    val ready = awaitReady(exoPlayerInstance)
                    if (!ready) throw Exception("ExoPlayer did not become ready")
                }

                // ExoPlayer appears ready. Attach RTMP video to the streamer. Only fall
                // back to bitmap if attaching fails. This avoids multiple setVideoSource
                // calls during an active stream which can cause the pipeline to stop.
                try {
                    currentStreamer.setVideoSource(RTMPVideoSource.Factory(exoPlayerInstance))

                    // Attach microphone immediately as a safe default for audio so we don't
                    // block video switch on MediaProjection permission/state. We'll try to
                    // upgrade to MediaProjection audio in the background if it becomes
                    // available shortly.
                    try {
                        currentStreamer.setAudioSource(MicrophoneSourceFactory())
                    } catch (ae: Exception) {
                        Log.w(TAG, "Attaching microphone audio failed: ${ae.message}")
                    }

                    // Background task: attempt to upgrade to MediaProjection audio for a short
                    // period (10s) if MediaProjection becomes available.
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to attach RTMP exoplayer to streamer: ${e.message}", e)
                    postError("Failed to attach RTMP source: ${e.message}")
                    try { exoPlayerInstance.release() } catch (_: Exception) {}
                    // Fall back to bitmap now that RTMP attach failed
                    switchToBitmapFallback(currentStreamer, testBitmap)
                    return false
                }

                // Success
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "RTMP playback failed or timed out: ${t.message}", t)
                postError("RTMP playback failed - staying on fallback source")
                try { exoPlayerInstance.release() } catch (_: Exception) {}
                // Fall back to bitmap if prepare/ready failed
                switchToBitmapFallback(currentStreamer, testBitmap)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToRtmpSource unexpected error: ${e.message}", e)
            postError("RTMP switch failed: ${e.message}")
            return false
        }
    }
}
