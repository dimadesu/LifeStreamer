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

import android.os.SystemClock
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
 * What the thermal policy is allowed to touch.
 *
 * Implemented by the UI, which may not exist — with the phone mounted and the screen off there is
 * no preview to reduce, and that is already the state the policy wants. So a null actuator is a
 * normal condition, not an error.
 */
interface ThermalActuator {
    fun setPreviewEnabled(enabled: Boolean)
    fun setPreviewFpsCap(maxFps: Int?)
    fun setPreviewShortEdge(shortEdge: Int?)

    /** VU meter, SRTLA stats, debug overlays — anything redrawing for information only. */
    fun setAuxiliaryUiEnabled(enabled: Boolean)

    /** Puts the operator's own preview settings back. */
    fun restoreOperatorPreviewSettings()

    fun notifyOperator(message: String)
}

/**
 * Turns thermal readings into actions, conservatively.
 *
 * The rule that governs everything here: **nothing it does automatically may touch what goes out**.
 * No encoder reconfiguration, no bitrate, and above all never `stopStream()` — the OS throttles
 * CPU and GPU by itself and the stream degrades, whereas ending it is the one outcome an operator
 * cannot recover from in the field.
 */
class ThermalPolicy(
    private val scope: CoroutineScope,
    private val monitor: ThermalMonitor,
    private val isEnabled: () -> Boolean,
    private val isMountedMode: () -> Boolean
) {
    var actuator: ThermalActuator? = null
        set(value) {
            field = value
            // A UI that appears mid-throttle has to be brought to the current step.
            if (value != null && appliedLevel != ThermalLevel.NONE) {
                applyActions(appliedLevel)
            }
        }

    private val _appliedActionsFlow = MutableStateFlow<List<String>>(emptyList())

    /** Human-readable list of what the policy has done, for the UI and the remote page. */
    val appliedActionsFlow: StateFlow<List<String>> = _appliedActionsFlow.asStateFlow()

    private var job: Job? = null

    private var appliedLevel = ThermalLevel.NONE
    private var candidateLevel = ThermalLevel.NONE
    private var candidateSinceMs = 0L
    private var manualOverrideUntilMs = 0L

    fun start() {
        job?.cancel()
        candidateLevel = monitor.stateFlow.value.level
        candidateSinceMs = SystemClock.elapsedRealtime()
        job = scope.launch {
            while (isActive) {
                evaluate()
                delay(TICK_MS)
            }
        }
        Log.i(TAG, "Thermal policy started")
    }

    fun stop() {
        job?.cancel()
        job = null
        if (appliedLevel != ThermalLevel.NONE) {
            relaxToNone()
        }
        Log.i(TAG, "Thermal policy stopped")
    }

    /**
     * The operator overruled the policy — typically by turning the preview back on.
     *
     * Nothing is more infuriating than a button that undoes itself, so the policy keeps its hands
     * off for a while afterwards.
     */
    fun onManualOverride() {
        manualOverrideUntilMs = SystemClock.elapsedRealtime() + MANUAL_OVERRIDE_MS
        Log.i(TAG, "Manual override: the policy will not act for ${MANUAL_OVERRIDE_MS / 1000}s")
    }

    private fun evaluate() {
        if (!isEnabled()) {
            if (appliedLevel != ThermalLevel.NONE) {
                relaxToNone()
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < manualOverrideUntilMs) {
            return
        }

        val level = monitor.stateFlow.value.level
        if (level != candidateLevel) {
            candidateLevel = level
            candidateSinceMs = now
            return
        }

        val dwellMs = now - candidateSinceMs

        when {
            // Heat returns in seconds, so escalation is quick.
            level.ordinal > appliedLevel.ordinal && dwellMs >= ESCALATE_DWELL_MS -> {
                val next = ThermalLevel.entries[appliedLevel.ordinal + 1]
                applyLevel(next)
            }

            // Cooling takes minutes; relaxing early is exactly what oscillates. One step at a
            // time, and never faster than the relax dwell.
            level.ordinal < appliedLevel.ordinal && dwellMs >= RELAX_DWELL_MS -> {
                val next = ThermalLevel.entries[appliedLevel.ordinal - 1]
                applyLevel(next)
                candidateSinceMs = now
            }
        }
    }

    private fun applyLevel(level: ThermalLevel) {
        if (level == appliedLevel) {
            return
        }
        Log.i(TAG, "Thermal step ${appliedLevel} -> $level")
        appliedLevel = level
        applyActions(level)
    }

    private fun applyActions(level: ThermalLevel) {
        val target = effectiveLevel(level)
        val actions = mutableListOf<String>()
        val act = actuator

        when (target) {
            ThermalLevel.NONE, ThermalLevel.LIGHT -> {
                act?.restoreOperatorPreviewSettings()
                act?.setAuxiliaryUiEnabled(true)
            }

            ThermalLevel.MODERATE -> {
                act?.setPreviewFpsCap(MODERATE_PREVIEW_FPS)
                act?.setPreviewShortEdge(MODERATE_PREVIEW_SHORT_EDGE)
                actions += "Preview reduced to ${MODERATE_PREVIEW_SHORT_EDGE}p at $MODERATE_PREVIEW_FPS fps"
                act?.notifyOperator("Phone is warm - preview reduced")
            }

            ThermalLevel.SEVERE -> {
                act?.setPreviewEnabled(false)
                actions += "Preview turned off"
                act?.notifyOperator("Phone is hot - preview turned off")
            }

            ThermalLevel.CRITICAL -> {
                act?.setPreviewEnabled(false)
                act?.setAuxiliaryUiEnabled(false)
                actions += "Preview turned off"
                actions += "On-screen meters stopped"
                act?.notifyOperator("Phone is very hot - the stream continues, screen updates stopped")
            }
        }

        _appliedActionsFlow.value = actions

        if (act == null && target != ThermalLevel.NONE) {
            // Expected with the screen off: the preview is already gone, which is the goal.
            Log.i(TAG, "No UI attached; thermal actions for $target are already satisfied")
        }
    }

    /**
     * In mounted mode the cheap measures are already in force — the preview is off before the
     * phone even gets warm — so there is nothing left to give up at MODERATE. The ladder starts
     * one step higher rather than pretending to act.
     */
    private fun effectiveLevel(level: ThermalLevel): ThermalLevel =
        if (isMountedMode() && level == ThermalLevel.MODERATE) ThermalLevel.SEVERE else level

    private fun relaxToNone() {
        appliedLevel = ThermalLevel.NONE
        candidateLevel = ThermalLevel.NONE
        applyActions(ThermalLevel.NONE)
    }

    companion object {
        private const val TAG = "ThermalPolicy"

        private const val TICK_MS = 5_000L
        private const val ESCALATE_DWELL_MS = 10_000L
        private const val RELAX_DWELL_MS = 120_000L
        private const val MANUAL_OVERRIDE_MS = 5 * 60_000L

        private const val MODERATE_PREVIEW_FPS = 15
        private const val MODERATE_PREVIEW_SHORT_EDGE = 540
    }
}
