package com.dimadesu.lifestreamer.srtla

import android.content.Context
import android.util.Log
import com.dimadesu.bondbunny.NativeSrtlaJni
import com.dimadesu.bondbunny.SrtlaSender
import com.dimadesu.bondbunny.moblink.MoblinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manages the embedded SRTLA native proxy within LifeStreamer.
 *
 * Delegates all network management and native lifecycle to [SrtlaSender]
 * from the shared srtla-lib module (bond-bunny/srtla-lib).
 *
 * ### Moblink integration
 * Pass an already-started [MoblinkManager] to [start]. After the SRTLA stack is running,
 * [start] calls [MoblinkManager.connectToSrtla] to activate tunnels for any waiting relays.
 * On [stop], the destination is cleared so relays park in the waiting room and remain
 * WebSocket-connected for the next stream start.
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
     *
     * @param moblinkManager Optional pre-started [MoblinkManager]. If provided, tunnels are
     *   activated for all waiting relays once SRTLA is running.
     */
    suspend fun start(
        context: Context,
        receiverHost: String,
        receiverPort: String,
        listenPort: String,
        moblinkManager: MoblinkManager? = null,
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

        // Phase 2: activate Moblink tunnels now that the SRTLA destination port is known.
        if (moblinkManager != null && isRunning) {
            Log.i(TAG, "Activating Moblink relay tunnels → $receiverHost:$receiverPort")
            moblinkManager.connectToSrtla(receiverHost, receiverPort.toIntOrNull() ?: 0)
        }
    }

    /**
     * Stop the SRTLA proxy and release all resources.
     *
     * If a [MoblinkManager] was passed to [start], call [MoblinkManager.connectToSrtla] with
     * an empty host / port=0 before calling [stop] so relays park in the waiting room and
     * remain connected for the next stream.
     */
    fun stop() {
        Log.i(TAG, "Stopping SRTLA")
        sender?.stop()
        sender = null
    }
}
