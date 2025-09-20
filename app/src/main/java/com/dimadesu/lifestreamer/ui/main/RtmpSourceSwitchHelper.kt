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

    suspend fun awaitReady(player: ExoPlayer, timeoutMs: Long = 5000): Boolean {
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                if (cont.isActive) cont.resume(true) {}
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (cont.isActive) cont.resume(false) {}
                        }
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
            Log.i(TAG, "Switched streamer to bitmap fallback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set bitmap fallback source: ${e.message}")
        }
    }

    /**
     * Full flow to switch a streamer from camera to an RTMP source:
     * - stop streaming if necessary
     * - switch to bitmap fallback immediately
     * - prepare ExoPlayer
     * - wait until ready (with timeout)
     * - attach RTMP video and appropriate audio source
     * - restart streaming if it was running before
     * Returns true if RTMP attach succeeded, false otherwise.
     */
    suspend fun switchToRtmpSource(
        application: Application,
        currentStreamer: SingleStreamer,
        testBitmap: Bitmap,
        storageRepository: DataStoreRepository,
        mediaProjectionHelper: MediaProjectionHelper,
        streamingMediaProjection: MediaProjection?,
        startServiceStreaming: suspend (MediaDescriptor) -> Boolean,
        stopServiceStreaming: suspend () -> Boolean,
        postError: (String) -> Unit
    ): Boolean {
        var wasStreaming = false
        try {
            if (currentStreamer.isStreamingFlow.value == true) {
                Log.i(TAG, "Temporarily stopping camera stream for source switch")
                wasStreaming = true
                try {
                    stopServiceStreaming()
                    delay(100)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
                }
            }

            // Immediately switch to bitmap fallback
            switchToBitmapFallback(currentStreamer, testBitmap)

            val videoSourceUrl = try {
                storageRepository.rtmpVideoSourceUrlFlow.first()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read RTMP video source URL from storage: ${e.message}")
                application.getString(com.dimadesu.lifestreamer.R.string.rtmp_source_default_url)
            }

            val exoPlayerInstance = try {
                createExoPlayer(application, videoSourceUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare ExoPlayer for RTMP preview: ${e.message}")
                null
            }

            if (exoPlayerInstance == null) {
                postError("RTMP preview preparation failed, staying on bitmap")
                if (wasStreaming) {
                    try {
                        val descriptor = storageRepository.endpointDescriptorFlow.first()
                        startServiceStreaming(descriptor)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart streaming with bitmap after RTMP prep failure: ${e.message}")
                    }
                }
                return false
            }

            try {
                withTimeout(6000) {
                    exoPlayerInstance.prepare()
                    exoPlayerInstance.playWhenReady = true
                    val ready = awaitReady(exoPlayerInstance)
                    if (!ready) throw Exception("ExoPlayer did not become ready")
                }
                Log.i(TAG, "ExoPlayer signaled ready - switching StreamPack source to RTMP")

                try {
                    currentStreamer.setVideoSource(RTMPVideoSource.Factory(exoPlayerInstance))
                    val mediaProjection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                    if (mediaProjection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        try {
                            currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
                        } catch (ae: Exception) {
                            Log.w(TAG, "MediaProjection audio attach failed, using microphone: ${ae.message}")
                            currentStreamer.setAudioSource(MicrophoneSourceFactory())
                        }
                    } else {
                        currentStreamer.setAudioSource(MicrophoneSourceFactory())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to attach RTMP exoplayer to streamer: ${e.message}")
                    postError("Failed to attach RTMP source: ${e.message}")
                    try { exoPlayerInstance.release() } catch (_: Exception) {}
                    if (wasStreaming) {
                        try {
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            startServiceStreaming(descriptor)
                        } catch (se: Exception) {
                            Log.e(TAG, "Failed to restart streaming with bitmap after attach failure: ${se.message}")
                        }
                    }
                    return false
                }

                if (wasStreaming) {
                    try {
                        val descriptor = storageRepository.endpointDescriptorFlow.first()
                        startServiceStreaming(descriptor)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error restarting stream with RTMP: ${e.message}")
                        postError("Failed to restart stream with RTMP: ${e.message}")
                    }
                }
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "RTMP preview failed or timed out: ${t.message}")
                postError("RTMP preview failed - staying on fallback source")
                try { exoPlayerInstance.release() } catch (_: Exception) {}
                if (wasStreaming) {
                    try {
                        val descriptor = storageRepository.endpointDescriptorFlow.first()
                        startServiceStreaming(descriptor)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart streaming with bitmap after RTMP preview timeout: ${e.message}")
                    }
                }
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToRtmpSource unexpected error: ${e.message}", e)
            postError("RTMP switch failed: ${e.message}")
            return false
        }
    }
}
