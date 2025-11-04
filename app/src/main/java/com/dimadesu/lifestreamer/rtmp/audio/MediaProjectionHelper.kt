package com.dimadesu.lifestreamer.rtmp.audio

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Helper class to manage MediaProjection for audio capture.
 * This is needed to capture ExoPlayer audio output using AudioPlaybackCapture API.
 * Uses a foreground service to satisfy Android 14+ requirements.
 */
class MediaProjectionHelper(private val context: Context) {

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var mediaProjectionService: MediaProjectionService? = null
    private var isBound = false
    private var onProjectionReady: ((MediaProjection?) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "Service connection callback triggered - className: $className")
            try {
                val binder = service as MediaProjectionService.LocalBinder
                mediaProjectionService = binder.getService()
                isBound = true
                Log.i(TAG, "Service connected successfully - isBound: $isBound")

                // Get MediaProjection from service and notify callback
                val projection = mediaProjectionService?.getMediaProjection()
                Log.i(TAG, "MediaProjection retrieved from service: ${if (projection != null) "SUCCESS" else "NULL"}")

                // Check if we have a callback waiting
                val callback = onProjectionReady
                Log.i(TAG, "Callback available: ${callback != null}")

                if (callback != null) {
                    Log.i(TAG, "Invoking MediaProjection callback with projection: ${if (projection != null) "SUCCESS" else "NULL"}")
                    callback.invoke(projection)
                    onProjectionReady = null
                    Log.i(TAG, "MediaProjection callback completed")
                } else {
                    Log.w(TAG, "No callback available to invoke - this might be a timing issue")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onServiceConnected: ${e.message}", e)
                onProjectionReady?.invoke(null)
                onProjectionReady = null
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mediaProjectionService = null
            isBound = false
            Log.i(TAG, "Service disconnected")
        }
    }

    companion object {
        private const val TAG = "MediaProjectionHelper"
    }

    /**
     * Register activity result launcher for MediaProjection permission.
     * Call this from Fragment.onCreate() or Activity.onCreate().
     */
    fun registerLauncher(fragment: Fragment): ActivityResultLauncher<Intent> {
        return fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleProjectionResult(result.resultCode, result.data)
        }
    }

    /**
     * Register activity result launcher for MediaProjection permission.
     * Call this from Activity.onCreate().
     */
    fun registerLauncher(activity: Activity): ActivityResultLauncher<Intent> {
        if (activity is ComponentActivity) {
            return activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                handleProjectionResult(result.resultCode, result.data)
            }
        } else {
            throw IllegalArgumentException("Activity must extend ComponentActivity")
        }
    }

    /**
     * Request MediaProjection permission and get the MediaProjection instance.
     *
     * @param launcher The ActivityResultLauncher registered with registerLauncher()
     * @param callback Called when MediaProjection is ready (success) or null (failure)
     */
    fun requestProjection(
        launcher: ActivityResultLauncher<Intent>,
        callback: (MediaProjection?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onProjectionReady = callback
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            launcher.launch(intent)
        } else {
            Log.e(TAG, "MediaProjection audio capture requires Android 10 (API 29) or higher")
            callback(null)
        }
    }

    private fun handleProjectionResult(resultCode: Int, data: Intent?) {
        Log.i(TAG, "handleProjectionResult called - resultCode: $resultCode, data: ${data != null}")
        val callback = onProjectionReady
        Log.i(TAG, "Current callback available: ${callback != null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                Log.i(TAG, "Permission granted - ensuring clean state before starting...")

                // Force cleanup any previous service binding to avoid conflicts
                // But preserve the current callback!
                if (isBound) {
                    Log.w(TAG, "Previous service binding detected - cleaning up...")
                    val savedCallback = onProjectionReady
                    release()
                    onProjectionReady = savedCallback // Restore callback after cleanup
                    Log.i(TAG, "Callback restored after cleanup: ${onProjectionReady != null}")
                    // Give a small delay for cleanup to complete
                    Thread.sleep(100)
                }

                Log.i(TAG, "Starting foreground service...")
                // Start foreground service to satisfy Android 14+ requirements
                startForegroundService(resultCode, data)

                Log.i(TAG, "Foreground service started - binding to service...")
                // Bind to service to get MediaProjection
                bindToService()

                Log.i(TAG, "MediaProjection service started and binding initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaProjection service: ${e.message}", e)
                onProjectionReady = null
                callback?.invoke(null)
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied by user - resultCode: $resultCode")
            onProjectionReady = null
            callback?.invoke(null)
        }
    }

    private fun startForegroundService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(context, MediaProjectionService::class.java).apply {
            putExtra("result_code", resultCode)
            putExtra("result_data", data)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        Log.i(TAG, "Started MediaProjection foreground service")
    }

    private fun bindToService() {
        val serviceIntent = Intent(context, MediaProjectionService::class.java)
        Log.i(TAG, "Attempting to bind to service with intent: $serviceIntent")

        if (isBound) {
            Log.w(TAG, "Service already bound - skipping bind attempt")
            return
        }

        try {
            val bindResult = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService() returned: $bindResult")
            if (!bindResult) {
                Log.e(TAG, "bindService() returned false - binding failed")
                onProjectionReady?.invoke(null)
                onProjectionReady = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during bindService(): ${e.message}", e)
            onProjectionReady?.invoke(null)
            onProjectionReady = null
        }
    }

    /**
     * Get the current MediaProjection if available.
     */
    fun getMediaProjection(): MediaProjection? {
        return if (isBound) {
            mediaProjectionService?.getMediaProjection()
        } else {
            Log.w(TAG, "Service not bound, cannot get MediaProjection")
            null
        }
    }

    /**
     * Clear the MediaProjection without stopping the service.
     * Use this when stopping stream to ensure we don't reuse expired tokens.
     */
    fun clearMediaProjection() {
        Log.i(TAG, "Clearing MediaProjection from service")
        if (isBound) {
            mediaProjectionService?.clearMediaProjection()
        }
    }

    /**
     * Release the MediaProjection resources.
     * Call this when you're done with audio capture.
     */
    fun release() {
        Log.i(TAG, "release() called - isBound: $isBound, mediaProjectionService: ${mediaProjectionService != null}")

        // Unbind from service
        if (isBound) {
            try {
                Log.i(TAG, "Unbinding from MediaProjection service...")
                context.unbindService(serviceConnection)
                isBound = false
                Log.i(TAG, "Successfully unbound from service")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding from service: ${e.message}", e)
                isBound = false
            }
        } else {
            Log.i(TAG, "Service was not bound, skipping unbind")
        }

        // Stop the foreground service
        try {
            val serviceIntent = Intent(context, MediaProjectionService::class.java)
            val stopResult = context.stopService(serviceIntent)
            Log.i(TAG, "stopService() returned: $stopResult")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}", e)
        }

        mediaProjectionService = null
        onProjectionReady = null

        Log.i(TAG, "MediaProjection released - cleanup completed")
    }
}