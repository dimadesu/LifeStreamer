package com.dimadesu.lifestreamer.rtmp.audio.buffer

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.core.elements.data.RawFrame

class AudioRecordWrapper3(
    private val exoPlayer: ExoPlayer,
    private val audioBuffer: CircularPcmBuffer,
) {
    companion object {
        private const val TAG = "AudioRecordWrapper3"
    }

    /**
     * Starts recording audio. Add custom behavior here if needed.
     */
    fun startRecording() {
        audioBuffer.clear()
        Log.i(TAG, "Audio buffer cleared before streaming start.")


//       if (Looper.myLooper() == Looper.getMainLooper()) {
//           exoPlayer.prepare()
//           exoPlayer.playWhenReady = true
//       } else {
           Handler(Looper.getMainLooper()).post {
               exoPlayer.prepare()
               exoPlayer.playWhenReady = true
           }
//       }
    }

    /**
     * Stops recording audio.
     */
    fun stop() {
        // I don't know why this doesn't seem to need this main thread thing.
        // It actually breaks transition to setting activity if it's enabled here
        // I think audio input's stopStream is called on activity destroy or smth like that
        // Also maybe smth to do with suspend
//       withContext(Dispatchers.Main) {
            // exoPlayer.stop()
//       }

       Handler(Looper.getMainLooper()).post {
           exoPlayer.stop()
       }
    }

    /**
     * Reads audio data into the provided buffer.
     */
    fun read(streamPackAudioFrame: RawFrame) {
        val exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())
//        streamPackAudioFrame.timestampInUs = System.nanoTime() / 1000

        if (exoPlayerAudioFrame != null) {
            val (data, timestamp) = exoPlayerAudioFrame

            streamPackAudioFrame.rawBuffer.put(data)

            streamPackAudioFrame.timestampInUs = timestamp
        }

        streamPackAudioFrame.rawBuffer.flip()
    }

    /**
     * Releases the AudioRecord resources.
     */
    fun release() {
        // I think this also doesn't need main thread stuff for exoPlayer
//       if (Looper.myLooper() == Looper.getMainLooper()) {
//            exoPlayer.release()
//            exoPlayer = null
//       } else {
           Handler(Looper.getMainLooper()).post {
               exoPlayer.release()
//           }
       }
        audioBuffer.clear()
    }
}
