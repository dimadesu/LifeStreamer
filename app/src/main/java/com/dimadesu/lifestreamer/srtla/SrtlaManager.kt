package com.dimadesu.lifestreamer.srtla

import android.content.Context
import android.util.Log
import com.dimadesu.bondbunny.NativeSrtlaJni
import com.dimadesu.bondbunny.SrtlaSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manages the embedded SRTLA native proxy within LifeStreamer.
 *
 * Delegates all network management and native lifecycle to [SrtlaSender]
 * from the shared srtla-lib module (bond-bunny/srtla-lib).
 */
object SrtlaManager {

    private const val TAG = "SrtlaManager"

    private var sender: SrtlaSender? = null

    /** True when the native SRTLA thread is running. */
    val isRunning: Boolean get() = NativeSrtlaJni.isRunningSrtlaNative()

    /**
     * Start the SRTLA proxy.
     *
     * Suspends until the native library has been launched and the listen port is
     * bound (~300 ms). Idempotent: does nothing if [isRunning] is already true.
     */
    suspend fun start(
        context: Context,
        receiverHost: String,
        receiverPort: String,
        listenPort: String
    ) {
        if (isRunning) {
            Log.i(TAG, "Already running — skipping start")
            return
        }

        Log.i(TAG, "Starting SRTLA: $receiverHost:$receiverPort, listen on $listenPort")

        val s = SrtlaSender(context)
        sender = s

        withContext(Dispatchers.IO) {
            s.start(receiverHost, receiverPort, listenPort, object : SrtlaSender.Listener {
                override fun onStatus(message: String) {
                    Log.i(TAG, "SrtlaSender: $message")
                }
                override fun onError(message: String) {
                    Log.e(TAG, "SrtlaSender error: $message")
                }
            })
        }

        // Give the native pthread time to bind the listen port before the SRT stack
        // tries to connect to 127.0.0.1:<listenPort>.  In-process, ~200 ms is plenty.
        delay(300)
        Log.i(TAG, "SRTLA proxy ready (isRunning=$isRunning)")
    }

    /** Stop the SRTLA proxy and release all resources. */
    fun stop() {
        Log.i(TAG, "Stopping SRTLA")
        sender?.stop()
        sender = null
    }
}
