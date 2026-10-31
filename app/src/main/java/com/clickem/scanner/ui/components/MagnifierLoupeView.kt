package com.clickem.scanner.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class MagnifierLoupeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    val loupeRadius = 65f * density // 130dp diameter for clear precision inspection
    private val zoomFactor = 3.2f

    private var targetBitmap: Bitmap? = null
    private var sampleNormX = 0f
    private var sampleNormY = 0f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#FFFFFF")
    }

    private val outerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = Color.parseColor("#15FFFFFF")
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.parseColor("#30FFFFFF")
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#090B10")
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()

    fun setSourceBitmap(bitmap: Bitmap?) {
        this.targetBitmap = bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bitmapPaint.shader = shader
        } else {
            bitmapPaint.shader = null
        }
        invalidate()
    }

    /**
     * Updates loupe location on screen and accurate pixel sample coordinates.
     * Prevents clipping on all 4 display borders and flips above/below finger dynamically.
     */
    fun updatePosition(
        screenTouchX: Float,
        screenTouchY: Float,
        normX: Float,
        normY: Float,
        parentWidth: Int,
        parentHeight: Int
    ) {
        this.sampleNormX = normX.coerceIn(0f, 1f)
        this.sampleNormY = normY.coerceIn(0f, 1f)

        val diameter = loupeRadius * 2
        val margin = 12f * density
        val fingerOffset = 76f * density

        // 1. Clamp horizontal coordinate so loupe never clips screen left/right
        val targetX = (screenTouchX - loupeRadius).coerceIn(margin, parentWidth - diameter - margin)

        // 2. Flip vertically: If finger is in top 35% of screen, place loupe BELOW finger
        val targetY = if (screenTouchY < (parentHeight * 0.35f)) {
            (screenTouchY + fingerOffset).coerceAtMost(parentHeight - diameter - margin)
        } else {
            (screenTouchY - diameter - fingerOffset).coerceAtLeast(margin)
        }

        x = targetX
        y = targetY
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = targetBitmap
        if (bmp == null || bmp.isRecycled || bitmapPaint.shader == null) return

        val cx = width / 2f
        val cy = height / 2f

        val bmpSampleX = sampleNormX * bmp.width
        val bmpSampleY = sampleNormY * bmp.height

        // Transform shader: center sample point and scale by zoomFactor
        shaderMatrix.reset()
        shaderMatrix.postTranslate(-bmpSampleX, -bmpSampleY)
        shaderMatrix.postScale(zoomFactor, zoomFactor)
        shaderMatrix.postTranslate(cx, cy)
        bitmapPaint.shader?.setLocalMatrix(shaderMatrix)

        // 1. Draw magnified circular viewport
        canvas.drawCircle(cx, cy, loupeRadius, bitmapPaint)

        // 2. Draw high-precision crosshair
        val arm = 18f * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
        canvas.drawCircle(cx, cy, 2.5f * density, centerDotPaint)

        // 3. Ambient frost glow & border ring
        canvas.drawCircle(cx, cy, loupeRadius, outerHaloPaint)
        canvas.drawCircle(cx, cy, loupeRadius, borderPaint)
    }
}
