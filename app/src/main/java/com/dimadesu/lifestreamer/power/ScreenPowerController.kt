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

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Keeps the screen on but effectively dark, and lowers the clock ceiling.
 *
 * This is the app's answer to a workaround operators reach for anyway: turning on Android's
 * battery saver to slow the CPU down. That does slow the clock, but with the screen off it also
 * lets the system restrict background work and kill services — which on a long stream is how you
 * lose the stream.
 *
 * So instead: **the screen stays on** (no Doze, services untouched) but with the brightness at
 * the floor and nothing redrawing, and the clock ceiling is lowered through the API that exists
 * for exactly that, without any of battery saver's other behaviour.
 */
class ScreenPowerController(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())

    private var isDimmed = false
    private var brightnessBeforeDim = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private val redimRunnable = Runnable {
        if (wantsDim) {
            applyDim(true)
        }
    }

    /**
     * Whether the screen should be dark when the operator is not touching it.
     */
    var wantsDim: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (value) {
                applyDim(true)
            } else {
                cancelRedim()
                applyDim(false)
            }
        }

    /**
     * Lowers the clock ceiling to a level the device can hold indefinitely.
     *
     * Unlike battery saver this has no effect on background work, which is the whole point.
     */
    var isSustainedPerformance: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Log.i(TAG, "Sustained performance needs Android 7")
                return
            }
            try {
                activity.window.setSustainedPerformanceMode(value)
                Log.i(TAG, "Sustained performance mode ${if (value) "on" else "off"}")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not set sustained performance mode: ${t.message}")
            }
        }

    /**
     * Call from `Activity.dispatchTouchEvent`.
     *
     * @return `true` when the touch was used only to light the screen and must **not** reach the
     * UI. On a phone clamped to a windscreen a bump must never land on LIVE or STOP, so the first
     * touch only wakes the screen; the operator touches again to act.
     */
    fun onUserTouch(): Boolean {
        if (!wantsDim) {
            return false
        }

        if (isDimmed) {
            applyDim(false)
            scheduleRedim()
            return true
        }

        // Already lit: keep it lit a while longer, and let the touch through.
        scheduleRedim()
        return false
    }

    fun onPause() {
        cancelRedim()
        applyDim(false)
    }

    private fun applyDim(dim: Boolean) {
        if (isDimmed == dim) {
            return
        }
        try {
            val params = activity.window.attributes
            if (dim) {
                brightnessBeforeDim = params.screenBrightness
                // 0f is the dimmest the platform allows; it does not turn the screen off, which
                // is what keeps the services alive.
                params.screenBrightness = DIM_BRIGHTNESS
            } else {
                params.screenBrightness = brightnessBeforeDim
            }
            activity.window.attributes = params
            isDimmed = dim
            Log.i(TAG, "Screen ${if (dim) "dimmed" else "restored"}")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not change screen brightness: ${t.message}")
        }
    }

    private fun scheduleRedim() {
        cancelRedim()
        handler.postDelayed(redimRunnable, WAKE_DURATION_MS)
    }

    private fun cancelRedim() {
        handler.removeCallbacks(redimRunnable)
    }

    companion object {
        private const val TAG = "ScreenPowerController"

        private const val DIM_BRIGHTNESS = 0.01f
        private const val WAKE_DURATION_MS = 15_000L
    }
}
