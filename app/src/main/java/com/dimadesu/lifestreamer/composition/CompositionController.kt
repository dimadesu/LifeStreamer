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
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerScaleMode
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayoutPreset
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.applyPreset
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.swapLayerOrder
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSettings
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.CompositeVideoSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.LayerSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    parentScope: CoroutineScope,
    private val videoSourceProvider: () -> IVideoSource?,
    /**
     * Replaces the whole video source. Supplied by the service, which owns the streamer; this is
     * what lets a composition be turned on and off without the app's screen.
     */
    private val videoSourceSwitcher: (suspend (IVideoSourceInternal.Factory) -> Unit)? = null,
    /** URL and buffer of the RTMP source used for the second layer, or null when unset. */
    rtmpPipConfig: suspend () -> Pair<String, Int>? = { null }
) {
    /**
     * The one thread every mutation and every camera read runs on.
     *
     * Single-threaded on purpose: it is what makes the unguarded read-modify-writes in the
     * composite source safe with two writers (the UI and the remote control). Kept off the main
     * thread so a busy UI cannot delay a remote command, and below video and audio priority so it
     * can never compete with what goes on air.
     */
    private var confinementThread: Thread? = null

    private val confinementExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            r.run()
        }, "CompositionController").apply {
            isDaemon = true
            confinementThread = this
        }
    }

    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + confinementExecutor.asCoroutineDispatcher())

    private val capabilities = CompositionCapabilities(context)
    private val store = CompositionStore(context)

    private val sources = CompositionSources(
        context.applicationContext as android.app.Application, capabilities, rtmpPipConfig
    )

    /**
     * What the second layer is meant to show. Persisted, and applied live when it changes.
     *
     * It used to be a LiveData in the ViewModel that nothing observed: choosing a new source with
     * the composition running changed the chip's label and nothing else, until the composition was
     * switched off and on again.
     */
    private val _pipSource = MutableStateFlow(
        store.load()?.pipSourceName
            ?.let { runCatching { PipSourceKind.valueOf(it) }.getOrNull() }
            ?: PipSourceKind.TEST_IMAGE
    )
    val pipSource: StateFlow<PipSourceKind> = _pipSource.asStateFlow()

    /**
     * True while the second layer shows the placeholder because its real source could not be built
     * or died. Stops a failure from replacing a placeholder with another placeholder.
     */
    @Volatile
    var isPipOnPlaceholder: Boolean = true
        private set

    /** Registered by the ViewModel while it is alive; builds the sources only the app can build. */
    @Volatile
    var externalPipProvider: ExternalPipSourceProvider? = null

    /** Structural changes (on/off, source swaps) are serialised: two at once would fight over cameras. */
    private val structuralMutex = kotlinx.coroutines.sync.Mutex()

    private var failureJob: kotlinx.coroutines.Job? = null
    private var observedComposite: ICompositeVideoSource? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Things the operator needs to be told. The ViewModel turns these into toasts; the server
     * pushes them to the page, so a remote tap that was refused says why instead of doing
     * nothing.
     */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _layersInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /**
     * Fires when something a layer *shows* changed without the layout itself changing.
     *
     * Swapping a camera deliberately reuses the same VideoLayer so the rectangle survives, which
     * means the new layout compares equal and the StateFlow never emits. Labels and zoom are
     * derived from the live source, so without this signal both the on-device bar and the remote
     * page keep the previous camera's name indefinitely.
     */
    val layersInvalidated: SharedFlow<Unit> = _layersInvalidated.asSharedFlow()

    private val _cameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val cameras: StateFlow<List<CameraInfo>> = _cameras.asStateFlow()

    /**
     * Last known zoom per layer. Reading zoom from the camera is a suspending call that takes a
     * mutex and can queue behind a pending setZoomRatio; doing it inline while serialising state
     * froze the whole snapshot. State now reads this cache and asks for a refresh out of band.
     */
    @Volatile
    private var zoomCache: Map<String, ZoomState> = emptyMap()

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
     *
     * That thread used to be the **main** thread, which is the worst possible choice here: while
     * streaming the main thread carries the notification updater, the thermal ticks and the whole
     * UI, and it is raised to priority -19 when a stream starts. Every remote command was acked
     * instantly by the HTTP handler and then queued behind all of that, which is exactly what
     * "the commands do not arrive" felt like. It is now a private single thread, so a command
     * takes effect whether or not the UI is busy.
     */
    private fun confined(block: () -> Unit) {
        if (Thread.currentThread() === confinementThread) {
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
            // The layout is unchanged by design (the rectangle survives a camera swap), so the
            // layoutFlow will not emit. Everything derived from the live source -- the label on
            // the bar, the chip on the page, the zoom range -- needs this to be told.
            zoomCache = zoomCache - layerId
            _layersInvalidated.tryEmit(Unit)
            refreshZoomAsync()
        }
    }

    // endregion

    // region composition lifecycle — replaces the whole video source

    /**
     * The camera the main layer should use: what is on screen now if it is a camera, else a hint
     * the app leaves (the last camera the operator picked), else the first camera.
     */
    @Volatile
    var primaryCameraHint: String? = null

    private fun primaryCameraId(): String =
        (videoSourceProvider() as? ICameraSource)?.cameraId
            ?: (composite?.childSource(CompositionLayers.MAIN) as? ICameraSource)?.cameraId
            ?: primaryCameraHint
            ?: _cameras.value.firstOrNull()?.id
            ?: "0"

    /**
     * Turns the composition on: main camera plus the chosen second layer.
     *
     * Moved here from PreviewViewModel.toggleCompositeSource so the service -- and so the remote
     * control -- can do it with the app's screen gone. Switching the video source while streaming
     * does not reconnect: the encoder is kept and the picture freezes briefly.
     */
    suspend fun enableComposition(): Result<Unit> = structural {
        val switcher = videoSourceSwitcher ?: error("Cannot switch the video source from here")
        if (composite != null) return@structural
        val cameraId = primaryCameraId()
        val kind = _pipSource.value
        val pip = sources.pipSpec(kind, cameraId, externalPipProvider)
        switcher(
            CompositeVideoSourceFactory(
                listOf(sources.mainSpec(cameraId, pairedWithCamera = kind == PipSourceKind.CAMERA && !pip.isPlaceholder), pip.spec)
            )
        )
        isPipOnPlaceholder = pip.isPlaceholder
        composite?.let { onCompositionAppeared(it) }
        restoreSaved()
        pip.note?.let { _messages.tryEmit(it) }
        Log.i(TAG, "Composition on: camera $cameraId + ${kind.label}")
    }

    /** Turns the composition off, back to the camera the main layer was showing. */
    suspend fun disableComposition(): Result<Unit> = structural {
        val switcher = videoSourceSwitcher ?: error("Cannot switch the video source from here")
        val target = composite ?: return@structural
        val cameraId = (target.childSource(CompositionLayers.MAIN) as? ICameraSource)?.cameraId
            ?: primaryCameraId()
        stopObservingFailures()
        switcher(CameraSourceFactory(cameraId))
        Log.i(TAG, "Composition off, back to camera $cameraId")
    }

    /**
     * Chooses what the second layer shows, and applies it at once if a composition is running.
     */
    suspend fun setPipSource(kind: PipSourceKind): Result<Unit> = structural {
        _pipSource.value = kind
        scheduleSave()
        val target = composite ?: return@structural
        val pip = sources.pipSpec(kind, primaryCameraId(), externalPipProvider)
        target.replaceLayerSource(CompositionLayers.PIP, pip.spec)
        isPipOnPlaceholder = pip.isPlaceholder
        pip.note?.let { _messages.tryEmit(it) }
        _layersInvalidated.tryEmit(Unit)
        refreshZoomAsync()
        Log.i(TAG, "Second layer is now ${kind.label}${if (pip.isPlaceholder) " (placeholder)" else ""}")
    }

    /**
     * Puts an app-built source in the second layer, for sources whose readiness arrives later by
     * callback -- a USB camera that finishes opening after the composition was built.
     */
    suspend fun replacePipSource(factory: IVideoSourceInternal.Factory): Result<Unit> = structural {
        val target = composite ?: return@structural
        target.replaceLayerSource(CompositionLayers.PIP, LayerSpec(sources.pipLayer(), factory))
        isPipOnPlaceholder = false
        _layersInvalidated.tryEmit(Unit)
    }

    /** Falls back to the test image, keeping the stream going. [reason] is told to the operator. */
    suspend fun degradePipToPlaceholder(reason: String): Result<Unit> = structural {
        val target = composite ?: return@structural
        if (isPipOnPlaceholder) return@structural
        target.replaceLayerSource(CompositionLayers.PIP, sources.placeholderSpec())
        isPipOnPlaceholder = true
        _messages.tryEmit(reason)
        _layersInvalidated.tryEmit(Unit)
        Log.i(TAG, "Second layer degraded to the placeholder: $reason")
    }

    /** One row per kind, with why it cannot be used right now, for the page and the app's picker. */
    data class PipSourceOption(val kind: PipSourceKind, val available: Boolean, val reason: String?)

    fun pipSourceOptions(): List<PipSourceOption> = PipSourceKind.entries.map { kind ->
        val reason = when (kind) {
            PipSourceKind.TEST_IMAGE, PipSourceKind.RTMP -> null
            PipSourceKind.CAMERA -> capabilities.reasonSecondCameraUnavailable(primaryCameraId())
            PipSourceKind.SCREEN, PipSourceKind.USB ->
                externalPipProvider?.reasonUnavailable(kind)
                    ?: if (externalPipProvider == null) CompositionSources.OPEN_APP_REASON else null
        }
        PipSourceOption(kind, reason == null, reason)
    }

    /**
     * Wires failure handling to a composition, whoever created it -- the app, the page, or a
     * restored session. Idempotent per instance.
     *
     * This used to live in the ViewModel and died with it, so with the app's screen gone a second
     * layer whose source failed (an RTMP feed that stopped, say) simply went black.
     */
    fun onCompositionAppeared(target: ICompositeVideoSource) {
        if (observedComposite === target && failureJob?.isActive == true) return
        stopObservingFailures()
        observedComposite = target
        failureJob = scope.launch {
            target.layerFailureFlow.collect { failure ->
                Log.w(TAG, "Layer ${failure.layerId} failed: ${failure.reason}")
                if (failure.layerId == CompositionLayers.PIP) {
                    degradePipToPlaceholder("${_pipSource.value.label} lost - showing placeholder")
                }
            }
        }
    }

    private fun stopObservingFailures() {
        failureJob?.cancel()
        failureJob = null
        observedComposite = null
    }

    private suspend fun structural(block: suspend () -> Unit): Result<Unit> =
        structuralMutex.withLock { runCatching { block() } }
            .onFailure { Log.w(TAG, "Composition change failed: ${it.message}", it) }

    // endregion

    // region style — geometry only, safe while streaming

    /**
     * Changes how a layer is drawn. Null leaves that property as it is.
     *
     * Only what the compositor reads each frame: no source restarts and no encoder changes, so it
     * is safe on air. Changing the scale mode stops matching any preset, which is correct -- the
     * layout is no longer the preset's.
     */
    fun setLayerStyle(
        layerId: String,
        scaleMode: LayerScaleMode? = null,
        alpha: Float? = null,
        mirror: Boolean? = null,
        rotationDegrees: Int? = null
    ) = confined {
        composite?.updateLayer(layerId) {
            it.copy(
                scaleMode = scaleMode ?: it.scaleMode,
                // Zero removes the layer from drawing, which hiding already does; keep it visible.
                alpha = (alpha ?: it.alpha).coerceIn(MIN_ALPHA, 1f),
                mirror = mirror ?: it.mirror,
                rotationDegrees = rotationDegrees?.let { r -> ((r % 360) + 360) % 360 / 90 * 90 }
                    ?: it.rotationDegrees
            )
        }
        scheduleSave()
    }

    /** The colour behind the layers, shown wherever they do not cover the canvas. */
    fun setBackgroundColor(argb: Int) = confined {
        val target = composite ?: return@confined
        target.updateLayout(target.layoutFlow.value.copy(backgroundColor = argb))
        scheduleSave()
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
        val settings = cameraSettingsForLayer(layerId)
        if (settings == null) {
            Log.d(TAG, "No camera settings for layer $layerId")
            return null
        }
        if (!settings.isActiveFlow.value) {
            Log.d(TAG, "Camera for layer $layerId is not active yet")
            return null
        }
        return runCatching {
            val range = settings.zoom.availableRatioRange
            // The lower bound can be below 1.0 where an ultra-wide is part of the logical camera,
            // so a slider must use it rather than assuming 1.
            ZoomState(range.lower, range.upper, settings.zoom.getZoomRatio())
        }.onFailure { Log.w(TAG, "Could not read zoom for layer $layerId: ${it.message}") }
            .getOrNull()
    }

    /**
     * The last zoom read for [layerId], without suspending. Null until the first refresh lands.
     */
    fun cachedZoomState(layerId: String): ZoomState? = zoomCache[layerId]

    /**
     * Re-reads zoom for every layer off the caller's thread and signals if anything moved.
     *
     * Deliberately fire-and-forget: a state snapshot uses whatever the cache holds and the
     * refresh triggers another push once it converges, instead of blocking the serialisation.
     */
    fun refreshZoomAsync() {
        scope.launch {
            val target = composite ?: return@launch
            val ids = target.layoutFlow.value.layers.map { it.id }
            val next = mutableMapOf<String, ZoomState>()
            ids.forEach { id -> zoomState(id)?.let { next[id] = it } }
            if (next != zoomCache) {
                zoomCache = next
                _layersInvalidated.tryEmit(Unit)
            }

            // A camera that has just been opened reports "not active" for a moment. Refreshing
            // only on change meant a composition switched on before its cameras settled kept an
            // empty zoom for good, so retry a few times until every camera layer has a reading.
            val cameraLayers = ids.count { target.childSource(it) is ICameraSource }
            if (next.size < cameraLayers && zoomRetries < MAX_ZOOM_RETRIES) {
                zoomRetries++
                kotlinx.coroutines.delay(ZOOM_RETRY_MS)
                refreshZoomAsync()
            } else {
                zoomRetries = 0
            }
        }
    }

    @Volatile
    private var zoomRetries = 0

    /**
     * The layer a zoom command applies to: the one asked for, or the primary when none is given.
     *
     * The remote page always names a layer, and an explicit name used to be accepted and then
     * silently dropped, so zooming the inset did nothing while reporting success.
     */
    private fun zoomTargetId(layerId: String?): String? =
        layerId ?: composite?.layoutFlow?.value?.primaryLayer?.id

    /**
     * Zoom is a control input, not a request/response: it is dispatched and the authoritative
     * value comes back with the next state read. Never block a request thread on it.
     */
    fun setZoom(layerId: String?, ratio: Float) {
        scope.launch {
            val id = zoomTargetId(layerId)
            val settings = cameraSettingsForLayer(id) ?: return@launch
            if (!settings.isActiveFlow.value) {
                return@launch
            }
            runCatching { settings.zoom.setZoomRatio(ratio) }
                .onFailure { Log.w(TAG, "Could not set zoom: ${it.message}") }
            refreshZoomAsync()
        }
    }

    fun nudgeZoom(layerId: String?, factor: Float) {
        scope.launch {
            val id = zoomTargetId(layerId)
            val settings = cameraSettingsForLayer(id) ?: return@launch
            if (!settings.isActiveFlow.value) {
                return@launch
            }
            runCatching {
                val range = settings.zoom.availableRatioRange
                val wanted = settings.zoom.getZoomRatio() * factor
                settings.zoom.setZoomRatio(wanted.coerceIn(range.lower, range.upper))
            }.onFailure { Log.w(TAG, "Could not nudge zoom: ${it.message}") }
            refreshZoomAsync()
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
        if (cameraId != null) return displayName(cameraId)
        return when (layerId) {
            CompositionLayers.MAIN -> "Camera"
            // Says what is really on screen: a source that could not be built, or died, shows the
            // test image, and the label must not keep naming the source that is not there.
            CompositionLayers.PIP ->
                if (isPipOnPlaceholder) PipSourceKind.TEST_IMAGE.label else _pipSource.value.label
            else -> layerId
        }
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
            // The source kind used to come from a field nobody ever assigned, so every save made
            // here -- a drag, a preset, a swap, from the phone or the page -- wrote an empty name
            // over the one the ViewModel had saved.
            store.save(pipSourceName = _pipSource.value.name, layout = current)
        }
    }

    /**
     * Puts the saved arrangement back: rectangles, visibility, each layer's style and the
     * background. Only for layers that still exist; the rest keep their defaults.
     */
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
            saved.styles[layer.id]?.let { style ->
                current = current.mapLayer(layer.id) {
                    it.copy(
                        scaleMode = style.scaleMode ?: it.scaleMode,
                        alpha = style.alpha ?: it.alpha,
                        mirror = style.mirror ?: it.mirror,
                        rotationDegrees = style.rotationDegrees ?: it.rotationDegrees
                    )
                }
                changed = true
            }
        }
        saved.backgroundColor?.let {
            current = current.copy(backgroundColor = it)
            changed = true
        }

        if (changed) {
            target.updateLayout(current)
            Log.i(TAG, "Restored the saved layout")
        }
    }

    // endregion

    companion object {
        private const val TAG = "CompositionController"

        /** How close to an edge or the centre a dragged layer snaps, in canvas fractions. */
        private const val SNAP_THRESHOLD = 0.02f
        private const val SAVE_DEBOUNCE_MS = 500L
        private const val MIN_ALPHA = 0.1f
        private const val MAX_ZOOM_RETRIES = 10
        private const val ZOOM_RETRY_MS = 1_000L
    }
}
