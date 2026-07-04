package com.dimadesu.lifestreamer.srtla

import android.content.Context
import android.util.Log
import com.dimadesu.bondbunny.NativeSrtlaJni
import com.dimadesu.bondbunny.SrtlaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * LifeStreamer-specific wrapper around [SrtlaEngine].
 *
 * Adds:
 * - Kotlin singleton (`object`) access
 * - Coroutine-friendly `suspend fun start()` with native bind delay
 * - [StateFlow] relay status for Compose UI observation
 *
 * All SRTLA + Moblink wiring is handled by [SrtlaEngine] in srtla-lib.
 *
 * ### Lifecycle
 *
 * **Phase 1 — Moblink server (call from service `onCreate`):**
 * ```
 * SrtlaManager.startMoblink(context, name, password, port)
 * ```
 *
 * **Phase 2 — SRTLA proxy (call when starting a stream):**
 * ```
 * SrtlaManager.start(context, host, port, listenPort)
 * ```
 *
 * **Stream stop:**
 * ```
 * SrtlaManager.stop()
 * ```
 */
object SrtlaManager {

    private const val TAG = "SrtlaManager"

    private var engine: SrtlaEngine? = null

    /** True when the native SRTLA thread is running. */
    val isRunning: Boolean get() = NativeSrtlaJni.isRunningSrtlaNative()

    // -------------------------------------------------------------------------
    // Relay status for UI
    // -------------------------------------------------------------------------

    private val _relays = MutableStateFlow<List<SrtlaEngine.RelayInfo>>(emptyList())

    /** Observable list of connected Moblink relays with their latest status. */
    val relays: StateFlow<List<SrtlaEngine.RelayInfo>> = _relays.asStateFlow()

    // -------------------------------------------------------------------------
    // Phase 1: Moblink server lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start (or restart) the Moblink WebSocket server.
     *
     * If SRTLA is already running, tunnels are activated immediately.
     */
    fun startMoblink(context: Context, name: String, password: String, port: Int) {
        ensureEngine(context).startMoblink(name, password, port)
        _relays.value = engine?.relays ?: emptyList()
    }

    /**
     * Stop the Moblink WebSocket server and disconnect all relays.
     */
    fun stopMoblink() {
        engine?.stopMoblink()
        _relays.value = emptyList()
    }

    // -------------------------------------------------------------------------
    // Phase 2: SRTLA proxy lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the SRTLA proxy.
     *
     * Suspends until the native library has been launched and the listen port is
     * bound (~300 ms). If the Moblink server is active, tunnels are activated
     * automatically.
     */
    suspend fun start(
        context: Context,
        receiverHost: String,
        receiverPort: String,
        listenPort: String,
    ) {
        if (isRunning) {
            Log.i(TAG, "Already running — skipping start")
            return
        }

        val e = ensureEngine(context)

        withContext(Dispatchers.IO) {
            e.startSrtla(receiverHost, receiverPort, listenPort, object : SrtlaEngine.Listener {
                override fun onSrtlaStatus(message: String) {
                    Log.i(TAG, "SrtlaSender: $message")
                }
                override fun onSrtlaError(message: String) {
                    Log.e(TAG, "SrtlaSender error: $message")
                }
                override fun onRelaysChanged(relays: List<SrtlaEngine.RelayInfo>) {
                    _relays.value = relays
                }
            })
        }

        // Give the native pthread time to bind the listen port before the SRT stack
        // tries to connect to 127.0.0.1:<listenPort>.
        delay(300)
        Log.i(TAG, "SRTLA proxy ready (isRunning=$isRunning)")
    }

    /**
     * Stop the SRTLA proxy and release all resources.
     * Moblink relays are parked (destination reset) but stay WebSocket-connected.
     */
    fun stop() {
        engine?.stopSrtla()
    }

    // -------------------------------------------------------------------------
    // Engine management
    // -------------------------------------------------------------------------

    private fun ensureEngine(context: Context): SrtlaEngine {
        return engine ?: SrtlaEngine(context.applicationContext).also { engine = it }
    }
}
