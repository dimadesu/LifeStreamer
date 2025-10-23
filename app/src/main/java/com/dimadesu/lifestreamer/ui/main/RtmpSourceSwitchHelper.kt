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
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import com.dimadesu.lifestreamer.rtmp.audio.ExoPlayerAudioSourceFactory
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import kotlinx.coroutines.isActive

internal object RtmpSourceSwitchHelper {
    private const val TAG = "RtmpSourceSwitchHelper"

    suspend fun createExoPlayer(
        application: Application, 
        url: String,
        audioProcessors: Array<androidx.media3.common.audio.AudioProcessor> = emptyArray()
    ): ExoPlayer =
        withContext(Dispatchers.Main) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    250, // Start playback after only 250ms of buffering
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()

            // Create custom renderer factory if we have audio processors
            val renderersFactory = if (audioProcessors.isNotEmpty()) {
                object : androidx.media3.exoplayer.DefaultRenderersFactory(application) {
                    override fun buildAudioSink(
                        context: android.content.Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): androidx.media3.exoplayer.audio.AudioSink {
                        return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                            .setAudioProcessors(audioProcessors)
                            .build()
                    }
                }
            } else {
                androidx.media3.exoplayer.DefaultRenderersFactory(application)
            }

            val exoPlayer = ExoPlayer.Builder(application)
                .setRenderersFactory(renderersFactory)
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
            // Set video to bitmap first
            streamer.setVideoSource(BitmapSourceFactory(bitmap))
            
            // Add delay before switching audio to allow clean transition
            // Increased delay gives more time for previous audio source to fully release
            delay(300)
            
            // Now set audio to microphone
            streamer.setAudioSource(MicrophoneSourceFactory())
            Log.i(TAG, "Switched to bitmap fallback with microphone audio")
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
            while (attemptCount < maxAttempts && isActive) {
                attemptCount++
                val isFirstAttempt = attemptCount == 1
                
                try {
                    // Check cancellation before showing status
                    if (!isActive) break
                    
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

                    // Create audio processor for extracting audio from ExoPlayer
                    val audioProcessor = com.dimadesu.lifestreamer.rtmp.audio.ExoPlayerAudioProcessor()
                    
                    val exoPlayerInstance = try {
                        createExoPlayer(application, videoSourceUrl, arrayOf(audioProcessor))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare ExoPlayer for RTMP preview: ${e.message}", e)
                        null
                    }

                    if (exoPlayerInstance == null) {
                        // Set bitmap fallback on first attempt only
                        if (isFirstAttempt) {
                            switchToBitmapFallback(currentStreamer, testBitmap)
                        }
                        if (isActive) {
                            postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                        }
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
                            
                            // Notify caller that RTMP is connected (for monitoring)
                            onRtmpConnected?.invoke(exoPlayerInstance)

                            // Add delay before switching audio to allow clean transition
                            // Increased delay gives more time for previous audio source to fully release
                            delay(300)

                            // Set audio source: Use ExoPlayer audio directly from RTMP stream
                            // This extracts decoded PCM audio with automatic format conversion
                            try {
                                currentStreamer.setAudioSource(ExoPlayerAudioSourceFactory(exoPlayerInstance, audioProcessor))
                                Log.i(TAG, "Set ExoPlayer audio source for RTMP (direct extraction with format conversion)")
                            } catch (ae: Exception) {
                                Log.w(TAG, "ExoPlayer audio source failed, falling back to microphone: ${ae.message}", ae)
                                try {
                                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
                                } catch (micEx: Exception) {
                                    Log.w(TAG, "Microphone fallback failed: ${micEx.message}")
                                }
                            }
                            
                            // Success - exit retry loop
                            return@launch
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to attach RTMP exoplayer to streamer: ${e.message}", e)
                            try { exoPlayerInstance.release() } catch (_: Exception) {}
                            
                            if (isFirstAttempt) {
                                switchToBitmapFallback(currentStreamer, testBitmap)
                            }
                            // Wait and retry
                            if (isActive) {
                                postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                            }
                            delay(5000)
                            continue
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "RTMP playback failed or timed out: ${t.message}", t)
                        try { exoPlayerInstance.release() } catch (_: Exception) {}
                        
                        if (isFirstAttempt) {
                            switchToBitmapFallback(currentStreamer, testBitmap)
                        }
                        // Wait and retry
                        if (isActive) {
                            postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                        }
                        delay(5000)
                        continue
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "switchToRtmpSource unexpected error: ${e.message}", e)
                    // Wait and retry
                    if (isActive) {
                        postRtmpStatus("Couldn't play RTMP stream. Retrying in 5 sec")
                    }
                    delay(5000)
                    continue
                }
            }
        }
    }
}
