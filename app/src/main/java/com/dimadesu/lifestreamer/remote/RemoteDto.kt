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
        val layers: List<LayerDto>,
        val cameras: List<CameraDto>,
        val thermal: ThermalDto?
    )

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
    data class ZoomDto(val min: Float, val max: Float, val ratio: Float)

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
