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
 */
class AudioLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentLevel: Float = 0f
    private var targetLevel: Float = 0f
    private var peakLevel: Float = 0f
    private var peakHoldLevel: Float = 0f  // The displayed peak (with hold/decay)
    private var peakHoldTime: Long = 0L    // When the peak was last updated
    private var isClipping: Boolean = false
    
    // Peak hold duration in milliseconds before decay starts
    private val peakHoldDurationMs = 500L
    // Peak decay rate per frame (0-1, how much to reduce per update)
    private val peakDecayRate = 0.05f
    
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
        minimumHeight = (8 * resources.displayMetrics.density).toInt()
    }
    
    /**
     * Update the audio level display.
     * 
     * @param audioLevel The current audio level from the processor
     */
    fun setAudioLevel(audioLevel: AudioLevel) {
        targetLevel = audioLevel.normalizedLevel
        
        // Calculate incoming peak level
        val incomingPeak = if (audioLevel.peak > 0.0001f) {
            val peakDb = audioLevel.peakDb.coerceIn(-60f, 0f)
            (peakDb + 60f) / 60f
        } else 0f
        
        // Update peak hold: if new peak is higher, capture it
        val now = System.currentTimeMillis()
        if (incomingPeak >= peakHoldLevel) {
            peakHoldLevel = incomingPeak
            peakHoldTime = now
        } else {
            // Decay peak after hold duration
            if (now - peakHoldTime > peakHoldDurationMs) {
                peakHoldLevel = (peakHoldLevel - peakDecayRate).coerceAtLeast(0f)
            }
        }
        
        peakLevel = peakHoldLevel
        isClipping = audioLevel.isClipping
        
        // Animate towards target
        currentLevel += (targetLevel - currentLevel) * smoothingFactor
        
        invalidate()
    }
    
    /**
     * Reset the meter to silent state.
     */
    fun reset() {
        currentLevel = 0f
        targetLevel = 0f
        peakLevel = 0f
        peakHoldLevel = 0f
        peakHoldTime = 0L
        isClipping = false
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
        val cornerRadius = h / 2
        
        // Draw background
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Draw level bar
        val levelWidth = (w * currentLevel.coerceIn(0f, 1f))
        if (levelWidth > 0) {
            levelRect.set(0f, 0f, levelWidth, h)
            canvas.drawRoundRect(levelRect, cornerRadius, cornerRadius, levelPaint)
        }
        
        // Draw peak indicator
        if (peakLevel > 0.01f) {
            val peakX = (w * peakLevel.coerceIn(0f, 0.98f))
            peakPaint.color = if (isClipping) Color.RED else Color.WHITE
            canvas.drawRect(peakX - 1f, 0f, peakX + 1f, h, peakPaint)
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (8 * resources.displayMetrics.density).toInt()
        
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
