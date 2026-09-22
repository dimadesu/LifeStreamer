/*
 * Copyright (C) 2026 dimadesu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.remote

import android.content.Context
import android.util.Log
import com.dimadesu.lifestreamer.composition.CompositionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the one remote control server, mirroring how SrtlaManager owns Moblink.
 *
 * A Kotlin `object` outliving the service is exactly how a singleton becomes a leak, so [stop]
 * drops every reference it holds.
 */
object RemoteControlManager {
    private const val TAG = "RemoteControlManager"

    @Volatile
    private var server: RemoteControlServer? = null

    @Volatile
    private var currentPort: Int = 0

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = server != null

    /**
     * Emits whenever the server comes up or goes down, so the settings screen can redraw the
     * address and PIN without the operator having to leave and re-enter it. Starting is
     * asynchronous (preference -> DataStore -> service), so a one-shot read after the switch
     * flips would still see the old state.
     */
    private val _stateFlow = MutableStateFlow(false)
    val stateFlow: StateFlow<Boolean> = _stateFlow.asStateFlow()

    /**
     * The address to show the operator, or null when the server is not up.
     */
    fun url(context: Context): String? {
        val port = currentPort
        if (server == null || port == 0) {
            return null
        }
        val ip = NetworkAddresses.localIPv4(context) ?: return null
        return "http://$ip:$port"
    }

    /**
     * The address with the PIN in the **fragment**.
     *
     * A fragment is never sent to the server, so the PIN stays out of access logs and out of any
     * Referer; the page reads `location.hash`, authenticates and clears it. A query parameter
     * would put it in the request line for free.
     */
    fun urlWithPin(context: Context, pin: String): String? =
        url(context)?.let { "$it/#$pin" }

    fun start(
        context: Context,
        controller: CompositionController,
        port: Int,
        pin: String,
        hooks: RemoteControlServer.Hooks
    ) {
        if (server != null && currentPort == port) {
            server?.auth?.pin = pin
            return
        }
        stop()

        val instance = RemoteControlServer(context.applicationContext, port, controller, hooks)
        instance.auth.pin = pin
        lastError = null

        try {
            instance.start()
            server = instance
            currentPort = port
            _stateFlow.value = true
            Log.i(TAG, "Remote control started on port $port")
        } catch (t: Throwable) {
            lastError = t.message ?: "Could not start on port $port"
            Log.e(TAG, "Remote control failed to start: ${t.message}", t)
            runCatching { instance.stop() }
            _stateFlow.value = false
        }
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        currentPort = 0
        _stateFlow.value = false
    }

    /**
     * Pushes the current state to every connected page, skipping the send when nothing changed.
     */
    fun broadcastState() {
        server?.broadcastState()
    }

    fun broadcastMessage(text: String) {
        server?.broadcastMessage(text)
    }
}
