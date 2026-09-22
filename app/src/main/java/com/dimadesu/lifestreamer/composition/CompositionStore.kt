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
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerScaleMode

/**
 * Remembers the layout the operator arranged, so reopening the app does not throw it away.
 *
 * Only geometry and the chosen source kind are stored — never live objects such as an
 * ExoPlayer or a MediaProjection token, which are meaningless across a restart.
 */
class CompositionStore(context: Context) {

    /**
     * Flat, explicitly kept data classes rather than the domain types.
     *
     * Two reasons, both of which bite only in release builds: the layer source is a sealed type
     * that Gson cannot round-trip without a type adapter, and R8 renames fields, which would
     * silently corrupt the JSON. The `-keep` rule lives next to the existing Gson block in
     * proguard-rules.pro.
     */
    /**
     * The style fields arrived in schema 2 and are nullable on purpose: a blob saved by schema 1
     * has none of them, Gson leaves them null, and they fall back to what the layer already had.
     */
    @Keep
    internal data class LayerDto(
        val id: String,
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
        val visible: Boolean,
        val scale: String? = null,
        val alpha: Float? = null,
        val mirror: Boolean? = null,
        val rot: Int? = null
    )

    @Keep
    internal data class CompositionDto(
        val v: Int = SCHEMA_VERSION,
        val pipSource: String,
        val layers: List<LayerDto>,
        val bg: Int? = null
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    /** A layer's look, independent of where it sits. Null fields mean "keep what it had". */
    data class LayerStyle(
        val scaleMode: LayerScaleMode?,
        val alpha: Float?,
        val mirror: Boolean?,
        val rotationDegrees: Int?
    )

    data class SavedComposition(
        val pipSourceName: String,
        val rects: Map<String, LayerRect>,
        val hidden: Set<String>,
        val styles: Map<String, LayerStyle> = emptyMap(),
        val backgroundColor: Int? = null
    )

    fun save(pipSourceName: String, layout: CompositionLayout) {
        try {
            val dto = CompositionDto(
                pipSource = pipSourceName,
                layers = layout.layers.map { layer ->
                    val rect = layer.rect
                    LayerDto(
                        layer.id, rect.left, rect.top, rect.right, rect.bottom, layer.visible,
                        scale = layer.scaleMode.name,
                        alpha = layer.alpha,
                        mirror = layer.mirror,
                        rot = layer.rotationDegrees
                    )
                },
                bg = layout.backgroundColor
            )
            prefs.edit().putString(KEY_COMPOSITION, gson.toJson(dto)).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not save the composition: ${t.message}")
        }
    }

    /**
     * Returns `null` on anything unexpected — a corrupt or newer blob must degrade to the
     * defaults, never crash the app on launch.
     */
    fun load(): SavedComposition? {
        val json = prefs.getString(KEY_COMPOSITION, null) ?: return null
        return try {
            val dto = gson.fromJson(json, CompositionDto::class.java) ?: return null
            // Older schemas are a subset of this one, so they load; a newer one is not understood.
            if (dto.v > SCHEMA_VERSION) {
                Log.i(TAG, "Ignoring a composition saved with schema ${dto.v}")
                return null
            }
            SavedComposition(
                pipSourceName = dto.pipSource,
                rects = dto.layers.associate { it.id to LayerRect(it.l, it.t, it.r, it.b) },
                hidden = dto.layers.filterNot { it.visible }.map { it.id }.toSet(),
                styles = dto.layers.associate {
                    it.id to LayerStyle(
                        scaleMode = it.scale?.let { name ->
                            runCatching { LayerScaleMode.valueOf(name) }.getOrNull()
                        },
                        alpha = it.alpha,
                        mirror = it.mirror,
                        rotationDegrees = it.rot
                    )
                },
                backgroundColor = dto.bg
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Ignoring an unreadable saved composition: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "CompositionStore"
        private const val PREFS_NAME = "composition"
        private const val KEY_COMPOSITION = "last_composition"
        private const val SCHEMA_VERSION = 2
    }
}
