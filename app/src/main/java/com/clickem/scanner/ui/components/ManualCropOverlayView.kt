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
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ManualCropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Visible image boundaries on screen (accounting for ImageView.ScaleType.FIT_CENTER)
    var imageVisibleRect: RectF = RectF()
        set(value) {
            field = value
            invalidate()
        }

    // Normalized quad corners [TL, TR, BR, BL] in range [0..1] relative to imageVisibleRect
    var quadCorners: MutableList<PointF> = mutableListOf(
        PointF(0.08f, 0.08f),
        PointF(0.92f, 0.08f),
        PointF(0.92f, 0.92f),
        PointF(0.08f, 0.92f)
    )
        set(value) {
            field = value
            invalidate()
        }

    // Callback on handle drag: (handleIndex, screenTouchX, screenTouchY, isTouching, normX, normY)
    var onHandleTouchUpdate: ((handleIdx: Int, screenX: Float, screenY: Float, isTouching: Boolean, normX: Float, normY: Float) -> Unit)? = null

    private var activeHandleIndex = -1 // 0..3: corners, 4..7: edge midpoints (Top, Right, Bottom, Left)
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val pinRadius = 13f * density
    private val touchRadius = 36f * density
    private val edgePillW = 20f * density
    private val edgePillH = 6f * density

    // Crop masking alpha: 1.0 (completely hidden by default), fades to 0.38 during user drag
    private var maskAlpha = 1.0f
    private var maskAnimator: ValueAnimator? = null

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#090b10")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.parseColor("#FFFFFF")
    }

    private val gridGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#30FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val handleOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val handleInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#090B10")
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#15FFFFFF")
    }

    private val path = Path()

    fun resetToFull() {
        quadCorners = mutableListOf(
            PointF(0.02f, 0.02f),
            PointF(0.98f, 0.02f),
            PointF(0.98f, 0.98f),
            PointF(0.02f, 0.98f)
        )
        invalidate()
    }

    fun setQuadFromNormalized(quad: List<PointF>) {
        if (quad.size == 4) {
            quadCorners = quad.map { PointF(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }.toMutableList()
            invalidate()
        }
    }

    private fun animateMaskAlpha(targetAlpha: Float) {
        maskAnimator?.cancel()
        maskAnimator = ValueAnimator.ofFloat(maskAlpha, targetAlpha).apply {
            duration = 160L
            addUpdateListener { anim ->
                maskAlpha = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun getScreenPoints(): Array<PointF> {
        val iRect = if (imageVisibleRect.width() > 0) imageVisibleRect else RectF(0f, 0f, width.toFloat(), height.toFloat())
        return arrayOf(
            PointF(iRect.left + quadCorners[0].x * iRect.width(), iRect.top + quadCorners[0].y * iRect.height()),
            PointF(iRect.left + quadCorners[1].x * iRect.width(), iRect.top + quadCorners[1].y * iRect.height()),
            PointF(iRect.left + quadCorners[2].x * iRect.width(), iRect.top + quadCorners[2].y * iRect.height()),
            PointF(iRect.left + quadCorners[3].x * iRect.width(), iRect.top + quadCorners[3].y * iRect.height())
        )
    }

    private fun getEdgeMidpoints(pts: Array<PointF>): Array<PointF> {
        return arrayOf(
            PointF((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f), // Top (4)
            PointF((pts[1].x + pts[2].x) / 2f, (pts[1].y + pts[2].y) / 2f), // Right (5)
            PointF((pts[2].x + pts[3].x) / 2f, (pts[2].y + pts[3].y) / 2f), // Bottom (6)
            PointF((pts[3].x + pts[0].x) / 2f, (pts[3].y + pts[0].y) / 2f)  // Left (7)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (quadCorners.size != 4) return super.onTouchEvent(event)

        val iRect = if (imageVisibleRect.width() > 0) imageVisibleRect else RectF(0f, 0f, width.toFloat(), height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pts = getScreenPoints()
                val midPts = getEdgeMidpoints(pts)

                var foundIdx = -1
                var minDist = touchRadius

                for (i in pts.indices) {
                    val d = hypot((event.x - pts[i].x).toDouble(), (event.y - pts[i].y).toDouble()).toFloat()
                    if (d < minDist) {
                        minDist = d
                        foundIdx = i
                    }
                }

                if (foundIdx == -1) {
                    for (i in midPts.indices) {
                        val d = hypot((event.x - midPts[i].x).toDouble(), (event.y - midPts[i].y).toDouble()).toFloat()
                        if (d < minDist) {
                            minDist = d
                            foundIdx = 4 + i
                        }
                    }
                }

                if (foundIdx != -1) {
                    activeHandleIndex = foundIdx
                    lastTouchX = event.x
                    lastTouchY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)

                    // Reveal cropped-out context during drag
                    animateMaskAlpha(0.38f)

                    val normX = ((event.x - iRect.left) / iRect.width()).coerceIn(0f, 1f)
                    val normY = ((event.y - iRect.top) / iRect.height()).coerceIn(0f, 1f)
                    onHandleTouchUpdate?.invoke(activeHandleIndex, event.x, event.y, true, normX, normY)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeHandleIndex != -1) {
                    val deltaXNorm = (event.x - lastTouchX) / iRect.width()
                    val deltaYNorm = (event.y - lastTouchY) / iRect.height()

                    if (activeHandleIndex in 0..3) {
                        val normX = ((event.x - iRect.left) / iRect.width()).coerceIn(0f, 1f)
                        val normY = ((event.y - iRect.top) / iRect.height()).coerceIn(0f, 1f)
                        quadCorners[activeHandleIndex].set(normX, normY)
                        onHandleTouchUpdate?.invoke(activeHandleIndex, event.x, event.y, true, normX, normY)
                    } else {
                        when (activeHandleIndex) {
                            4 -> { // Top edge
                                val newY0 = (quadCorners[0].y + deltaYNorm).coerceIn(0f, quadCorners[3].y - 0.05f)
                                val newY1 = (quadCorners[1].y + deltaYNorm).coerceIn(0f, quadCorners[2].y - 0.05f)
                                quadCorners[0].y = newY0
                                quadCorners[1].y = newY1
                            }
                            5 -> { // Right edge
                                val newX1 = (quadCorners[1].x + deltaXNorm).coerceIn(quadCorners[0].x + 0.05f, 1f)
                                val newX2 = (quadCorners[2].x + deltaXNorm).coerceIn(quadCorners[3].x + 0.05f, 1f)
                                quadCorners[1].x = newX1
                                quadCorners[2].x = newX2
                            }
                            6 -> { // Bottom edge
                                val newY2 = (quadCorners[2].y + deltaYNorm).coerceIn(quadCorners[1].y + 0.05f, 1f)
                                val newY3 = (quadCorners[3].y + deltaYNorm).coerceIn(quadCorners[0].y + 0.05f, 1f)
                                quadCorners[2].y = newY2
                                quadCorners[3].y = newY3
                            }
                            7 -> { // Left edge
                                val newX0 = (quadCorners[0].x + deltaXNorm).coerceIn(0f, quadCorners[1].x - 0.05f)
                                val newX3 = (quadCorners[3].x + deltaXNorm).coerceIn(0f, quadCorners[2].x - 0.05f)
                                quadCorners[0].x = newX0
                                quadCorners[3].x = newX3
                            }
                        }
                        val normX = ((event.x - iRect.left) / iRect.width()).coerceIn(0f, 1f)
                        val normY = ((event.y - iRect.top) / iRect.height()).coerceIn(0f, 1f)
                        onHandleTouchUpdate?.invoke(activeHandleIndex, event.x, event.y, true, normX, normY)
                    }

                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandleIndex != -1) {
                    val normX = ((event.x - iRect.left) / iRect.width()).coerceIn(0f, 1f)
                    val normY = ((event.y - iRect.top) / iRect.height()).coerceIn(0f, 1f)
                    onHandleTouchUpdate?.invoke(activeHandleIndex, event.x, event.y, false, normX, normY)
                    activeHandleIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)

                    // Hide cropped-out context again with smooth fade
                    animateMaskAlpha(1.0f)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || quadCorners.size != 4) return

        val pts = getScreenPoints()
        val midPts = getEdgeMidpoints(pts)

        // 1. Draw outer masking scrim (hides cropped-out area by default, reveals on touch)
        path.reset()
        path.moveTo(pts[0].x, pts[0].y)
        path.lineTo(pts[1].x, pts[1].y)
        path.lineTo(pts[2].x, pts[2].y)
        path.lineTo(pts[3].x, pts[3].y)
        path.close()

        canvas.save()
        canvas.clipOutPath(path)
        scrimPaint.alpha = (maskAlpha * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.restore()

        // 2. Draw quadrilateral contour
        canvas.drawPath(path, borderPaint)

        // 3. Draw Rule of Thirds grid guidelines inside crop area
        for (i in 1..2) {
            val frac = i / 3f
            val leftH = PointF(pts[0].x + (pts[3].x - pts[0].x) * frac, pts[0].y + (pts[3].y - pts[0].y) * frac)
            val rightH = PointF(pts[1].x + (pts[2].x - pts[1].x) * frac, pts[1].y + (pts[2].y - pts[1].y) * frac)
            canvas.drawLine(leftH.x, leftH.y, rightH.x, rightH.y, gridGuidePaint)

            val topV = PointF(pts[0].x + (pts[1].x - pts[0].x) * frac, pts[0].y + (pts[1].y - pts[0].y) * frac)
            val botV = PointF(pts[3].x + (pts[2].x - pts[3].x) * frac, pts[3].y + (pts[2].y - pts[3].y) * frac)
            canvas.drawLine(topV.x, topV.y, botV.x, botV.y, gridGuidePaint)
        }

        // 4. Draw 4 Edge Midpoint Handles (pills)
        for (i in midPts.indices) {
            val mid = midPts[i]
            val isHoriz = (i == 0 || i == 2)
            val pw = if (isHoriz) edgePillW else edgePillH
            val ph = if (isHoriz) edgePillH else edgePillW
            val r = RectF(mid.x - pw / 2f, mid.y - ph / 2f, mid.x + pw / 2f, mid.y + ph / 2f)
            canvas.drawRoundRect(r, 3f * density, 3f * density, handleOuterPaint)
        }

        // 5. Draw 4 Corner Handles (Pins)
        for (i in pts.indices) {
            val pt = pts[i]
            val isActive = (i == activeHandleIndex)
            val currentRadius = if (isActive) pinRadius * 1.3f else pinRadius

            canvas.drawCircle(pt.x, pt.y, currentRadius + 6f * density, haloPaint)
            canvas.drawCircle(pt.x, pt.y, currentRadius, handleOuterPaint)
            canvas.drawCircle(pt.x, pt.y, currentRadius * 0.45f, handleInnerPaint)
        }
    }
}
