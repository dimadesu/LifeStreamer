package com.dimadesu.lifestreamer.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple timer utility for delayed operations, similar to Moblin's SimpleTimer.
 * Provides single-shot execution after a timeout.
 */
class ReconnectTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var currentRunnable: Runnable? = null
    private val isScheduled = AtomicBoolean(false)

    /**
     * Schedules a single execution after the specified timeout.
     * Automatically cancels any previously scheduled execution.
     *
     * @param timeoutSeconds Delay in seconds before execution
     * @param action The action to execute
     */
    fun startSingleShot(timeoutSeconds: Int, action: () -> Unit) {
        stop() // Cancel any existing scheduled execution
        
        val runnable = Runnable {
            try {
                isScheduled.set(false)
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing reconnect action: ${e.message}", e)
            }
        }
        
        currentRunnable = runnable
        isScheduled.set(true)
        handler.postDelayed(runnable, timeoutSeconds * 1000L)
        
        Log.d(TAG, "Reconnect scheduled in $timeoutSeconds seconds")
    }

    /**
     * Cancels any pending execution.
     */
    fun stop() {
        currentRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            isScheduled.set(false)
            currentRunnable = null
            Log.d(TAG, "Reconnect timer stopped")
        }
    }

    /**
     * Checks if a reconnection is currently scheduled.
     */
    fun isScheduled(): Boolean = isScheduled.get()

    companion object {
        private const val TAG = "ReconnectTimer"
    }
}
