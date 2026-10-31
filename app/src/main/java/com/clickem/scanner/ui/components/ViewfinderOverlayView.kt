package com.clickem.scanner.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.clickem.scanner.util.cv.BoundaryDetector
import kotlin.math.max
import kotlin.math.min

class ViewfinderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    var isQrMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var activeAspectRatio: String = "4:3" // "4:3", "16:9", "1:1"
        set(value) {
            field = value
            invalidate()
        }

    var isDocumentDetected: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isBoundaryLocked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateColorPalette()
                invalidate()
            }
        }

    var showGrid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Normalized quad corners [TL, TR, BR, BL] in range [0..1]
    var quadCorners: List<PointF> = listOf(
        PointF(0.12f, 0.20f),
        PointF(0.88f, 0.18f),
        PointF(0.92f, 0.82f),
        PointF(0.08f, 0.80f)
    )
        set(value) {
            field = value
            invalidate()
        }

    fun updateDetectedQuad(newQuad: List<PointF>) {
        if (newQuad.size == 4) {
            quadCorners = BoundaryDetector.smoothQuad(newQuad, quadCorners, alpha = 0.40f)
        }
    }

    private val boundaryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#FFFFFF")
    }

    private val boundaryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#15FFFFFF")
    }

    private val cornerPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val cornerPinCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#090B10")
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#FFFFFF")
    }

    private val qrFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#FFFFFF")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = Color.parseColor("#f8fafc")
        textAlign = Paint.Align.CENTER
    }

    private val gridShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * density
        color = Color.parseColor("#70090B10")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.parseColor("#A6FFFFFF")
    }

    private val gridIntersectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        color = Color.parseColor("#E6FFFFFF")
    }

    private var scanProgress: Float = 0f
    private var scanAnimator: ValueAnimator? = null
    private val path = Path()

    init {
        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                scanProgress = anim.animatedValue as Float
                if (isQrMode) {
                    invalidate()
                }
            }
            start()
        }
    }

    private fun updateColorPalette() {
        val strokeColor = Color.parseColor("#FFFFFF")
        val fillColor = if (isBoundaryLocked) Color.parseColor("#1EFFFFFF") else Color.parseColor("#12FFFFFF")

        boundaryStrokePaint.color = strokeColor
        boundaryFillPaint.color = fillColor
        cornerPinPaint.color = strokeColor
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scanAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        drawAspectRatioFraming(canvas, w, h)

        if (showGrid) {
            drawAlignmentGrid(canvas, w, h)
        }

        if (isQrMode) {
            drawQrReticle(canvas, w, h)
            return
        }

        if (isDocumentDetected && quadCorners.size == 4) {
            drawDocumentQuad(canvas, w, h)
        }
    }

    var topPanelInset: Float = 86f * density
        set(value) {
            field = value
            invalidate()
        }

    var bottomPanelInset: Float = 156f * density
        set(value) {
            field = value
            invalidate()
        }

    private val pitchBlackCutoffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.parseColor("#35FFFFFF")
    }

    private val framingScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    fun getActiveViewportRect(w: Float, h: Float): RectF {
        val safeTop = topPanelInset.coerceIn(0f, h * 0.35f)
        val safeBottom = (h - bottomPanelInset).coerceIn(safeTop + 100f, h)
        val availH = safeBottom - safeTop

        return when (activeAspectRatio) {
            "1:1" -> {
                val side = min(w, availH)
                val left = (w - side) / 2f
                val top = safeTop + (availH - side) / 2f
                RectF(left, top, left + side, top + side)
            }
            "4:3" -> {
                val targetH = w * (4f / 3f)
                val left = 0f
                val top = if (targetH <= availH) {
                    safeTop + (availH - targetH) / 2f
                } else {
                    ((h - targetH) / 2f).coerceAtLeast(0f)
                }
                RectF(left, top, left + w, top + targetH)
            }
            "16:9" -> {
                val targetH = w * (16f / 9f)
                if (targetH <= h) {
                    val targetW = w
                    val left = 0f
                    val top = ((h - targetH) / 2f).coerceAtLeast(0f)
                    RectF(left, top, left + targetW, top + targetH)
                } else {
                    val targetW = h * (9f / 16f)
                    val left = (w - targetW) / 2f
                    RectF(left, 0f, left + targetW, h)
                }
            }
            "FULL" -> {
                RectF(0f, 0f, w, h)
            }
            else -> {
                RectF(0f, safeTop, w, safeBottom)
            }
        }
    }

    private fun drawAspectRatioFraming(canvas: Canvas, w: Float, h: Float) {
        if (activeAspectRatio != "FULL") {
            val r = getActiveViewportRect(w, h)
            // Draw pure pitch black cutoff panels outside the active viewport
            if (r.top > 0f) {
                canvas.drawRect(0f, 0f, w, r.top, pitchBlackCutoffPaint)
            }
            if (r.bottom < h) {
                canvas.drawRect(0f, r.bottom, w, h, pitchBlackCutoffPaint)
            }
            if (r.left > 0f) {
                canvas.drawRect(0f, r.top, r.left, r.bottom, pitchBlackCutoffPaint)
            }
            if (r.right < w) {
                canvas.drawRect(r.right, r.top, w, r.bottom, pitchBlackCutoffPaint)
            }
            // Draw crisp specular frost rim around the active viewport
            canvas.drawRect(r.left, r.top, r.right, r.bottom, frameBorderPaint)
        }
    }

    private fun drawAlignmentGrid(canvas: Canvas, w: Float, h: Float) {
        val r = getActiveViewportRect(w, h)
        val colStep = r.width() / 3f
        val rowStep = r.height() / 3f

        val x1 = r.left + colStep
        val x2 = r.left + colStep * 2f
        val y1 = r.top + rowStep
        val y2 = r.top + rowStep * 2f

        // 1. Dark contrast backing lines so grid remains highly visible over bright white paper
        canvas.drawLine(x1, r.top, x1, r.bottom, gridShadowPaint)
        canvas.drawLine(x2, r.top, x2, r.bottom, gridShadowPaint)
        canvas.drawLine(r.left, y1, r.right, y1, gridShadowPaint)
        canvas.drawLine(r.left, y2, r.right, y2, gridShadowPaint)

        // 2. Crisp high-visibility frost white grid lines
        canvas.drawLine(x1, r.top, x1, r.bottom, gridPaint)
        canvas.drawLine(x2, r.top, x2, r.bottom, gridPaint)
        canvas.drawLine(r.left, y1, r.right, y1, gridPaint)
        canvas.drawLine(r.left, y2, r.right, y2, gridPaint)

        // 3. Rule-of-thirds intersection crosshair accents for precision alignment
        val crossArm = 6f * density
        val intersections = arrayOf(
            PointF(x1, y1),
            PointF(x2, y1),
            PointF(x1, y2),
            PointF(x2, y2)
        )
        for (pt in intersections) {
            canvas.drawLine(pt.x - crossArm, pt.y, pt.x + crossArm, pt.y, gridIntersectionPaint)
            canvas.drawLine(pt.x, pt.y - crossArm, pt.x, pt.y + crossArm, gridIntersectionPaint)
        }
    }

    private fun drawDocumentQuad(canvas: Canvas, w: Float, h: Float) {
        val r = getActiveViewportRect(w, h)
        path.reset()
        val p0 = PointF(r.left + quadCorners[0].x * r.width(), r.top + quadCorners[0].y * r.height())
        val p1 = PointF(r.left + quadCorners[1].x * r.width(), r.top + quadCorners[1].y * r.height())
        val p2 = PointF(r.left + quadCorners[2].x * r.width(), r.top + quadCorners[2].y * r.height())
        val p3 = PointF(r.left + quadCorners[3].x * r.width(), r.top + quadCorners[3].y * r.height())

        path.moveTo(p0.x, p0.y)
        path.lineTo(p1.x, p1.y)
        path.lineTo(p2.x, p2.y)
        path.lineTo(p3.x, p3.y)
        path.close()

        // 1. Shaded polygon surface
        canvas.drawPath(path, boundaryFillPaint)

        // 2. Boundary contour
        canvas.drawPath(path, boundaryStrokePaint)

        // 3. Clean concentric Monochrome Frost corner target nodes
        val pts = arrayOf(p0, p1, p2, p3)
        for (pt in pts) {
            canvas.drawCircle(pt.x, pt.y, 7.5f * density, cornerPinPaint)
            canvas.drawCircle(pt.x, pt.y, 3.2f * density, cornerPinCenterPaint)
        }
    }

    private fun drawQrReticle(canvas: Canvas, w: Float, h: Float) {
        val size = min(w, h) * 0.65f
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        val right = left + size
        val bottom = top + size

        val bracketLen = 32f * density

        // Corners
        canvas.drawLine(left, top, left + bracketLen, top, qrFramePaint)
        canvas.drawLine(left, top, left, top + bracketLen, qrFramePaint)
        canvas.drawLine(right, top, right - bracketLen, top, qrFramePaint)
        canvas.drawLine(right, top, right, top + bracketLen, qrFramePaint)
        canvas.drawLine(left, bottom, left + bracketLen, bottom, qrFramePaint)
        canvas.drawLine(left, bottom, left, bottom - bracketLen, qrFramePaint)
        canvas.drawLine(right, bottom, right - bracketLen, bottom, qrFramePaint)
        canvas.drawLine(right, bottom, right, bottom - bracketLen, qrFramePaint)

        // Scanning laser
        val scanY = top + (bottom - top) * scanProgress
        canvas.drawLine(left + (12f * density), scanY, right - (12f * density), scanY, scanLinePaint)

        canvas.drawText("ALIGN QR CODE WITHIN FRAME", w / 2f, bottom + (32f * density), textPaint)
    }
}
