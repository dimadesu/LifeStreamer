package com.dimadesu.lifestreamer.bitrate

import kotlin.math.max

/**
 * Estimates the transport bitrate based on cumulative bytes sent over time.
 * Uses an Exponential Moving Average (EMA) to smooth out fluctuations, matching
 * Moblin's iOS implementation.
 */
class TransportBitrateEstimator {
    private var previousByteSentTotal: Long = -1L
    private var previousTransportUpdateTimeNs: Long = -1L
    
    var bitrateBps: Long = 0
        private set

    fun update(byteSentTotal: Long): Boolean {
        var updated = false
        val nowNs = System.nanoTime()
        if (previousByteSentTotal >= 0 && previousTransportUpdateTimeNs > 0) {
            val deltaBytes = max(0L, byteSentTotal - previousByteSentTotal)
            val deltaMs = (nowNs - previousTransportUpdateTimeNs) / 1_000_000.0
            if (deltaMs > 0.0) {
                val deltaBitsPerSecond = (8.0 * deltaBytes) * (1000.0 / deltaMs)
                // In iOS Moblin, updateSrtTransportBitrate() is called exactly once per second
                // with EMA weights of 0.7 / 0.3. Since this runs at a variable interval,
                // we mathematically scale the 1-second retention factor (0.7) to match the elapsed time.
                val retention = Math.pow(0.7, deltaMs / 1000.0)
                bitrateBps = (bitrateBps * retention + deltaBitsPerSecond * (1.0 - retention)).toLong()
                updated = true
            }
        }
        previousByteSentTotal = byteSentTotal
        previousTransportUpdateTimeNs = nowNs
        return updated
    }
}
