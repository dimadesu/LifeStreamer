package com.dimadesu.lifestreamer.srtla

import android.content.Context
import android.util.Log
import com.dimadesu.bondbunny.NativeSrtlaJni
import com.dimadesu.bondbunny.SrtlaSender
import com.dimadesu.bondbunny.moblink.MoblinkManager
import com.dimadesu.bondbunny.moblink.ThermalState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages the embedded SRTLA native proxy and Moblink relay server within LifeStreamer.
 *
 * Owns both [SrtlaSender] (native SRTLA bonding) and [MoblinkManager] (WebSocket relay
 * server). All relay wiring is handled internally — callers never need to know about
 * Moblink details.
 *
 * ### Lifecycle
 *
 * **Phase 1 — Moblink server (call as early as possible):**
 * ```
 * SrtlaManager.startMoblink(context, name, password, port)
 * ```
 * Relays connect, authenticate, and report battery/thermal status. No video flows yet.
 * Call [stopMoblink] on service destroy or when Moblink is disabled in settings.
 *
 * **Phase 2 — SRTLA proxy (call when starting a stream):**
 * ```
 * SrtlaManager.start(context, host, port, listenPort)
 * ```
 * Starts the native SRTLA bonding proxy. If Moblink is active, tunnels are activated
 * automatically — no extra call needed.
 *
 * **Stream stop:**
 * ```
 * SrtlaManager.stop()
 * ```
 * Stops SRTLA and parks Moblink relays (resets destination so they wait for next stream).
 * The Moblink WebSocket server stays alive.
 */
object SrtlaManager {

    private const val TAG = "SrtlaManager"

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var sender: SrtlaSender? = null
    private var moblinkManager: MoblinkManager? = null

    /** SRTLA receiver address — saved so we can pass it to connectToSrtla(). */
    private var srtlaHost: String = ""
    private var srtlaPort: Int = 0

    /** True when the native SRTLA thread is running. */
    val isRunning: Boolean get() = NativeSrtlaJni.isRunningSrtlaNative()

    // -------------------------------------------------------------------------
    // Relay status for UI
    // -------------------------------------------------------------------------

    /** Snapshot of a connected Moblink relay's state. */
    data class RelayInfo(
        val id: String,
        val name: String,
        val battery: Int?,
        val thermal: ThermalState?,
        val tunnelActive: Boolean,
    )

    private val _relays = MutableStateFlow<List<RelayInfo>>(emptyList())

    /** Observable list of connected Moblink relays with their latest status. */
    val relays: StateFlow<List<RelayInfo>> = _relays.asStateFlow()

    /** Internal mutable map keyed by relay ID for efficient updates. */
    private val relayMap = LinkedHashMap<String, RelayInfo>()

    private fun publishRelays() {
        _relays.value = relayMap.values.toList()
    }

    // -------------------------------------------------------------------------
    // Phase 1: Moblink server lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start (or restart) the Moblink WebSocket server.
     *
     * Idempotent with same parameters. If called with different parameters while already
     * running, the old server is stopped and a new one is started (matching Moblin's
     * `reloadMoblinkStreamer()` pattern).
     *
     * If SRTLA is already running, tunnels are activated immediately for any waiting relays.
     */
    fun startMoblink(context: Context, name: String, password: String, port: Int) {
        val current = moblinkManager
        if (current != null) {
            // Already running — stop first so we can restart with new config
            Log.i(TAG, "Restarting Moblink server with new config")
            current.stop()
            moblinkManager = null
            relayMap.clear()
            publishRelays()
        }

        Log.i(TAG, "Starting Moblink server (port=$port, name='$name')")
        val mgr = MoblinkManager(context, name, password, port)
        mgr.start(makeMoblinkListener())
        moblinkManager = mgr

        // If SRTLA is already running (mid-stream enable), activate tunnels immediately
        if (isRunning && srtlaPort != 0) {
            Log.i(TAG, "SRTLA already running — activating Moblink tunnels → $srtlaHost:$srtlaPort")
            mgr.connectToSrtla(srtlaHost, srtlaPort)
        }
    }

