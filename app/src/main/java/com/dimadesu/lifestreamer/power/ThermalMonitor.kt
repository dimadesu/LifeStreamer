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
package com.dimadesu.lifestreamer.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How hot the device says it is.
 *
 * Mirrors `PowerManager.THERMAL_STATUS_*` but collapses the three top levels, because nothing the
 * app can do differs between them.
 */
enum class ThermalLevel {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL;

    companion object {
        fun fromStatus(status: Int): ThermalLevel = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> NONE
            PowerManager.THERMAL_STATUS_LIGHT -> LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> SEVERE
            else -> CRITICAL
        }
    }
}

data class ThermalState(
    val level: ThermalLevel = ThermalLevel.NONE,

    /**
     * `PowerManager.getThermalHeadroom(60)`, or `NaN` when unavailable.
     *
     * This is the **predictive** number: it reaches 1.0 *before* throttling starts, which is what
     * gives an operator time to react. The status above only changes once throttling is already
     * happening.
     */
    val headroom: Float = Float.NaN,

    val isPowerSaveMode: Boolean = false,

    /**
     * False on devices too old to report thermal status at all, so the UI can say "unknown"
     * instead of implying everything is fine.
     */
    val isSupported: Boolean = false
)

/**
 * Watches the device's thermal status.
 *
 * Lives in the foreground service, not in a ViewModel: the stream outlives the UI, and the window
 * that matters most is precisely the one where the UI is gone — phone mounted on a windscreen in
 * the sun with the screen off.
 */
class ThermalMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _stateFlow = MutableStateFlow(ThermalState())
    val stateFlow: StateFlow<ThermalState> = _stateFlow.asStateFlow()

    private var headroomJob: Job? = null
    private var isStarted = false

    private val thermalListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { status ->
                val level = ThermalLevel.fromStatus(status)
                Log.i(TAG, "Thermal status changed: $level (raw $status)")
                _stateFlow.value = _stateFlow.value.copy(level = level)
            }
        } else {
            null
        }

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val saving = powerManager.isPowerSaveMode
            Log.i(TAG, "Power save mode is now $saving")
            _stateFlow.value = _stateFlow.value.copy(isPowerSaveMode = saving)
        }
    }

    fun start() {
        if (isStarted) {
            return
        }
        isStarted = true

        context.registerReceiver(
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Reading /sys/class/thermal is not an option: the paths are vendor specific and
            // unreadable without permission on modern Android.
            Log.i(TAG, "Thermal status needs Android 10; this device is ${Build.VERSION.SDK_INT}")
            _stateFlow.value = ThermalState(
                isSupported = false,
                isPowerSaveMode = powerManager.isPowerSaveMode
            )
            return
        }

        _stateFlow.value = ThermalState(
            level = ThermalLevel.fromStatus(powerManager.currentThermalStatus),
            isPowerSaveMode = powerManager.isPowerSaveMode,
            isSupported = true
        )

        thermalListener?.let { powerManager.addThermalStatusListener(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startHeadroomPolling()
        }

        Log.i(TAG, "Thermal monitor started at ${_stateFlow.value.level}")
    }

    /**
     * `getThermalHeadroom` is rate limited to roughly once per second and returns `NaN` for the
     * first ~30 s after boot, so it is polled slowly and NaN is kept rather than treated as zero.
     */
    private fun startHeadroomPolling() {
        headroomJob?.cancel()
        headroomJob = scope.launch {
            while (isActive) {
                try {
                    val headroom = powerManager.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)
                    if (headroom != _stateFlow.value.headroom) {
                        _stateFlow.value = _stateFlow.value.copy(headroom = headroom)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not read thermal headroom: ${t.message}")
                }
                delay(HEADROOM_POLL_MS)
            }
        }
    }

    fun stop() {
        if (!isStarted) {
            return
        }
        isStarted = false

        headroomJob?.cancel()
        headroomJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let {
                try {
                    powerManager.removeThermalStatusListener(it)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not remove the thermal listener: ${t.message}")
                }
            }
        }

        try {
            context.unregisterReceiver(powerSaveReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not unregister the power save receiver: ${t.message}")
        }

        Log.i(TAG, "Thermal monitor stopped")
    }

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val HEADROOM_FORECAST_SECONDS = 60
        private const val HEADROOM_POLL_MS = 30_000L
    }
}
