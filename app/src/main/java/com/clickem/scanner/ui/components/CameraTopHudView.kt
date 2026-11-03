package com.clickem.scanner.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.clickem.scanner.R
import kotlin.math.abs

class CameraTopHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    var onTorchToggled: ((Boolean) -> Unit)? = null
    var onQrModeToggled: ((Boolean) -> Unit)? = null
    var onRatioToggled: ((String) -> Unit)? = null
    var onGridToggled: ((Boolean) -> Unit)? = null
    var onSettingsClicked: (() -> Unit)? = null
    var onLoadoutClicked: (() -> Unit)? = null

    private var isTorchOn = false
    private var isQrOn = false
    private var isGridOn = false
    private var currentRatio = "4:3"

    private val btnTorch: ImageView
    private val btnQr: ImageView
    private val btnRatio: TextView
    private val horizonView: HorizonLevelIndicatorView
    private val btnGrid: ImageView
    private val btnSettings: ImageView
    private val btnLoadout: ImageView

    private fun getMinimumSafeTopInset(): Int = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val hP = (10 * density).toInt()

        val initialTop = getMinimumSafeTopInset() + (6 * density).toInt()
        setPadding(hP, initialTop, hP, (6 * density).toInt())

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val statusBarInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val topInset = maxOf(statusBarInsets.top, getMinimumSafeTopInset())
            val padH = (10 * density).toInt()
            v.setPadding(padH, topInset + (6 * density).toInt(), padH, (6 * density).toInt())
            insets
        }

        background = null
        elevation = 0f

        val controlsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        fun wrapCenteredSlot(child: View): FrameLayout {
            return FrameLayout(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                val lp = (child.layoutParams as? LayoutParams)?.let {
                    FrameLayout.LayoutParams(it.width, it.height).apply {
                        gravity = Gravity.CENTER
                    }
                } ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                child.layoutParams = lp
                addView(child)
            }
        }

        btnTorch = createIconButton(R.drawable.ic_flash_off).apply {
            contentDescription = "Flashlight"
        }
        styleToggleButton(btnTorch, false)
        btnTorch.setOnClickListener { v ->
            isTorchOn = !isTorchOn
            val iv = v as ImageView
            iv.setImageResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            styleToggleButton(iv, isTorchOn)
            v.contentDescription = if (isTorchOn) "Flashlight on" else "Flashlight off"
            onTorchToggled?.invoke(isTorchOn)
        }
        controlsRow.addView(wrapCenteredSlot(btnTorch))

        val initialRatio = try {
            com.clickem.scanner.util.settings.SettingsManager(context).cameraAspectRatio
        } catch (_: Exception) {
            "4:3"
        }
        currentRatio = initialRatio

        btnRatio = TextView(context).apply {
            text = initialRatio
            contentDescription = "Aspect ratio $initialRatio"
            textSize = 11.5f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            minimumWidth = (48 * density).toInt()
            minimumHeight = (48 * density).toInt()
            val padX = (10 * density).toInt()
            val padY = (4 * density).toInt()
            setPadding(padX, padY, padX, padY)
            val pillBg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#B30A0D14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            background = pillBg
            layoutParams = LayoutParams((48 * density).toInt(), (48 * density).toInt())
            setOnClickListener {
                currentRatio = when (currentRatio) {
                    "4:3" -> "16:9"
                    "16:9" -> "1:1"
                    "1:1" -> "FULL"
                    else -> "4:3"
                }
                text = currentRatio
                contentDescription = "Aspect ratio $currentRatio"
                onRatioToggled?.invoke(currentRatio)
            }
        }
        controlsRow.addView(wrapCenteredSlot(btnRatio))

        btnGrid = createIconButton(R.drawable.ic_grid).apply {
            contentDescription = "Camera Grid"
        }
        styleToggleButton(btnGrid, false)
        btnGrid.setOnClickListener { v ->
            isGridOn = !isGridOn
            styleToggleButton(v as ImageView, isGridOn)
            v.contentDescription = if (isGridOn) "Camera Grid enabled" else "Camera Grid disabled"
            onGridToggled?.invoke(isGridOn)
        }
        controlsRow.addView(wrapCenteredSlot(btnGrid))

        btnQr = createIconButton(R.drawable.ic_qr_scan).apply {
            contentDescription = "QR Scanner"
        }
        styleToggleButton(btnQr, false)
        btnQr.setOnClickListener { v ->
            isQrOn = !isQrOn
            styleToggleButton(v as ImageView, isQrOn)
            v.contentDescription = if (isQrOn) "QR Scanner enabled" else "QR Scanner disabled"
            onQrModeToggled?.invoke(isQrOn)
        }
        controlsRow.addView(wrapCenteredSlot(btnQr))

        btnLoadout = createIconButton(R.drawable.ic_loadout).apply {
            contentDescription = "Preset Loadouts"
        }
        styleActionIconButton(btnLoadout)
        btnLoadout.setOnClickListener {
            onLoadoutClicked?.invoke()
        }
        controlsRow.addView(wrapCenteredSlot(btnLoadout))

        btnSettings = createIconButton(R.drawable.ic_settings).apply {
            contentDescription = "Camera Settings"
        }
        styleActionIconButton(btnSettings)
        btnSettings.setOnClickListener {
            onSettingsClicked?.invoke()
        }
        controlsRow.addView(wrapCenteredSlot(btnSettings))

        addView(controlsRow)

        // Dedicated Centered Horizon Level Indicator Strip
        horizonView = HorizonLevelIndicatorView(context).apply {
            layoutParams = LayoutParams((132 * density).toInt(), (14 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (4 * density).toInt()
            }
        }
        addView(horizonView)
    }

    private fun createIconButton(iconRes: Int): ImageView {
        return ImageView(context).apply {
            setImageResource(iconRes)
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#FFFFFF"))
        }
    }

    private fun styleToggleButton(button: ImageView, isActive: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isActive) {
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#FFFFFF"))
            } else {
                setColor(Color.parseColor("#B30A0D14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
        }
        button.setColorFilter(Color.parseColor(if (isActive) "#090B10" else "#FFFFFF"))
    }

    private fun styleActionIconButton(button: ImageView) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#B30A0D14"))
            setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
        }
        button.setColorFilter(Color.parseColor("#FFFFFF"))
    }

    fun updateHorizon(ax: Float, ay: Float) {
        horizonView.updateAngles(ax, ay)
    }

    fun setRatioDisplay(ratio: String) {
        currentRatio = ratio
        btnRatio.text = ratio
        btnRatio.contentDescription = "Aspect ratio $ratio"
    }

    fun setQrModeDisplay(enabled: Boolean) {
        isQrOn = enabled
        styleToggleButton(btnQr, isQrOn)
        btnQr.contentDescription = if (isQrOn) "QR Scanner enabled" else "QR Scanner disabled"
    }

    fun setControlsRotation(rotationDegrees: Float) {
        val duration = 220L
        listOf(btnTorch, btnRatio, btnGrid, btnQr, btnLoadout, btnSettings).forEach { view ->
            view.animate().rotation(rotationDegrees).setDuration(duration).start()
        }
    }

    /**
     * Minimal custom view drawing a delicate illuminated Monochrome Frost horizon leveling bar.
     */
    class HorizonLevelIndicatorView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var rollAngle = 0f
        private var isLevel = false

        private val refTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = Color.parseColor("#40FFFFFF")
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }

        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        @Suppress("UNUSED_PARAMETER")
        fun updateAngles(ax: Float, ay: Float) {
            rollAngle = (ax * 5f).coerceIn(-25f, 25f)
            isLevel = abs(rollAngle) < 1.8f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val halfW = 24f * density

            // Outer reference ticks
            canvas.drawLine(cx - halfW - 8f * density, cy, cx - halfW - 3f * density, cy, refTickPaint)
            canvas.drawLine(cx + halfW + 3f * density, cy, cx + halfW + 8f * density, cy, refTickPaint)

            val color = if (isLevel) Color.parseColor("#FFFFFF") else Color.parseColor("#75FFFFFF")
            linePaint.color = color
            centerDotPaint.color = color

            canvas.save()
            canvas.rotate(-rollAngle, cx, cy)
            canvas.drawLine(cx - halfW, cy, cx + halfW, cy, linePaint)
            canvas.drawCircle(cx, cy, 2.5f * density, centerDotPaint)
            canvas.restore()
        }
    }
}
