package com.clickem.scanner.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

class GridBackgroundView(context: Context) : View(context) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
        color = Color.parseColor("#FFFFFF")
    }

    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val density = context.resources.displayMetrics.density
    private val gridGap = 28f * density
    private val baseRadius = 1.1f * density
    private val highlightRadius = 2.8f * density
    private val baseInfluenceRadius = 140f * density
    private val repelForce = 16f * density

    // Touch & Hold Tracking
    private var touchX = -9999f
    private var touchY = -9999f
    private var touchTargetX = -9999f
    private var touchTargetY = -9999f
    private var touchIntensity = 0f
    private var isTouching = false
    private var touchDownTime = 0L
    private var holdCharge = 0f

    // Shockwave Ripple Pool for Taps & Releases
    private class Ripple(
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 0f,
        var maxRadius: Float = 0f,
        var alpha: Float = 1f,
        var speed: Float = 0f
    )

    private val activeRipples = mutableListOf<Ripple>()
    private val ripplePool = mutableListOf<Ripple>()

    private var startTime = SystemClock.elapsedRealtime()
    private var lastFrameTime = SystemClock.elapsedRealtime()
    private var animator: ValueAnimator? = null

    init {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                invalidate()
            }
        }
    }

    fun handleGlobalTouch(event: MotionEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTargetX = event.x
                touchTargetY = event.y
                touchX = event.x
                touchY = event.y
                isTouching = true
                touchDownTime = now
                holdCharge = 0f
                spawnRipple(event.x, event.y, 160f * density, 420f * density)
            }
            MotionEvent.ACTION_MOVE -> {
                touchTargetX = event.x
                touchTargetY = event.y
                isTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (holdCharge > 0.3f) {
                    spawnRipple(touchX, touchY, (200f + holdCharge * 150f) * density, 550f * density)
                }
                isTouching = false
                holdCharge = 0f
            }
        }
    }

    private fun spawnRipple(x: Float, y: Float, maxR: Float, speed: Float) {
        val ripple = if (ripplePool.isNotEmpty()) ripplePool.removeAt(ripplePool.size - 1) else Ripple()
        ripple.x = x
        ripple.y = y
        ripple.radius = 0f
        ripple.maxRadius = maxR
        ripple.alpha = 1f
        ripple.speed = speed
        activeRipples.add(ripple)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.elapsedRealtime()
        lastFrameTime = startTime
        animator?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            if (animator?.isRunning != true) animator?.start()
        } else {
            animator?.cancel()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val radial = RadialGradient(
                w / 2f, h * 0.15f, w * 0.85f,
                intArrayOf(Color.parseColor("#15FFFFFF"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            ambientPaint.shader = radial
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrameTime = now

        val timeSec = (now - startTime) * 0.001f

        // 1. Ambient soft radial glow in upper background
        canvas.drawRect(0f, 0f, w, h, ambientPaint)

        // 2. Update Touch & Hold Dynamics
        if (isTouching) {
            touchX += (touchTargetX - touchX) * (1f - Math.exp((-18.0 * dt).toDouble()).toFloat())
            touchY += (touchTargetY - touchY) * (1f - Math.exp((-18.0 * dt).toDouble()).toFloat())
            touchIntensity += (1f - touchIntensity) * (1f - Math.exp((-12.0 * dt).toDouble()).toFloat())

            val holdTime = (now - touchDownTime) / 1000f
            if (holdTime > 0.2f) {
                holdCharge = ((holdTime - 0.2f) / 1.0f).coerceIn(0f, 1f)
            }
        } else {
            touchIntensity += (0f - touchIntensity) * (1f - Math.exp((-8.0 * dt).toDouble()).toFloat())
            holdCharge = 0f
        }

        // 3. Update & Draw Active Ripples
        var i = activeRipples.size - 1
        while (i >= 0) {
            val r = activeRipples[i]
            r.radius += r.speed * dt
            r.alpha = (1f - (r.radius / r.maxRadius)).coerceIn(0f, 1f)

            if (r.radius >= r.maxRadius || r.alpha <= 0f) {
                activeRipples.removeAt(i)
                ripplePool.add(r)
            } else {
                glowRingPaint.alpha = (r.alpha * 120).toInt()
                canvas.drawCircle(r.x, r.y, r.radius, glowRingPaint)
            }
            i--
        }

        val cols = (w / gridGap).toInt() + 2
        val rows = (h / gridGap).toInt() + 2
        val offsetX = (w % gridGap) / 2f - gridGap
        val offsetY = (h % gridGap) / 2f - gridGap

        val flowScale = 1.6f * density
        val currentInfluenceRadius = baseInfluenceRadius * (1f + holdCharge * 0.5f + (if (holdCharge > 0f) Math.sin(timeSec * 8.0).toFloat() * 0.08f else 0f))
        val currentRepelForce = repelForce * (1f + holdCharge * 0.7f)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val originX = offsetX + c * gridGap
                val originY = offsetY + r * gridGap

                val wave1 = Math.sin((c * 0.2 + r * 0.15 + timeSec * 1.0).toDouble()).toFloat()
                val flowX = (Math.sin((timeSec * 0.7 + r * 0.25).toDouble()) * flowScale).toFloat()
                val flowY = (Math.cos((timeSec * 0.7 + c * 0.25).toDouble()) * flowScale).toFloat()

                var posX = originX + flowX
                var posY = originY + flowY

                var alpha = 0.06f + (wave1 * 0.5f + 0.5f) * 0.08f
                var dotRadius = baseRadius
                var red = 226
                var green = 232
                var blue = 240

                if (touchIntensity > 0.01f) {
                    val dx = posX - touchX
                    val dy = posY - touchY
                    val distSq = dx * dx + dy * dy

                    if (distSq < currentInfluenceRadius * currentInfluenceRadius) {
                        val dist = Math.sqrt(distSq.toDouble()).toFloat()
                        val norm = (1f - (dist / currentInfluenceRadius)) * touchIntensity
                        val ease = norm * norm * (3f - 2f * norm)

                        val force = (1f - (dist / currentInfluenceRadius)) * currentRepelForce * touchIntensity
                        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
                        val vortexAngle = angle + (holdCharge * 0.4f * (1f - dist / currentInfluenceRadius))

                        posX += (Math.cos(vortexAngle) * force).toFloat()
                        posY += (Math.sin(vortexAngle) * force).toFloat()

                        alpha = Math.min(0.95f, alpha + ease * (0.65f + holdCharge * 0.3f))
                        dotRadius = baseRadius + ease * (highlightRadius - baseRadius + holdCharge * 1.2f * density)

                        val frostEase = ease.coerceIn(0f, 1f)

                        red = (226 + (255 - 226) * frostEase).toInt().coerceIn(0, 255)
                        green = (232 + (255 - 232) * frostEase).toInt().coerceIn(0, 255)
                        blue = (240 + (255 - 240) * frostEase).toInt().coerceIn(0, 255)
                    }
                }

                for (rippleIdx in activeRipples.indices) {
                    val rip = activeRipples[rippleIdx]
                    val rx = posX - rip.x
                    val ry = posY - rip.y
                    val rDist = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat()
                    val distFromWave = Math.abs(rDist - rip.radius)
                    val waveThickness = 28f * density

                    if (distFromWave < waveThickness) {
                        val wavePower = (1f - (distFromWave / waveThickness)) * rip.alpha
                        alpha = Math.min(0.95f, alpha + wavePower * 0.5f)
                        dotRadius = Math.max(dotRadius, baseRadius + wavePower * 1.6f * density)
                        red = (red + (255 - red) * wavePower).toInt().coerceIn(0, 255)
                        green = (green + (255 - green) * wavePower).toInt().coerceIn(0, 255)
                        blue = (blue + (255 - blue) * wavePower).toInt().coerceIn(0, 255)
                    }
                }

                dotPaint.color = Color.argb((alpha * 255).toInt(), red, green, blue)
                canvas.drawCircle(posX, posY, dotRadius, dotPaint)
            }
        }
    }
}
