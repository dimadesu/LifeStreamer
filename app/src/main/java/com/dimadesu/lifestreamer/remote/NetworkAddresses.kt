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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddresses {
    private const val TAG = "NetworkAddresses"

    /**
     * The address a laptop on the same network can actually reach.
     *
     * Deliberately **not** `ConnectivityManager.activeNetwork`: while streaming over mobile data
     * or a bonded SRTLA link the active network is the cellular one, so that path returns a
     * carrier address nothing on the LAN can reach. Wi-Fi and Ethernet are asked for by
     * transport instead.
     *
     * Needs no permission beyond the ACCESS_NETWORK_STATE already in the manifest. In particular
     * do not add ACCESS_WIFI_STATE: it is only needed for the deprecated
     * `WifiManager.connectionInfo`, which returns 0 for non-system apps on modern Android.
     */
    fun localIPv4(context: Context): String? {
        runCatching {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            @Suppress("DEPRECATION")
            manager.allNetworks.forEach { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
                val isLocal =
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isLocal) {
                    return@forEach
                }
                manager.getLinkProperties(network)?.linkAddresses
                    ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                    ?.let { return it.address.hostAddress }
            }
        }.onFailure { Log.w(TAG, "Could not read link properties: ${it.message}") }

        // Covers a Wi-Fi hotspot or USB tethering, where there is no "network" to ask about.
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
