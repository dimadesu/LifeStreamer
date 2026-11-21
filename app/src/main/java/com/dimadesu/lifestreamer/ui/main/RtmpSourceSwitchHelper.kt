package com.dimadesu.lifestreamer.ui.main

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import com.dimadesu.lifestreamer.rtmp.audio.ExoPlayerAudioSourceFactory
import com.dimadesu.lifestreamer.rtmp.audio.RtmpAudioBridge
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.player.SrtDataSourceFactory
import com.dimadesu.lifestreamer.player.TsOnlyExtractorFactory
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

internal object RtmpSourceSwitchHelper {
    private const val TAG = "RtmpSourceSwitchHelper"
    internal const val MIN_STATUS_DISPLAY_MS = 1_000L

    /**
     * Compute how long to keep the current "Playing RTMP" / "Trying to play RTMP" status
     * visible before switching to a failure message, so fast connection errors don't flicker.
     */
    internal fun remainingStatusDisplayMs(
        elapsedMs: Long,
        minDisplayMs: Long = MIN_STATUS_DISPLAY_MS
    ): Long = (minDisplayMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)

    /**
     * Check if the URL is an SRT URL.
     */
    private fun isSrtUrl(url: String): Boolean {
        return url.lowercase().startsWith("srt://")
    }

    /**
     * Build a [DefaultLoadControl] using [bufferForPlaybackMs] for both initial start
     * and post-rebuffer resume so ExoPlayer honors the user's configured buffer cushion
     * instead of forcing ExoPlayer's hardcoded 5,000 ms rebuffer threshold.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    internal fun createLoadControl(bufferForPlaybackMs: Int): DefaultLoadControl {
        val playbackBufferMs = bufferForPlaybackMs.coerceIn(0, DefaultLoadControl.DEFAULT_MIN_BUFFER_MS)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, // 50 seconds
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, // 50 seconds
                playbackBufferMs, // Start playback after buffering this many ms
                playbackBufferMs  // Match user buffer on rebuffer instead of 5s default
            )
            .build()
    }

    /**
     * Fail fast on transport/IO errors for live RTMP/SRT sources so ExoPlayer surfaces
     * `onPlayerError` immediately instead of retrying internally across multiple seconds.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    internal fun createFailFastLoadErrorHandlingPolicy(): LoadErrorHandlingPolicy =
        object : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 0) {
            override fun getRetryDelayMsFor(
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long = C.TIME_UNSET
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun createExoPlayer(application: Application, url: String, bufferForPlaybackMs: Int): ExoPlayer =
        withContext(Dispatchers.Main) {
            val loadControl = createLoadControl(bufferForPlaybackMs)

            val renderersFactory = object : DefaultRenderersFactory(application) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(150_000)
                        .setMaxPcmBufferDurationUs(180_000)
                        .setPcmBufferMultiplicationFactor(2)
                        .build()
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                        .setAudioProcessors(arrayOf(RtmpAudioBridge.createTapAudioProcessor(startStaged = true)))
                        .build()
                }
            }

            val exoPlayer = ExoPlayer.Builder(application)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build()

            val mediaItem = MediaItem.fromUri(url)

            // Use SRT data source for srt:// URLs, otherwise use default (supports RTMP, etc.)
            val isSrt = isSrtUrl(url)
            Log.i(TAG, "Creating ${if (isSrt) "SRT" else "default"} media source for: $url")
            val mediaSourceFactory = if (isSrt) {
                ProgressiveMediaSource.Factory(
                    SrtDataSourceFactory(),
                    TsOnlyExtractorFactory()
                )
            } else {
                ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(application),
                    DefaultExtractorsFactory()
                )
            }
            val mediaSource = mediaSourceFactory
                .setLoadErrorHandlingPolicy(createFailFastLoadErrorHandlingPolicy())
                .createMediaSource(mediaItem)

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
                                if (cont.isActive) cont.resumeWith(Result.success(true))
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            try { player.removeListener(this) } catch (_: Exception) {}
                            if (cont.isActive) cont.resumeWith(Result.success(false))
                        }
                    }
                    // If the player is already ready, avoid registering the listener which
                    // could miss the READY event if it happened before listener registration.
                    val currentState = try { player.playbackState } catch (_: Exception) { -1 }
                    if (currentState == androidx.media3.common.Player.STATE_READY) {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                        return@suspendCancellableCoroutine
                    }
                    player.addListener(listener)
                    cont.invokeOnCancellation {
                        try { player.removeListener(listener) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "awaitReady timed out after ${timeoutMs}ms")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "awaitReady failed: ${e.message}")
            false
        }
    }

    /**
     * Switch to bitmap fallback for RTMP source.
     * Uses ExoPlayerAudioSource to pull/pad RTMP audio while retrying.
     */
    suspend fun switchToBitmapFallback(
        streamer: SingleStreamer,
        bitmap: Bitmap
    ) {
        try {
            // Set video to bitmap first
            streamer.setVideoSource(BitmapSourceFactory(bitmap))
            streamer.setAudioSource(ExoPlayerAudioSourceFactory())
            Log.i(TAG, "Switched to bitmap fallback with ExoPlayerAudioSource")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bitmap fallback source: ${e.message}", e)
        }
    }

