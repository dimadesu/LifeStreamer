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

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.ui.main.RtmpSourceSwitchHelper
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerScaleMode
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.LayerSpec

/**
 * What feeds the second layer of the composition.
 */
enum class PipSourceKind(val label: String) {
    TEST_IMAGE("Test image"),
    CAMERA("Second camera"),
    USB("USB camera"),
    SCREEN("Screen"),
    RTMP("RTMP / SRT source")
}

/** The two layers the app composes. Fixed ids: failure handling, USB and labels key on them. */
object CompositionLayers {
    const val MAIN = "main"
    const val PIP = "pip"
}

/**
 * Sources only the app's screen can build.
 *
 * A screen layer needs a MediaProjection grant and a USB layer needs the UVC helper, and both are
 * obtained through interactive dialogs on the phone. The service cannot produce either, so the
 * ViewModel registers itself as this while it is alive; without it those kinds are offered as
 * unavailable, with the reason, instead of silently doing nothing.
 */
interface ExternalPipSourceProvider {
    /** A factory for [kind], or null if it cannot be built right now. */
    fun factoryFor(kind: PipSourceKind): IVideoSourceInternal.Factory?

    /** Why [kind] cannot be used right now, or null if it can. */
    fun reasonUnavailable(kind: PipSourceKind): String?
}

/**
 * The result of building the second layer. [isPlaceholder] is true when the requested source could
 * not be built and the test image stands in for it; [note] says why, for the operator.
 */
data class PipBuild(val spec: LayerSpec, val isPlaceholder: Boolean, val note: String? = null)

/**
 * Builds the layer specs the composition is made of.
 *
 * Moved out of PreviewViewModel so the service can build a composition on its own: the remote
 * control runs precisely when the app's screen is gone, and everything that only lived in the
 * ViewModel was out of its reach.
 */
class CompositionSources(
    private val application: Application,
    private val capabilities: CompositionCapabilities,
    /** URL and playback buffer of the RTMP source used for the second layer, or null if unset. */
    private val rtmpPipConfig: suspend () -> Pair<String, Int>?
) {
    /** Also the placeholder: a bitmap source cannot fail, which makes it a safe terminal state. */
    val testBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(application.resources, R.drawable.img_test)
    }

    fun mainLayer() = VideoLayer(
        id = CompositionLayers.MAIN,
        z = 0,
        rect = LayerRect.FULL,
        scaleMode = LayerScaleMode.FILL
    )

    fun pipLayer() = VideoLayer(
        id = CompositionLayers.PIP,
        z = 1,
        rect = LayerRect.PIP_BOTTOM_RIGHT,
        scaleMode = LayerScaleMode.FIT
    )

    /**
     * @param pairedWithCamera true when the second layer is also a camera: both devices must then
     * stay inside the concurrent-pair configuration, so the main camera is capped too -- capping
     * only the small one would still fail to open.
     */
    fun mainSpec(cameraId: String, pairedWithCamera: Boolean) = LayerSpec(
        layer = mainLayer(),
        childFactory = CameraSourceFactory(cameraId),
        captureResolution = if (pairedWithCamera) capabilities.report().concurrentCameraMaxSize else null
    )

    fun placeholderSpec() = LayerSpec(
        layer = pipLayer(),
        childFactory = BitmapSourceFactory(testBitmap)
    )

    /**
     * Builds the second layer for [kind]. A source that cannot be built right now degrades to the
     * placeholder instead of failing the whole composition.
     */
    suspend fun pipSpec(
        kind: PipSourceKind,
        primaryCameraId: String,
        external: ExternalPipSourceProvider?
    ): PipBuild = when (kind) {
        PipSourceKind.TEST_IMAGE -> PipBuild(placeholderSpec(), isPlaceholder = true)

        PipSourceKind.CAMERA -> {
            val secondId = capabilities.secondCameraFor(primaryCameraId)
            if (secondId == null) {
                PipBuild(
                    placeholderSpec(), isPlaceholder = true,
                    note = capabilities.reasonSecondCameraUnavailable(primaryCameraId)
                )
            } else {
                PipBuild(
                    LayerSpec(
                        layer = pipLayer(),
                        childFactory = CameraSourceFactory(secondId),
                        // Both cameras of a concurrent pair have to stay inside the guaranteed
                        // configuration, so the capture size is capped rather than inherited.
                        captureResolution = capabilities.report().concurrentCameraMaxSize
                    ),
                    isPlaceholder = false
                )
            }
        }

        PipSourceKind.RTMP -> try {
            val (url, bufferMs) = rtmpPipConfig() ?: error("RTMP source 1 has no URL")
            require(url.isNotBlank()) { "RTMP source 1 has no URL" }
            val player = RtmpSourceSwitchHelper.createExoPlayer(application, url, bufferMs)
            PipBuild(
                LayerSpec(layer = pipLayer(), childFactory = RTMPVideoSource.Factory(player)),
                isPlaceholder = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not build the RTMP layer: ${e.message}")
            PipBuild(placeholderSpec(), isPlaceholder = true, note = "RTMP source unavailable - showing placeholder")
        }

        PipSourceKind.SCREEN, PipSourceKind.USB -> {
            val factory = external?.factoryFor(kind)
            if (factory == null) {
                PipBuild(
                    placeholderSpec(), isPlaceholder = true,
                    note = external?.reasonUnavailable(kind) ?: OPEN_APP_REASON
                )
            } else {
                PipBuild(LayerSpec(layer = pipLayer(), childFactory = factory), isPlaceholder = false)
            }
        }
    }

    companion object {
        private const val TAG = "CompositionSources"

        /** Screen and USB need a dialog on the phone; with the app closed there is no way to show it. */
        const val OPEN_APP_REASON = "Needs the app open on the phone"
    }
}