    /**
     * Stop the Moblink WebSocket server and disconnect all relays.
     * The SRTLA proxy is unaffected.
     */
    fun stopMoblink() {
        val mgr = moblinkManager ?: return
        Log.i(TAG, "Stopping Moblink server")
        mgr.stop()
        moblinkManager = null
        relayMap.clear()
        publishRelays()
    }

    // -------------------------------------------------------------------------
    // Phase 2: SRTLA proxy lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the SRTLA proxy.
     *
     * Suspends until the native library has been launched and the listen port is
     * bound (~300 ms). Idempotent: does nothing if [isRunning] is already true.
     *
     * If the Moblink server is active, tunnels are activated automatically for all
     * waiting relays.
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

        // Save destination for Moblink tunnel activation
        srtlaHost = receiverHost
        srtlaPort = receiverPort.toIntOrNull() ?: 0

        // Activate Moblink tunnels if server is running
        val mgr = moblinkManager
        if (mgr != null && isRunning && srtlaPort != 0) {
            Log.i(TAG, "Activating Moblink relay tunnels → $receiverHost:$receiverPort")
            mgr.connectToSrtla(srtlaHost, srtlaPort)
        }
    }

    /**
     * Stop the SRTLA proxy and release all resources.
     * Moblink relays are parked (destination reset) but stay WebSocket-connected.
     */
    fun stop() {
        Log.i(TAG, "Stopping SRTLA")

        // Park Moblink relays before stopping SRTLA so tunnel tracking is still valid
        val mgr = moblinkManager
        if (mgr != null) {
            Log.i(TAG, "Parking Moblink relays — they will wait for next stream")
            mgr.connectToSrtla("", 0)
        }

        sender?.stop()
        sender = null
        srtlaHost = ""
        srtlaPort = 0
    }

    // -------------------------------------------------------------------------
    // Internal Moblink listener
    // -------------------------------------------------------------------------

    /**
     * Creates the [MoblinkManager.Listener] that wires relay events to:
     * 1. [SrtlaSender] — register/deregister relay sockets for bonding
     * 2. [_relays] — update observable relay status for UI
     */
    private fun makeMoblinkListener() = object : MoblinkManager.Listener() {

        override fun onRelayConnected(relayId: String, name: String) {
            Log.i(TAG, "Moblink relay connected: '$name'")
            relayMap[relayId] = RelayInfo(relayId, name, null, null, tunnelActive = false)
            publishRelays()
        }

        override fun onRelayDisconnected(relayId: String) {
            Log.i(TAG, "Moblink relay disconnected: $relayId")
            relayMap.remove(relayId)
            publishRelays()
        }

        override fun onRelayTunnelReady(relayId: String, name: String, host: String, port: Int) {
            Log.i(TAG, "Moblink relay tunnel ready: '$name' @ $host:$port")
            // Register with native SRTLA bonding layer
            sender?.addMoblinkRelay(relayId, host, port)
            // Update UI state
            val existing = relayMap[relayId]
            relayMap[relayId] = (existing ?: RelayInfo(relayId, name, null, null, false))
                .copy(tunnelActive = true)
            publishRelays()
        }

        override fun onRelayTunnelClosed(relayId: String, host: String, port: Int) {
            Log.i(TAG, "Moblink relay tunnel closed: $relayId @ $host:$port")
            // Deregister from native SRTLA bonding layer
            sender?.removeMoblinkRelay(relayId)
            // Update UI state — relay stays connected, just no tunnel
            val existing = relayMap[relayId]
            if (existing != null) {
                relayMap[relayId] = existing.copy(tunnelActive = false)
                publishRelays()
            }
        }

        override fun onRelayStatus(
            relayId: String,
            name: String,
            batteryPercentage: Int?,
            thermalState: ThermalState?,
        ) {
            val existing = relayMap[relayId]
            relayMap[relayId] = (existing ?: RelayInfo(relayId, name, null, null, false))
                .copy(battery = batteryPercentage, thermal = thermalState)
            publishRelays()
        }

        override fun onLog(message: String) {
            Log.i(TAG, "Moblink: $message")
        }
    }
}
