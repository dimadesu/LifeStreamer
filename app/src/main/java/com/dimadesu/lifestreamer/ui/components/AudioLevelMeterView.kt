package com.dimadesu.lifestreamer.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.dimadesu.lifestreamer.audio.AudioLevel

/**
 * A horizontal audio level meter (VU meter) custom View.
 * Displays RMS level with color gradient from green to yellow to red.
 * Automatically shows 1 bar for mono or 2 bars for stereo audio.
 */
class AudioLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Left/mono channel state
    private var currentLevelLeft: Float = 0f
    private var targetLevelLeft: Float = 0f
    private var peakLevelLeft: Float = 0f
    private var peakHoldLevelLeft: Float = 0f
    private var peakHoldTimeLeft: Long = 0L
    private var isClippingLeft: Boolean = false
    
    // Right channel state (for stereo)
    private var currentLevelRight: Float = 0f
    private var targetLevelRight: Float = 0f
    private var peakLevelRight: Float = 0f
    private var peakHoldLevelRight: Float = 0f
    private var peakHoldTimeRight: Long = 0L
    private var isClippingRight: Boolean = false
    
    // Stereo mode
    private var isStereo: Boolean = false
    
    private var lastUpdateTime: Long = 0L  // Throttle UI updates
    
    // Minimum interval between UI updates (in ms) - ~30fps is plenty for a VU meter
    private val minUpdateIntervalMs = 33L
    // Peak hold duration in milliseconds before decay starts
    private val peakHoldDurationMs = 500L
    // Peak decay rate per frame (0-1, how much to reduce per update)
    private val peakDecayRate = 0.05f
    // Gap between stereo bars (in dp)
    private val stereoGapDp = 2f
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
    }
    
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    
    private val rect = RectF()
    private val levelRect = RectF()
    
    private var gradient: LinearGradient? = null
    private var lastWidth = 0
    
    // Animation smoothing factor (0-1, lower = smoother but slower)
    private val smoothingFactor = 0.3f
    
    init {
        // Set a default minimum size
        minimumHeight = (10 * resources.displayMetrics.density).toInt()
    }
    
    /**
     * Update the audio level display.
     * 
     * @param audioLevel The current audio level from the processor
     */
    fun setAudioLevel(audioLevel: AudioLevel) {
        val now = System.currentTimeMillis()
        
        // Update stereo mode
        isStereo = audioLevel.isStereo
        
        // Always update target levels and peak tracking (even if we skip redraw)
        targetLevelLeft = audioLevel.normalizedLevel
        
        // Calculate incoming peak level for left channel
        val incomingPeakLeft = if (audioLevel.peak > 0.0001f) {
            val peakDb = audioLevel.peakDb.coerceIn(-60f, 0f)
            (peakDb + 60f) / 60f
        } else 0f
        
        // Update left peak hold
        if (incomingPeakLeft >= peakHoldLevelLeft) {
            peakHoldLevelLeft = incomingPeakLeft
            peakHoldTimeLeft = now
        } else if (now - peakHoldTimeLeft > peakHoldDurationMs) {
            peakHoldLevelLeft = (peakHoldLevelLeft - peakDecayRate).coerceAtLeast(0f)
        }
        
        // Handle right channel for stereo
        if (isStereo) {
            targetLevelRight = audioLevel.normalizedLevelRight
            
            val incomingPeakRight = if (audioLevel.peakRight > 0.0001f) {
                val peakDb = audioLevel.peakDbRight.coerceIn(-60f, 0f)
                (peakDb + 60f) / 60f
            } else 0f
            
            if (incomingPeakRight >= peakHoldLevelRight) {
                peakHoldLevelRight = incomingPeakRight
                peakHoldTimeRight = now
            } else if (now - peakHoldTimeRight > peakHoldDurationMs) {
                peakHoldLevelRight = (peakHoldLevelRight - peakDecayRate).coerceAtLeast(0f)
            }
        }
        
        // Throttle UI updates to reduce resource usage
        if (now - lastUpdateTime < minUpdateIntervalMs) {
            return
        }
        lastUpdateTime = now
        
        peakLevelLeft = peakHoldLevelLeft
        isClippingLeft = audioLevel.isClipping
        
        if (isStereo) {
            peakLevelRight = peakHoldLevelRight
            isClippingRight = audioLevel.isClippingRight
        }
        
        // Animate towards target
        currentLevelLeft += (targetLevelLeft - currentLevelLeft) * smoothingFactor
        if (isStereo) {
            currentLevelRight += (targetLevelRight - currentLevelRight) * smoothingFactor
        }
        
        invalidate()
    }
    
    /**
     * Reset the meter to silent state.
     */
    fun reset() {
        currentLevelLeft = 0f
        targetLevelLeft = 0f
        peakLevelLeft = 0f
        peakHoldLevelLeft = 0f
        peakHoldTimeLeft = 0L
        isClippingLeft = false
        
        currentLevelRight = 0f
        targetLevelRight = 0f
        peakLevelRight = 0f
        peakHoldLevelRight = 0f
        peakHoldTimeRight = 0L
        isClippingRight = false
        
        isStereo = false
        invalidate()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // Recreate gradient when width changes
        if (w != lastWidth && w > 0) {
            lastWidth = w
            gradient = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(
                    Color.parseColor("#4CAF50"),  // Green
                    Color.parseColor("#4CAF50"),  // Green
                    Color.parseColor("#FFEB3B"),  // Yellow
                    Color.parseColor("#FF9800"),  // Orange
                    Color.parseColor("#F44336")   // Red
                ),
                floatArrayOf(0f, 0.6f, 0.75f, 0.9f, 1f),
                Shader.TileMode.CLAMP
            )
            levelPaint.shader = gradient
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        if (isStereo) {
            drawStereoMeters(canvas, w, h)
        } else {
            drawMonoMeter(canvas, w, h)
        }
    }
    
    private fun drawMonoMeter(canvas: Canvas, w: Float, h: Float) {
        val cornerRadius = h / 2
        
        // Draw background
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Draw level bar
        val levelWidth = (w * currentLevelLeft.coerceIn(0f, 1f))
        if (levelWidth > 0) {
            levelRect.set(0f, 0f, levelWidth, h)
            canvas.drawRoundRect(levelRect, cornerRadius, cornerRadius, levelPaint)
        }
        
        // Draw peak indicator
        if (peakLevelLeft > 0.01f) {
            val peakX = (w * peakLevelLeft.coerceIn(0f, 0.98f))
            peakPaint.color = if (isClippingLeft) Color.RED else Color.WHITE
            canvas.drawRect(peakX - 1f, 0f, peakX + 1f, h, peakPaint)
        }
    }
    
    private fun drawStereoMeters(canvas: Canvas, w: Float, h: Float) {
        val gap = stereoGapDp * resources.displayMetrics.density
        val barHeight = (h - gap) / 2
        val cornerRadius = barHeight / 2
        
        // Left channel (top bar)
        val topBarTop = 0f
        val topBarBottom = barHeight
        
        // Background
        rect.set(0f, topBarTop, w, topBarBottom)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Level bar
        val levelWidthLeft = (w * currentLevelLeft.coerceIn(0f, 1f))
        if (levelWidthLeft > 0) {
            levelRect.set(0f, topBarTop, levelWidthLeft, topBarBottom)
            canvas.drawRoundRect(levelRect, cornerRadius, cornerRadius, levelPaint)
        }
        
        // Peak indicator
        if (peakLevelLeft > 0.01f) {
            val peakX = (w * peakLevelLeft.coerceIn(0f, 0.98f))
            peakPaint.color = if (isClippingLeft) Color.RED else Color.WHITE
            canvas.drawRect(peakX - 1f, topBarTop, peakX + 1f, topBarBottom, peakPaint)
        }
        
        // Right channel (bottom bar)
        val bottomBarTop = barHeight + gap
        val bottomBarBottom = h
        
        // Background
        rect.set(0f, bottomBarTop, w, bottomBarBottom)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Level bar
        val levelWidthRight = (w * currentLevelRight.coerceIn(0f, 1f))
        if (levelWidthRight > 0) {
            levelRect.set(0f, bottomBarTop, levelWidthRight, bottomBarBottom)
            canvas.drawRoundRect(levelRect, cornerRadius, cornerRadius, levelPaint)
        }
        
        // Peak indicator
        if (peakLevelRight > 0.01f) {
            val peakX = (w * peakLevelRight.coerceIn(0f, 0.98f))
            peakPaint.color = if (isClippingRight) Color.RED else Color.WHITE
            canvas.drawRect(peakX - 1f, bottomBarTop, peakX + 1f, bottomBarBottom, peakPaint)
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (10 * resources.displayMetrics.density).toInt()
        
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
