package com.dimadesu.lifestreamer.srtla

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages the embedded SRTLA native proxy within LifeStreamer.
 *
 * Ported from bond-bunny's NativeSrtlaService. Runs entirely in-process:
 * no separate app, no inter-process communication, no fixed startup delay.
 *
 * Registers ConnectivityManager callbacks for Wi-Fi, Cellular and Ethernet so the
 * native SRTLA library can bond multiple links simultaneously.
 */
object SrtlaManager {

    private const val TAG = "SrtlaManager"

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    private var connectivityManager: ConnectivityManager? = null
    private var filesDir: File? = null

    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var ethernetCallback: ConnectivityManager.NetworkCallback? = null

    /** virtualIP → native socket FD */
    private val virtualConnections = ConcurrentHashMap<String, Int>()
    /** "$networkType:$network" → realIP — avoids redundant socket recreation */
    private val networkState = ConcurrentHashMap<String, String>()

    private var firstConnectionLatch = CountDownLatch(1)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** True when the native SRTLA thread is running. */
    val isRunning: Boolean get() = NativeSrtlaJni.isRunningSrtlaNative()

    /**
     * Start the SRTLA proxy.
     *
     * Suspends until the native library has been launched and the listen port is
     * bound (~300 ms). Idempotent: does nothing if [isRunning] is already true.
     */
    suspend fun start(context: Context, receiverHost: String, receiverPort: String, listenPort: String) {
        if (isRunning) {
            Log.i(TAG, "Already running — skipping start")
            return
        }

        Log.i(TAG, "Starting SRTLA: $receiverHost:$receiverPort, listen on $listenPort")

        withContext(Dispatchers.IO) {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
            filesDir = context.filesDir

            // Acquire wake lock + Wi-Fi lock
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LifeStreamer::SrtlaWakeLock")
            wakeLock?.acquire()

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LifeStreamer::SrtlaWifiLock")
            wifiLock?.acquire()

            // Reset per-session state
            firstConnectionLatch = CountDownLatch(1)
            virtualConnections.clear()
            networkState.clear()

            // Register network callbacks (fires immediately for already-connected networks on
            // some devices; on others we need to poll existing networks explicitly)
            setupDedicatedNetworkCallbacks()
            if (virtualConnections.isEmpty()) {
                recreateNetworkSockets()
            }

            // Wait up to 2 s for at least one network socket to be ready
            waitForNetworkConnections()

            // Write the virtual-IPs file that srtla_send.c reads on startup
            val ipsFile = try {
                createVirtualIpsFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create virtual IPs file — aborting SRTLA start", e)
                releaseLocks()
                return@withContext
            }

            // Start the native SRTLA thread (non-blocking: spawns a pthread internally)
            Log.i(TAG, "Launching native SRTLA (ipsFile=${ipsFile.absolutePath})")
            val result = NativeSrtlaJni.startSrtlaNative(
                listenPort,
                receiverHost,
                receiverPort,
                ipsFile.absolutePath
            )

            if (result != 0) {
                Log.e(TAG, "NativeSrtlaJni.startSrtlaNative() returned $result")
            }
        }

        // Give the native pthread time to bind the listen port before the SRT stack
        // tries to connect to 127.0.0.1:<listenPort>.  In-process, ~200 ms is plenty.
        delay(300)
        Log.i(TAG, "SRTLA proxy ready (isRunning=${isRunning})")
    }

    /** Stop the SRTLA proxy and release all resources. */
    fun stop() {
        Log.i(TAG, "Stopping SRTLA")
        NativeSrtlaJni.stopSrtlaNative()
        teardownDedicatedNetworkCallbacks()
        virtualConnections.clear()
        networkState.clear()
        releaseLocks()
        connectivityManager = null
        filesDir = null
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun waitForNetworkConnections() {
        Log.i(TAG, "Waiting for network connections...")
        try {
            val ready = firstConnectionLatch.await(2, TimeUnit.SECONDS)
            if (ready) {
                Log.i(TAG, "Ready: ${virtualConnections.size} connection(s) available")
            } else {
                Log.w(TAG, "Timeout — starting with ${virtualConnections.size} connection(s)")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Network wait interrupted", e)
        }
    }

    @Throws(Exception::class)
    private fun createVirtualIpsFile(): File {
        val dir = filesDir ?: error("filesDir not initialised")
        val file = File(dir, "srtla_virtual_ips.txt")
        if (file.exists()) file.delete()

        FileWriter(file, false).use { w ->
            virtualConnections.keys.forEach { vip ->
                w.write("$vip\n")
                Log.i(TAG, "  virtual IP: $vip")
            }
            w.flush()
        }
        Log.i(TAG, "IPs file written: ${file.absolutePath} (${virtualConnections.size} entries)")
        return file
    }

    private fun setupDedicatedNetworkCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        cellularCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_CELLULAR, "CELLULAR")
        wifiCallback     = registerNetworkCallback(NetworkCapabilities.TRANSPORT_WIFI,     "WIFI")
        ethernetCallback = registerNetworkCallback(NetworkCapabilities.TRANSPORT_ETHERNET, "ETHERNET")
    }

    private fun teardownDedicatedNetworkCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = connectivityManager ?: return
        try {
            cellularCallback?.let { cm.unregisterNetworkCallback(it) }
            wifiCallback    ?.let { cm.unregisterNetworkCallback(it) }
            ethernetCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callbacks", e)
        }
        cellularCallback = null
        wifiCallback     = null
        ethernetCallback = null
    }

    /** Recreate sockets for networks that are already connected when we start. */
    private fun recreateNetworkSockets() {
        val cm = connectivityManager ?: return
        Log.i(TAG, "Recreating sockets for already-connected networks")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    handleDedicatedNetworkAvailable(network, "CELLULAR")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    handleDedicatedNetworkAvailable(network, "WIFI")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    handleDedicatedNetworkAvailable(network, "ETHERNET")
            }
        }
    }

    private fun registerNetworkCallback(
        transportType: Int,
        networkTypeName: String,
    ): ConnectivityManager.NetworkCallback {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    Log.i(TAG, "DEDICATED: $networkTypeName available: $network")
                    handleDedicatedNetworkAvailable(network, networkTypeName)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in $networkTypeName onAvailable callback", e)
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "DEDICATED: $networkTypeName lost: $network")
                handleDedicatedNetworkLost(network, networkTypeName)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                try {
                    val stateKey  = "$networkTypeName:$network"
                    val currentIP = getNetworkIP(network)
                    val previousIP = networkState[stateKey]
                    if (currentIP != null && currentIP != previousIP) {
                        Log.i(TAG, "DEDICATED: $networkTypeName IP changed $previousIP→$currentIP")
                        handleDedicatedNetworkAvailable(network, networkTypeName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in $networkTypeName onCapabilitiesChanged", e)
                }
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(transportType)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager?.requestNetwork(request, callback)
            Log.i(TAG, "Registered $networkTypeName network callback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register $networkTypeName callback", e)
        }
        return callback
    }

    private fun getVirtualIPForNetworkType(networkType: String): String? = when (networkType) {
        "WIFI"     -> "10.0.1.1"
        "CELLULAR" -> "10.0.2.1"
        "ETHERNET" -> "10.0.3.1"
        else       -> null
    }

    private fun getNetworkTypeId(networkType: String): Int = when (networkType) {
        "WIFI"     -> 1
        "CELLULAR" -> 2
        "ETHERNET" -> 3
        else       -> 0
    }

    @Synchronized
    private fun handleDedicatedNetworkAvailable(network: Network, networkType: String) {
        try {
            val realIP = getNetworkIP(network) ?: run {
                Log.w(TAG, "DEDICATED: Cannot get IP for $networkType network $network")
                return
            }

            val virtualIP    = getVirtualIPForNetworkType(networkType) ?: return
            val networkTypeId = getNetworkTypeId(networkType)

            val stateKey   = "$networkType:$network"
            val previousIP = networkState[stateKey]

            if (realIP == previousIP && virtualConnections.containsKey(virtualIP)) {
                Log.d(TAG, "DEDICATED: $networkType socket unchanged, skipping")
                return
            }

            if (virtualConnections.containsKey(virtualIP)) {
                Log.i(TAG, "DEDICATED: Re-creating socket for $networkType (network changed)")
                virtualConnections.remove(virtualIP)
            }

            networkState[stateKey] = realIP
            val socketFd = createNetworkSocket(network)
            if (socketFd >= 0) {
                virtualConnections[virtualIP] = socketFd
                NativeSrtlaJni.setNetworkSocket(virtualIP, realIP, networkTypeId, socketFd)
                Log.i(TAG, "DEDICATED: $networkType ready: $virtualIP→$realIP (fd=$socketFd)")

                // Unblock waitForNetworkConnections()
                firstConnectionLatch.countDown()

                // If already running, refresh the IPs file so the native code picks it up
                if (isRunning) {
                    try {
                        createVirtualIpsFile()
                        NativeSrtlaJni.notifyNetworkChange()
                        Log.i(TAG, "DEDICATED: Notified native SRTLA of $networkType change")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error refreshing IPs file on network change", e)
                    }
                }
            } else {
                Log.e(TAG, "DEDICATED: Failed to create socket for $networkType (fd=$socketFd)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $networkType network available", e)
        }
    }

    @Synchronized
    private fun handleDedicatedNetworkLost(network: Network, networkType: String) {
        try {
            val virtualIP = getVirtualIPForNetworkType(networkType) ?: return
            if (virtualConnections.containsKey(virtualIP)) {
                Log.i(TAG, "DEDICATED: Removing $networkType connection: $virtualIP")
                virtualConnections.remove(virtualIP)
                networkState.remove("$networkType:$network")

                if (isRunning) {
                    try {
                        createVirtualIpsFile()
                        NativeSrtlaJni.notifyNetworkChange()
                        Log.i(TAG, "DEDICATED: Notified native SRTLA of $networkType loss")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error refreshing IPs file on network loss", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $networkType network lost", e)
        }
    }

    /**
     * Creates a native UDP socket and binds it to [network] so SRTLA can use it
     * for outbound packets on that specific interface.
     * Uses reflection to pass the raw FD to [Network.bindSocket] and then
     * detaches from fdsan so native code owns the lifetime.
     */
    private fun createNetworkSocket(network: Network): Int {
        val socketFd = NativeSrtlaJni.createUdpSocketNative()
        if (socketFd < 0) {
            Log.e(TAG, "createUdpSocketNative() failed")
            return -1
        }

        return try {
            val fd = java.io.FileDescriptor()
            val fdField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            fdField.isAccessible = true
            fdField.setInt(fd, socketFd)

            network.bindSocket(fd)

            // Detach FD from fdsan — ownership transfers to native code
            try {
                val setIntMethod = java.io.FileDescriptor::class.java
                    .getDeclaredMethod("setInt\$", Int::class.java)
                setIntMethod.isAccessible = true
                setIntMethod.invoke(fd, -1)
                Log.i(TAG, "Detached FD $socketFd from fdsan")
            } catch (e: Exception) {
                Log.w(TAG, "Could not detach FD $socketFd from fdsan (may cause fdsan crash): ${e.message}")
            }

            socketFd
        } catch (e: Exception) {
            Log.w(TAG, "createNetworkSocket failed for network $network, closing FD $socketFd", e)
            NativeSrtlaJni.closeSocketNative(socketFd)
            -1
        }
    }

    private fun getNetworkIP(network: Network): String? {
        return try {
            val cm = connectivityManager ?: return null
            val linkProperties = cm.getLinkProperties(network)
            if (linkProperties != null) {
                for (linkAddress in linkProperties.linkAddresses) {
                    val addr = linkAddress.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            // Fallback: bind a test socket to get the source address
            Log.i(TAG, "LinkProperties had no IPv4 for $network — trying socket fallback")
            getNetworkIPFromSocket(network)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network IP for $network", e)
            null
        }
    }

    private fun getNetworkIPFromSocket(network: Network): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            network.bindSocket(socket)
            socket.connect(InetAddress.getByName("8.8.8.8"), 53)
            val local = socket.localAddress
            if (local is Inet4Address && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
                local.hostAddress
            } else null
        } catch (e: Exception) {
            if (e.message?.contains("EPERM") == true) {
                // Samsung blocks DatagramSocket.bindSocket on cellular — use placeholder
                Log.i(TAG, "EPERM on cellular bindSocket — using placeholder IP")
                "10.64.64.64"
            } else {
                Log.w(TAG, "getNetworkIPFromSocket failed: ${e.message}")
                null
            }
        } finally {
            socket?.let { if (!it.isClosed) it.close() }
        }
    }
}
