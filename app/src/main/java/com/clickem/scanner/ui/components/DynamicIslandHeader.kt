package com.clickem.scanner.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.clickem.scanner.R

class DynamicIslandHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    var onTorchToggled: ((Boolean) -> Unit)? = null
    var onQrModeToggled: ((Boolean) -> Unit)? = null
    var onLoadoutClicked: (() -> Unit)? = null

    private var isTorchOn = false
    private var isQrMode = false

    private val torchBtn: ImageView
    private val qrBtn: ImageView
    private val levelText: TextView
    private val loadoutBtn: LinearLayout

    init {
        // Frosted Glass dynamic island shape
        background = GradientDrawable().apply {
            cornerRadius = 32f * density
            setColor(Color.parseColor("#e612141c"))
            setStroke((1.5f * density).toInt(), Color.parseColor("#30FFFFFF"))
        }
        elevation = 12f * density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            val hPad = (16 * density).toInt()
            val vPad = (8 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        addView(container)

        // Torch button
        torchBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_flash_off)
            contentDescription = "Toggle flashlight"
            val sz = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (12 * density).toInt()
            }
            setColorFilter(Color.parseColor("#8c96a5"))
            setOnClickListener {
                isTorchOn = !isTorchOn
                setImageResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
                setColorFilter(if (isTorchOn) Color.parseColor("#FFFFFF") else Color.parseColor("#8c96a5"))
                onTorchToggled?.invoke(isTorchOn)
            }
        }
        container.addView(torchBtn)

        // QR Mode Toggle button
        qrBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_qr_scan)
            contentDescription = "Toggle QR scanner"
            val sz = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (14 * density).toInt()
            }
            setColorFilter(Color.parseColor("#8c96a5"))
            setOnClickListener {
                isQrMode = !isQrMode
                setColorFilter(if (isQrMode) Color.parseColor("#FFFFFF") else Color.parseColor("#8c96a5"))
                onQrModeToggled?.invoke(isQrMode)
            }
        }
        container.addView(qrBtn)

        // Center Level & Gyro Indicator
        levelText = TextView(context).apply {
            text = "0.0° • LEVEL"
            textSize = 12f
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        container.addView(levelText)

        // Right Loadout Button (Previous Scans)
        loadoutBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            contentDescription = "Open loadout"
            val pX = (10 * density).toInt()
            val pY = (4 * density).toInt()
            setPadding(pX, pY, pX, pY)

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_loadout)
                val isz = (16 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(isz, isz).apply {
                    marginEnd = (4 * density).toInt()
                }
                setColorFilter(Color.parseColor("#FFFFFF"))
            }
            addView(icon)

            val label = TextView(context).apply {
                text = "Loadout"
                textSize = 12f
                setTextColor(Color.parseColor("#f8fafc"))
            }
            addView(label)

            setOnClickListener { onLoadoutClicked?.invoke() }
        }
        container.addView(loadoutBtn)
    }

    fun updateHorizon(pitch: Float, roll: Float) {
        val totalTilt = Math.sqrt((pitch * pitch + roll * roll).toDouble()).toFloat()
        val isLevel = totalTilt < 2.5f

        if (isLevel) {
            levelText.text = "0.0° • LEVEL"
            levelText.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            levelText.text = String.format("%.1f° • TILT", totalTilt)
            levelText.setTextColor(Color.parseColor("#8c96a5"))
        }
    }
}
