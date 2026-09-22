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

import androidx.annotation.Keep

/**
 * The wire format of the remote control.
 *
 * Every class here is [Keep]ed and covered by a proguard rule. R8 renames fields in release
 * builds, which would quietly turn the JSON into `{"a":1,"b":2}` and break the page with no
 * error anywhere — the same trap `CompositionStore` already documents.
 */
object RemoteDto {

    @Keep
    data class StateDto(
        val revision: Long,
        val composition: Boolean,
        val streaming: Boolean,
        val muted: Boolean,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val presets: List<PresetDto>,
        /** Which preset the layout currently matches, or null after a manual drag. */
        val activePresetId: String?,
        val layers: List<LayerDto>,
        val cameras: List<CameraDto>,
        val thermal: ThermalDto?,
        val power: PowerDto,
        val stream: StreamDto
    )

    /**
     * The stream as the operator needs to see it from outside: not just live or not, but starting,
     * connecting, reconnecting and why it failed -- and whether it can be started right now.
     *
     * Bitrate and fps are deliberately not here: they change every two seconds, and in the state
     * they would defeat the "only push when something changed" rule and rebuild the page mid-drag.
     * They travel in a separate `stats` event.
     */
    @Keep
    data class StreamDto(
        /** NOT_STREAMING, STARTING, CONNECTING, STREAMING or ERROR. */
        val status: String,
        val reconnecting: Boolean,
        val reconnectionMessage: String?,
        /** Epoch ms when the stream went live; the page derives the uptime from it. */
        val startedAtMs: Long?,
        val lastError: String?,
        val canStart: Boolean,
        val startBlockedReason: String?
    )

    @Keep
    data class StatsDto(val bitrateKbps: Int?, val fps: Float?)

    @Keep
    data class PresetDto(val id: String, val name: String)

    @Keep
    data class LayerDto(
        val id: String,
        val label: String,
        val z: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val visible: Boolean,
        val primary: Boolean,
        val cameraId: String?,
        val zoom: ZoomDto?
    )

    @Keep
    data class ZoomDto(
        val min: Float,
        val max: Float,
        val ratio: Float,
        /**
         * The camera this range belongs to, so the page can rebuild a slider whose bounds came
         * from a camera that is no longer behind the layer.
         */
        val cameraId: String?
    )

    /**
     * What is actually applied to the preview right now, so the page can show it instead of
     * offering four buttons that give no hint of the current state.
     */
    @Keep
    data class PowerDto(
        val previewEnabled: Boolean,
        val previewShortEdge: Int?,
        val previewFpsCap: Int?,
        /** "operator" or "thermal": who put the preview in this state. */
        val appliedBy: String
    )

    @Keep
    data class CameraDto(
        val id: String,
        val name: String,
        val facing: String,
        val usedByLayerId: String?
    )

    @Keep
    data class ThermalDto(
        val level: String,
        val headroom: Float?,
        val powerSaveMode: Boolean,
        val supported: Boolean,
        val appliedActions: List<String>
    )

    // Requests

    @Keep
    data class AuthRequest(val pin: String? = null)

    @Keep
    data class AuthResponse(val token: String, val expiresInSec: Long)

    @Keep
    data class ErrorResponse(val error: String, val retryAfterMs: Long? = null)

    @Keep
    data class PresetRequest(val presetId: String? = null)

    @Keep
    data class LayerRectRequest(
        val layerId: String? = null,
        val left: Float? = null,
        val top: Float? = null,
        val right: Float? = null,
        val bottom: Float? = null
    )

    @Keep
    data class LayerRequest(val layerId: String? = null)

    @Keep
    data class LayerVisibleRequest(val layerId: String? = null, val visible: Boolean? = null)

    @Keep
    data class LayerCameraRequest(val layerId: String? = null, val cameraId: String? = null)

    @Keep
    data class ZoomRequest(
        val layerId: String? = null,
        val ratio: Float? = null,
        val factor: Float? = null
    )

    @Keep
    data class MuteRequest(val muted: Boolean? = null)

    @Keep
    data class PreviewRequest(val enabled: Boolean? = null)

    @Keep
    data class PreviewResolutionRequest(val shortEdge: Int? = null)

    @Keep
    data class PreviewFpsRequest(val maxFps: Int? = null)

    @Keep
    data class OkResponse(val ok: Boolean, val error: String? = null)

    @Keep
    data class MessageDto(val text: String)
}
