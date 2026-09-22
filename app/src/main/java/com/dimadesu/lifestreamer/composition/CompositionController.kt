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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Looper
import android.util.Log
import android.util.SizeF
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionPresets
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayoutPreset
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.applyPreset
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.swapLayerOrder
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSettings
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.LayerSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

data class CameraInfo(val id: String, val displayName: String, val facing: String)

data class ZoomState(val min: Float, val max: Float, val ratio: Float)

/**
 * Owns everything about a running composition that is not UI.
 *
 * It lives in the foreground service rather than the ViewModel because there are now two
 * consumers with different lifetimes: the on-device UI, which dies with the Activity, and the
 * remote control server, which must keep working precisely when it has — phone mounted, screen
 * off, Activity destroyed.
 *
 * Duplicating the rules into the server instead was the alternative, and the rules are not
 * incidental: [CompositionCapabilities.canRunTogether] exists so an impossible camera pairing is
 * refused *before* camera2 fails to open mid-stream, the concurrent resolution cap has to apply
 * to the whole pair, and snapping plus the debounced save are what make the on-device drag and
 * the remote drag agree. Two copies of that would drift in silence, and the drift shows up as a
 * broken stream.
 */
class CompositionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val videoSourceProvider: () -> IVideoSource?
) {
    private val capabilities = CompositionCapabilities(context)
    private val store = CompositionStore(context)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Things the operator needs to be told. The ViewModel turns these into toasts; the server
     * pushes them to the page, so a remote tap that was refused says why instead of doing
     * nothing.
     */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _cameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val cameras: StateFlow<List<CameraInfo>> = _cameras.asStateFlow()

    val presets: List<LayoutPreset> get() = CompositionPresets.ALL

    val composite: ICompositeVideoSource?
        get() = videoSourceProvider() as? ICompositeVideoSource

    val layout: CompositionLayout? get() = composite?.layoutFlow?.value

    val isCompositionActive: Boolean get() = composite != null

    init {
        scope.launch { loadCameras() }
    }

    // region thread confinement

    /**
     * Confines every mutation to one thread.
     *
     * [ICompositeVideoSource.updateLayer] is an unguarded read-modify-write and `childSource`
     * reads the children map outside the mutex that otherwise guards it. That was safe while the
     * UI thread was the only writer; the remote control is the second one.
     */
    private fun confined(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            scope.launch { block() }
        }
    }

    // endregion

    // region geometry — safe at gesture rate, never touches a source

    fun setLayerRect(layerId: String, rect: LayerRect) = confined {
        composite?.updateLayer(layerId) { it.copy(rect = rect) }
    }

    /**
     * Ends a drag: snaps to the canvas edges and centre, then persists.
     *
     * Snapping lives here rather than in either editor so the on-device drag and the remote drag
     * cannot disagree about where a layer ends up.
     */
    fun commitLayerGeometry(layerId: String) = confined {
        composite?.updateLayer(layerId) { layer -> layer.copy(rect = snapped(layer.rect)) }
        scheduleSave()
    }

    fun applyPreset(presetId: String) = confined {
        val target = composite ?: return@confined
        val preset = CompositionPresets.ALL.firstOrNull { it.id == presetId } ?: return@confined
        target.updateLayout(target.layoutFlow.value.applyPreset(preset))
        scheduleSave()
        Log.i(TAG, "Applied layout ${preset.name}")
    }

    fun swapLayers() = confined {
        val target = composite ?: return@confined
        val layers = target.layoutFlow.value.layers.sortedBy { it.z }
        if (layers.size < 2) {
            _messages.tryEmit("Nothing to swap yet")
            return@confined
        }
        target.updateLayout(
            target.layoutFlow.value.swapLayerOrder(layers.first().id, layers.last().id)
        )
        scheduleSave()
    }

    fun setLayerVisible(layerId: String, visible: Boolean) = confined {
        composite?.updateLayer(layerId) { it.copy(visible = visible) }
        scheduleSave()
    }

    fun setPrimaryLayer(layerId: String) = confined {
        composite?.setPrimaryLayer(layerId)
        scheduleSave()
    }

    private fun snapped(rect: LayerRect): LayerRect {
        val width = rect.width
        val height = rect.height
        var left = rect.left
        var top = rect.top

        if (left < SNAP_THRESHOLD) left = 0f
        if (top < SNAP_THRESHOLD) top = 0f
        if (left + width > 1f - SNAP_THRESHOLD) left = 1f - width
        if (top + height > 1f - SNAP_THRESHOLD) top = 1f - height
        if (abs(left + width / 2f - 0.5f) < SNAP_THRESHOLD) left = 0.5f - width / 2f
        if (abs(top + height / 2f - 0.5f) < SNAP_THRESHOLD) top = 0.5f - height / 2f

        return LayerRect(left, top, left + width, top + height)
    }

    // endregion

    // region structural — opens devices, can fail

    /**
     * Points one layer at a different camera, keeping its rectangle and depth.
     *
     * Refuses a pairing the hardware cannot honour *before* trying, because letting camera2 fail
     * to open leaves the composition half-built in the middle of a live stream.
     */
    suspend fun setLayerCamera(layerId: String, cameraId: String): Result<Unit> {
        val target = composite ?: return Result.failure(IllegalStateException("No composition"))
        val currentLayout = target.layoutFlow.value
        val layer = currentLayout[layerId]
            ?: return Result.failure(IllegalArgumentException("No layer $layerId"))

        val usedElsewhere = currentLayout.layers
            .filter { it.id != layerId }
            .mapNotNull { (target.childSource(it.id) as? ICameraSource)?.cameraId }

        if (usedElsewhere.contains(cameraId)) {
            val message = "That camera is already used by another layer"
            _messages.tryEmit(message)
            return Result.failure(IllegalStateException(message))
        }

        usedElsewhere.firstOrNull { !capabilities.canRunTogether(cameraId, it) }?.let { clash ->
            val message = "${displayName(cameraId)} cannot run at the same time as " +
                    "${displayName(clash)} on this device"
            _messages.tryEmit(message)
            Log.w(TAG, "Refused camera $cameraId: clashes with $clash")
            return Result.failure(IllegalStateException(message))
        }

        // Both cameras of a concurrent pair must stay inside the guaranteed configuration.
        val captureResolution = if (usedElsewhere.isNotEmpty()) {
            capabilities.report().concurrentCameraMaxSize
        } else {
            null
        }

        return runCatching {
            target.replaceLayerSource(
                layerId,
                LayerSpec(
                    layer = layer,
                    childFactory = CameraSourceFactory(cameraId),
                    captureResolution = captureResolution
                )
            )
            _messages.tryEmit("${displayName(cameraId)} -> ${positionName(layerId)}")
            Log.i(TAG, "Layer $layerId now uses camera $cameraId")
        }
    }

    // endregion

    // region zoom

    /**
     * The camera behind [layerId], or behind the whole source when there is no composition.
     *
     * The app used to cast the top-level source to [ICameraSource], which is null the moment a
     * composition is active — so zoom, exposure and focus were dead on the device whenever a
     * composition ran. Going through the child fixes the on-device controls and the remote ones
     * at the same time.
     */
    fun cameraSettingsForLayer(layerId: String?): CameraSettings? {
        val source = videoSourceProvider()
        if (source is ICompositeVideoSource) {
            val id = layerId ?: source.layoutFlow.value.primaryLayer?.id ?: return null
            return (source.childSource(id) as? ICameraSource)?.settings
        }
        return (source as? ICameraSource)?.settings
    }

    suspend fun zoomState(layerId: String?): ZoomState? {
        val settings = cameraSettingsForLayer(layerId) ?: return null
        if (!settings.isActiveFlow.value) {
            return null
        }
        return runCatching {
            val range = settings.zoom.availableRatioRange
            // The lower bound can be below 1.0 where an ultra-wide is part of the logical camera,
            // so a slider must use it rather than assuming 1.
            ZoomState(range.lower, range.upper, settings.zoom.getZoomRatio())
        }.getOrNull()
    }

    /**
     * Zoom is a control input, not a request/response: it is dispatched and the authoritative
     * value comes back with the next state read. Never block a request thread on it.
     */
    fun setZoom(layerId: String?, ratio: Float) {
        scope.launch {
            val settings = cameraSettingsForLayer(layerId) ?: return@launch
            if (!settings.isActiveFlow.value) {
                return@launch
            }
            runCatching { settings.zoom.setZoomRatio(ratio) }
                .onFailure { Log.w(TAG, "Could not set zoom: ${it.message}") }
        }
    }

    fun nudgeZoom(layerId: String?, factor: Float) {
        scope.launch {
            val settings = cameraSettingsForLayer(layerId) ?: return@launch
            if (!settings.isActiveFlow.value) {
                return@launch
            }
            runCatching { settings.zoom.setZoomRatio(settings.zoom.getZoomRatio() * factor) }
                .onFailure { Log.w(TAG, "Could not nudge zoom: ${it.message}") }
        }
    }

    // endregion

    // region naming and cameras

    fun displayName(cameraId: String): String =
        _cameras.value.firstOrNull { it.id == cameraId }?.displayName ?: cameraId

    /**
     * What a layer is called. A camera layer is named after its camera, because with two of them
     * on screen "Camera" and "Second camera" say nothing about which is which.
     */
    fun layerLabel(layerId: String): String {
        val cameraId = (composite?.childSource(layerId) as? ICameraSource)?.cameraId
        return cameraId?.let { displayName(it) } ?: layerId
    }

    /**
     * Where a layer sits, independent of what feeds it, so it still reads right after a swap.
     */
    fun positionName(layerId: String): String {
        val current = layout ?: return layerId
        val layer = current[layerId] ?: return layerId
        return when {
            layer.rect == LayerRect.FULL -> "main"
            current.layers.size <= 1 -> "main"
            else -> "inset"
        }
    }

    fun cameraIdsInUse(): Set<String> {
        val target = composite ?: return emptySet()
        return target.layoutFlow.value.layers
            .mapNotNull { (target.childSource(it.id) as? ICameraSource)?.cameraId }
            .toSet()
    }

    suspend fun loadCameras() = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.mapNotNull { id ->
                runCatching {
                    val characteristics = manager.getCameraCharacteristics(id)
                    val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                        CameraCharacteristics.LENS_FACING_BACK -> "Back"
                        else -> "External"
                    }
                    CameraInfo(id, "$facing ${fieldOfView(characteristics)}".trim(), facing)
                }.getOrNull()
            }
        }.onSuccess { _cameras.value = it }
            .onFailure { Log.e(TAG, "Could not list cameras: ${it.message}") }
        Unit
    }

    /**
     * A rough diagonal field of view, which is how an operator tells two back cameras apart far
     * better than by id.
     */
    private fun fieldOfView(characteristics: CameraCharacteristics): String {
        val focal = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return ""
        val sensor = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) as? SizeF
            ?: return "f=%.1fmm".format(focal)

        val diagonal = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
        val degrees = (2.0 * atan((diagonal / (2.0 * focal)).toDouble()) * 180.0 / Math.PI).toInt()
        return "$degrees°"
    }

    // endregion

    // region persistence

    private var saveJob: kotlinx.coroutines.Job? = null

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(SAVE_DEBOUNCE_MS)
            val current = layout ?: return@launch
            store.save(
                pipSourceName = savedPipSourceName,
                rects = current.layers.associate { it.id to it.rect },
                hidden = current.layers.filterNot { it.visible }.map { it.id }.toSet()
            )
        }
    }

    /** Set by the app so the stored blob keeps naming the chosen second source. */
    var savedPipSourceName: String = ""

    fun restoreSaved() {
        val target = composite ?: return
        val saved = store.load() ?: return
        var current = target.layoutFlow.value
        var changed = false

        current.layers.forEach { layer ->
            saved.rects[layer.id]?.let { rect ->
                current = current.mapLayer(layer.id) {
                    it.copy(rect = rect, visible = !saved.hidden.contains(layer.id))
                }
                changed = true
            }
        }

        if (changed) {
            target.updateLayout(current)
            Log.i(TAG, "Restored the saved layout")
        }
    }

    fun savedPipSourceNameOrNull(): String? = store.load()?.pipSourceName

    // endregion

    companion object {
        private const val TAG = "CompositionController"

        /** How close to an edge or the centre a dragged layer snaps, in canvas fractions. */
        private const val SNAP_THRESHOLD = 0.02f
        private const val SAVE_DEBOUNCE_MS = 500L
    }
}
