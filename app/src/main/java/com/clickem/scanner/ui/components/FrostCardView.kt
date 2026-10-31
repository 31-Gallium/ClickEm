package com.clickem.scanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Clean, modern Monochrome Frost Card.
 * Provides a translucent dark charcoal surface with a subtle, elegant static frost border.
 * Heavy/distracting rotating glow outlines and ambient halos have been completely removed.
 */
class FrostCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Maintained for backward compatibility (no-op since glowing animation is removed)
    var isGlowActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    var cornerRadius: Float = 20f * density
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#12151D")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#25FFFFFF")
    }

    private val cardRect = RectF()

    init {
        setWillNotDraw(false)
        val pad = (16 * density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeInset = 0.5f * density
        cardRect.set(strokeInset, strokeInset, w.toFloat() - strokeInset, h.toFloat() - strokeInset)
        if (w > 0 && h > 0) {
            bgPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#E6161A24"),
                Color.parseColor("#D90E1118"),
                android.graphics.Shader.TileMode.CLAMP
            )
            borderPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#45FFFFFF"),
                Color.parseColor("#18FFFFFF"),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Sleek frosted obsidian/charcoal background with translucent depth
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)

        // 2. Crisp, elegant specular frost rim (light catching top edge)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)
    }

    companion object {
        // Preserved metadata array for theme verification (dynamic glow disabled per user feedback)
        val colors = intArrayOf(
            Color.parseColor("#090B10"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#090B10")
        )
        val positions = floatArrayOf(0.0f, 0.50f, 1.0f)
    }
}
