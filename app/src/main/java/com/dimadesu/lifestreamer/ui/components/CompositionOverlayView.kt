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
package com.dimadesu.lifestreamer.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The on-air layout editor: draws each layer's rectangle over the preview and turns touches into
 * geometry changes.
 *
 * It is laid out with the exact same size, pivot and scale as the preview, so a normalized
 * rectangle maps to a local pixel by a plain multiply — correct in any orientation, with no extra
 * maths to keep in sync.
 *
 * It is only ever visible in edit mode. Outside of it the view is [View.GONE], not merely
 * transparent, so it cannot intercept a touch meant for the preview and cannot move a layer while
 * the operator is live.
 */
class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * One layer as the editor sees it.
     */
    data class OverlayLayer(
        val id: String,
        val label: String,
        val rect: LayerRect
    )

    interface Listener {
        fun onLayerSelected(layerId: String?)

        /** Fires continuously while dragging. Must be cheap: no allocation, no I/O. */
        fun onLayerGeometryChanged(layerId: String, rect: LayerRect)

        /** Fires once the gesture ends, for snapping and persistence. */
        fun onLayerGeometryCommitted(layerId: String)
    }

    var listener: Listener? = null

    private var layers: List<OverlayLayer> = emptyList()
    private var selectedLayerId: String? = null

    private val density = resources.displayMetrics.density
    private val handleSizePx = HANDLE_DP * density
    private val touchSlopPx = DRAG_SLOP_DP * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val selectedStrokePaint = Paint(strokePaint).apply {
        color = SELECTED_COLOR
        strokeWidth = 3f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTED_COLOR
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAA000000.toInt()
    }

    private val scratchRect = RectF()

    // Gesture state
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var dragStartRect: LayerRect? = null
    private var pinchStartDistance = 0f
    private var pinchStartRect: LayerRect? = null

    private enum class Mode { NONE, PENDING, MOVE, RESIZE, PINCH }

    fun setLayers(layers: List<OverlayLayer>) {
        this.layers = layers
        if (layers.none { it.id == selectedLayerId }) {
            selectedLayerId = layers.lastOrNull()?.id
        }
        invalidate()
    }

    fun setSelectedLayer(layerId: String?) {
        selectedLayerId = layerId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }

        layers.forEach { layer ->
            val isSelected = layer.id == selectedLayerId
            toPixels(layer.rect, scratchRect)

            canvas.drawRect(
                scratchRect,
                if (isSelected) selectedStrokePaint else strokePaint
            )

            drawLabel(canvas, layer.label, scratchRect.left, scratchRect.top)

            if (isSelected) {
                canvas.drawRect(
                    scratchRect.right - handleSizePx,
                    scratchRect.bottom - handleSizePx,
                    scratchRect.right,
                    scratchRect.bottom,
                    handlePaint
                )
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, left: Float, top: Float) {
        val padding = 4f * density
        val textWidth = labelPaint.measureText(text)
        val textHeight = labelPaint.textSize
        canvas.drawRect(
            left,
            top,
            left + textWidth + padding * 2,
            top + textHeight + padding * 2,
            labelBackgroundPaint
        )
        canvas.drawText(text, left + padding, top + textHeight + padding / 2, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTimeMs = System.currentTimeMillis()

                val hit = hitTest(event.x, event.y)
                if (hit == null) {
                    // A touch outside every layer clears the selection rather than doing nothing,
                    // so it is always obvious what a drag would move.
                    selectedLayerId = null
                    listener?.onLayerSelected(null)
                    invalidate()
                    return true
                }

                if (hit.id != selectedLayerId) {
                    selectedLayerId = hit.id
                    listener?.onLayerSelected(hit.id)
                    invalidate()
                }

                dragStartRect = hit.rect
                mode = if (isOnHandle(hit.rect, event.x, event.y)) Mode.RESIZE else Mode.PENDING
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && selectedLayerId != null) {
                    pinchStartDistance = distanceBetween(event)
                    pinchStartRect = selectedLayer()?.rect
                    mode = Mode.PINCH
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val layerId = selectedLayerId ?: return true

                when (mode) {
                    Mode.PENDING -> {
                        // Armed only after a deliberate movement or a short hold, so brushing the
                        // screen while live cannot nudge a layer.
                        val moved = hypot(event.x - downX, event.y - downY) > touchSlopPx
                        val held = System.currentTimeMillis() - downTimeMs > HOLD_TO_DRAG_MS
                        if (moved || held) {
                            mode = Mode.MOVE
                        }
                    }

                    Mode.MOVE -> {
                        val start = dragStartRect ?: return true
                        val dx = (event.x - downX) / width
                        val dy = (event.y - downY) / height
                        listener?.onLayerGeometryChanged(layerId, start.offsetInsideCanvas(dx, dy))
                    }

                    Mode.RESIZE -> {
                        val start = dragStartRect ?: return true
                        listener?.onLayerGeometryChanged(layerId, resized(start, event.x, event.y))
                    }

                    Mode.PINCH -> {
                        val start = pinchStartRect ?: return true
                        if (pinchStartDistance <= 0f) return true
                        val factor = distanceBetween(event) / pinchStartDistance
                        listener?.onLayerGeometryChanged(layerId, scaled(start, factor))
                    }

                    Mode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    mode = Mode.NONE
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val layerId = selectedLayerId
                if (layerId != null && mode != Mode.NONE && mode != Mode.PENDING) {
                    listener?.onLayerGeometryCommitted(layerId)
                }
                mode = Mode.NONE
                dragStartRect = null
                pinchStartRect = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Resizes about the top-left corner, which stays put while the handle follows the finger.
     */
    private fun resized(start: LayerRect, x: Float, y: Float): LayerRect {
        val right = (x / width).coerceIn(start.left + MIN_SIZE, 1f)
        val bottom = (y / height).coerceIn(start.top + MIN_SIZE, 1f)
        return LayerRect(start.left, start.top, right, bottom)
    }

    /**
     * Scales about the rectangle's own centre, clamped to the canvas.
     */
    private fun scaled(start: LayerRect, factor: Float): LayerRect {
        val halfWidth = (start.width * factor / 2f).coerceIn(MIN_SIZE / 2f, 0.5f)
        val halfHeight = (start.height * factor / 2f).coerceIn(MIN_SIZE / 2f, 0.5f)
        val centerX = start.centerX.coerceIn(halfWidth, 1f - halfWidth)
        val centerY = start.centerY.coerceIn(halfHeight, 1f - halfHeight)
        return LayerRect(
            max(0f, centerX - halfWidth),
            max(0f, centerY - halfHeight),
            min(1f, centerX + halfWidth),
            min(1f, centerY + halfHeight)
        )
    }

    private fun selectedLayer(): OverlayLayer? = layers.firstOrNull { it.id == selectedLayerId }

    /**
     * Topmost layer under the point. [layers] arrives in draw order, so the last match wins.
     */
    private fun hitTest(x: Float, y: Float): OverlayLayer? {
        return layers.lastOrNull { layer ->
            toPixels(layer.rect, scratchRect)
            scratchRect.contains(x, y)
        }
    }

    private fun isOnHandle(rect: LayerRect, x: Float, y: Float): Boolean {
        toPixels(rect, scratchRect)
        return abs(x - scratchRect.right) <= handleSizePx &&
                abs(y - scratchRect.bottom) <= handleSizePx
    }

    private fun toPixels(rect: LayerRect, out: RectF) {
        out.set(
            rect.left * width,
            rect.top * height,
            rect.right * width,
            rect.bottom * height
        )
    }

    private fun distanceBetween(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    companion object {
        private const val HANDLE_DP = 22f
        private const val DRAG_SLOP_DP = 8f
        private const val HOLD_TO_DRAG_MS = 200L
        private const val MIN_SIZE = 0.08f
        private const val SELECTED_COLOR = 0xFF4CAF50.toInt()
    }
}