    /**
     * Full flow to switch a streamer from camera to an RTMP source without
     * stopping or restarting the streamer service itself:
     * - prepare ExoPlayer for RTMP preview
     * - wait until ready (with timeout)
     * - switch to bitmap fallback if the initial connection fails
     * - attach RTMP video and ExoPlayer audio source
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
        postError: (String) -> Unit,
        postRtmpStatus: (String?) -> Unit,
        onRtmpConnected: ((ExoPlayer) -> Unit)? = null
    ): kotlinx.coroutines.Job {
        var attemptCount = 0
        val maxAttempts = Int.MAX_VALUE // Keep retrying indefinitely
        
        // Start retry loop - use Main dispatcher for ExoPlayer thread safety
        // Return the Job so caller can cancel it when switching back to camera
        return CoroutineScope(Dispatchers.Main).launch {
            var attemptStartTimeMs = 0L

            suspend fun ensureMinAttemptStatusVisible() {
                val remainingMs = remainingStatusDisplayMs(SystemClock.elapsedRealtime() - attemptStartTimeMs)
                if (remainingMs > 0) {
                    delay(remainingMs)
                }
            }

            suspend fun handleAttemptFailure(
                statusMessage: String,
                playerToRelease: ExoPlayer? = null
            ) {
                if (playerToRelease != null) {
                    try { playerToRelease.release() } catch (_: Exception) {}
                }
                if (attemptCount == 1 && isActive) {
                    switchToBitmapFallback(currentStreamer, testBitmap)
                }
                ensureMinAttemptStatusVisible()
                if (isActive) {
                    postRtmpStatus(statusMessage)
                }
                delay(5000)
            }

            while (attemptCount < maxAttempts && isActive) {
                attemptCount++
                val isFirstAttempt = attemptCount == 1
                
                try {
                    // Check cancellation before showing status
                    if (!isActive) break
                    
                    // Show status message
                    attemptStartTimeMs = SystemClock.elapsedRealtime()
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        application.getString(com.dimadesu.lifestreamer.R.string.default_rtmp_buffer_for_playback_ms).toInt()
                    }

                    val exoPlayerInstance = try {
                        createExoPlayer(application, videoSourceUrl, bufferForPlaybackMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare ExoPlayer for RTMP preview: ${e.message}", e)
                        null
                    }

                    if (exoPlayerInstance == null) {
                        handleAttemptFailure("Couldn't play RTMP stream. Retrying in 5 seconds")
                        continue
                    }

                    try {
                        // Prepare and wait for the RTMP player to be ready before touching streamer
                        // ExoPlayer operations must run on Main thread
                        exoPlayerInstance.prepare()
                        exoPlayerInstance.playWhenReady = true
                        val ready = awaitReady(exoPlayerInstance, bufferForPlaybackMs.toLong() + 5000L)
                        if (!ready) throw Exception("ExoPlayer did not become ready")

                        // FLV extractor locks tracks at FLV header time. If ExoPlayer
                        // connected before the RTMP server had audio flowing, the header
                        // has hasAudio=false and audio is permanently missing for this
                        // instance. Release and retry — next attempt will likely have audio.
                        val requireAudioTrack = try {
                            withContext(Dispatchers.IO) {
                                storageRepository.rtmpSourceRequireAudioTrackFlow.first()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            true
                        }
                        if (requireAudioTrack) {
                            val hasAudioTrack = exoPlayerInstance.currentTracks.groups.any { g ->
                                (0 until g.length).any { g.getTrackFormat(it).sampleMimeType?.startsWith("audio/") == true }
                            }
                            if (!hasAudioTrack) {
                                Log.w(TAG, "ExoPlayer has no audio tracks — RTMP server likely not sending audio yet. Retrying.")
                                handleAttemptFailure(
                                    statusMessage = "No audio track detected. Retrying in 5 seconds",
                                    playerToRelease = exoPlayerInstance
                                )
                                continue
                            }
                        }

                        // Promote staged pre-fill audio only once ExoPlayer is ready to go live
                        RtmpAudioBridge.promotePendingTap()

                        // ExoPlayer appears ready. Attach RTMP video and audio to the streamer.
                        try {
                            // Switch video source
                            currentStreamer.setVideoSource(RTMPVideoSource.Factory(exoPlayerInstance))
                            
                            // Switch audio source immediately after video.
                            try {
                                currentStreamer.setAudioSource(ExoPlayerAudioSourceFactory())
                                RtmpAudioBridge.trimToTargetBuffer()
                                Log.i(TAG, "Set ExoPlayerAudioSource for RTMP")
                            } catch (ae: Exception) {
                                Log.w(TAG, "Failed to set ExoPlayerAudioSource for RTMP: ${ae.message}")
                            }
                            
                            // Clear status message and notify caller after both video and audio are set
                            postRtmpStatus(null)
                            Log.i(TAG, "Successfully connected to RTMP source (video + audio)")
                            
                            // Notify caller that RTMP is connected (for monitoring)
                            onRtmpConnected?.invoke(exoPlayerInstance)
                            
                            // Success - exit retry loop
                            return@launch
                        } catch (e: CancellationException) {
                            // Coroutine cancelled (e.g. user toggled RTMP off, or disconnect handler
                            // is replacing this retry loop). Release ExoPlayer and propagate immediately.
                            Log.i(TAG, "RTMP source attachment cancelled, releasing ExoPlayer")
                            try { exoPlayerInstance.release() } catch (_: Exception) {}
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to attach RTMP exoplayer to streamer: ${e.message}", e)
                            handleAttemptFailure(
                                statusMessage = "Couldn't play RTMP stream. Retrying in 5 seconds",
                                playerToRelease = exoPlayerInstance
                            )
                            continue
                        }
                    } catch (e: CancellationException) {
                        Log.i(TAG, "RTMP playback cancelled, releasing ExoPlayer")
                        try { exoPlayerInstance.release() } catch (_: Exception) {}
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "RTMP playback failed or timed out: ${t.message}", t)
                        handleAttemptFailure(
                            statusMessage = "Couldn't play RTMP stream. Retrying in 5 seconds",
                            playerToRelease = exoPlayerInstance
                        )
                        continue
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "switchToRtmpSource unexpected error: ${e.message}", e)
                    ensureMinAttemptStatusVisible()
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
