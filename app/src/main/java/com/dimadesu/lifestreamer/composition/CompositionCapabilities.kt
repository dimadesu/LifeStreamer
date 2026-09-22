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
package com.dimadesu.lifestreamer.composition

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size

/**
 * Answers whether this device can actually run two of its own cameras at once.
 *
 * Running two camera2 devices simultaneously is not a given: it needs Android 11, the
 * manufacturer has to advertise the pair, and the guaranteed stream configuration for a
 * concurrent pair is capped well below what a single camera can do. So the answer is a device
 * fact to be read, never an assumption — and when it is no, the UI has to say why rather than
 * quietly hiding the option.
 */
class CompositionCapabilities(private val context: Context) {

    data class Report(
        /**
         * Sets of camera ids that can be open at the same time. Empty means one camera at a time.
         */
        val concurrentCameraIdSets: List<Set<String>>,

        /**
         * The largest resolution each camera of a concurrent pair may use.
         */
        val concurrentCameraMaxSize: Size,

        /**
         * True when the device predates the API that reports concurrent pairs, so the answer is
         * "unknown, assume no" rather than "no".
         */
        val apiTooOld: Boolean
    ) {
        val supportsTwoCameras: Boolean get() = concurrentCameraIdSets.isNotEmpty()

        /**
         * A sentence to show the operator, in their terms.
         */
        fun describe(): String = when {
            apiTooOld ->
                "Two cameras at once needs Android 11 or newer. USB, screen and network layers " +
                        "still work."

            !supportsTwoCameras ->
                "This device cannot run two of its own cameras at the same time. USB, screen and " +
                        "network layers still work."

            else -> {
                val pair = concurrentCameraIdSets.first().joinToString(" + ")
                "This device can run cameras $pair at the same time, up to " +
                        "${concurrentCameraMaxSize.width}x${concurrentCameraMaxSize.height} each."
            }
        }
    }

    private val cached: Report by lazy { probe() }

    fun report(): Report = cached

    /**
     * A camera that can run alongside [primaryCameraId], or `null` when there is none.
     */
    fun secondCameraFor(primaryCameraId: String): String? {
        val set = cached.concurrentCameraIdSets.firstOrNull { it.contains(primaryCameraId) }
            ?: return null
        return set.firstOrNull { it != primaryCameraId }
    }

    /**
     * Whether these two cameras can be open at the same time.
     *
     * Two ids that are not in a concurrent set will fail to open together, so this has to be
     * asked *before* swapping a camera into a layer rather than discovering it as a crash.
     */
    fun canRunTogether(first: String, second: String): Boolean {
        if (first == second) {
            return false
        }
        return cached.concurrentCameraIdSets.any { it.contains(first) && it.contains(second) }
    }

    /**
     * Why a second camera layer is unavailable, or `null` when it is available.
     */
    fun reasonSecondCameraUnavailable(primaryCameraId: String?): String? = when {
        cached.apiTooOld -> "Needs Android 11 or newer"
        !cached.supportsTwoCameras -> "This device cannot run two cameras at once"
        primaryCameraId == null -> null
        secondCameraFor(primaryCameraId) == null ->
            "The current camera cannot be paired with another one"

        else -> null
    }

    private fun probe(): Report {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "Concurrent camera query needs API 30, this device is ${Build.VERSION.SDK_INT}")
            return Report(emptyList(), CONCURRENT_MAX_SIZE, apiTooOld = true)
        }

        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sets = manager.concurrentCameraIds.map { it.toSet() }.filter { it.size >= 2 }
            Log.i(TAG, "Concurrent camera sets: $sets")
            Report(sets, CONCURRENT_MAX_SIZE, apiTooOld = false)
        } catch (t: Throwable) {
            // Some vendors throw here rather than returning an empty set.
            Log.w(TAG, "Could not query concurrent cameras: ${t.message}")
            Report(emptyList(), CONCURRENT_MAX_SIZE, apiTooOld = false)
        }
    }

    companion object {
        private const val TAG = "CompositionCapabilities"

        /**
         * The guaranteed stream configuration for a concurrent camera pair is far smaller than
         * for a single camera; 720p per camera is the level Android guarantees when concurrent
         * operation is supported at all.
         */
        private val CONCURRENT_MAX_SIZE = Size(1280, 720)
    }
}
