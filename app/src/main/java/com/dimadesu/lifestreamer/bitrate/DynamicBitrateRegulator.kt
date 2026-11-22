package com.dimadesu.lifestreamer.bitrate

import android.util.Log
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.regulator.IBitrateRegulator

/**
 * Pure-Kotlin representation of a video bitrate [lower, upper] range in bits/s.
 * Decouples regulator range updates and unit tests from Android's `android.util.Range`.
 */
data class BitrateRange(
    val lower: Int,
    val upper: Int
) {
    init {
        require(lower <= upper) { "lower ($lower) must be <= upper ($upper)" }
    }

    fun coerceIn(value: Int): Int = value.coerceIn(lower, upper)

    companion object {
        fun from(config: BitrateRegulatorConfig): BitrateRange =
            BitrateRange(config.videoBitrateRange.lower, config.videoBitrateRange.upper)
    }
}

/**
 * Warm state transferred between SRT bitrate regulators when switching algorithms
 * mid-stream (e.g., Moblin SrtFight <-> BelaBox) so the incoming regulator does not
 * drop to 1 Mbps or retrain transport/RTT/PIF baselines from scratch.
 */
data class SrtRegulatorSnapshot(
    val currentBitrate: Long,
    val transportSnapshot: TransportBitrateEstimator.Snapshot,
    val avgRtt: Double,
    val avgPif: Double
)

/**
 * Extension of StreamPack's [IBitrateRegulator] that supports live video bitrate range updates
 * without recreating the regulator or losing trained EMA state.
 */
interface DynamicBitrateRegulator : IBitrateRegulator {
    fun updateVideoBitrateRange(range: BitrateRange)
}

/**
 * An SRT [DynamicBitrateRegulator] that can export its warm state for handoff to another
 * SRT regulator implementation.
 */
interface SnapshotableSrtRegulator : DynamicBitrateRegulator {
    fun exportSnapshot(): SrtRegulatorSnapshot
}

/**
 * JVM-test-safe wrapper around [Log] for the bitrate regulator package so regulators and
 * controllers can run on a plain JVM without android.jar mocks.
 */
internal object BitrateRegulatorLog {
    fun d(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: RuntimeException) {
            // Android Log is not mocked in local JVM unit tests.
        }
    }

    fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: RuntimeException) {
            // Android Log is not mocked in local JVM unit tests.
        }
    }

    fun w(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: RuntimeException) {
            // Android Log is not mocked in local JVM unit tests.
        }
    }
}
