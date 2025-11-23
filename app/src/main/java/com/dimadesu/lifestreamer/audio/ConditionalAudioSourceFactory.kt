package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import com.dimadesu.lifestreamer.audio.AppBluetoothSourceFactory

class ConditionalAudioSourceFactory : IAudioSourceInternal.Factory {
    private val TAG = "ConditionalAudioSourceFactory"

    override suspend fun create(context: Context): IAudioSourceInternal {
        val useBt = try { BluetoothAudioConfig.isEnabled() } catch (_: Throwable) { false }
        val device = try { BluetoothAudioConfig.getPreferredDevice() } catch (_: Throwable) { null }

        Log.i(TAG, "Conditional factory invoked: useBt=$useBt device=${device?.id}")

        return try {
            if (useBt && device != null) {
                Log.i(TAG, "Using app-level Bluetooth source for device id=${device.id}")
                AppBluetoothSourceFactory(device).create(context)
            } else {
                MicrophoneSourceFactory().create(context)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Audio source create failed: ${t.message}. Rethrowing.")
            throw t
        }
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // We prefer to avoid live-swapping audio sources. Return true to indicate the
        // currently configured source is acceptable (the service will handle routing).
        return true
    }
}
