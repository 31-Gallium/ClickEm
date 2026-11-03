package com.clickem.scanner.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.clickem.scanner.R

class NavPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tabsLayout: LinearLayout
    private val liquidIndicator: View
    private val tabContainers = mutableListOf<LinearLayout>()
    private val tabIcons = mutableListOf<ImageView>()
    private val tabLabels = mutableListOf<android.widget.TextView>()

    var onTabSelected: ((Int) -> Unit)? = null
    private var selectedIndex = 0

    init {
        val density = context.resources.displayMetrics.density

        // Frosted Monochrome Slate glass dock (zero elevation to prevent dark rectangular shadow artifacts)
        background = GradientDrawable().apply {
            cornerRadius = 32f * density
            setColor(Color.parseColor("#F00E1118"))
            setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            setDither(true)
        }
        elevation = 0f

        tabsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            val hPad = (8 * density).toInt()
            val vPad = (8 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        addView(tabsLayout)

        liquidIndicator = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 3f * density
                setColor(Color.parseColor("#FFFFFF"))
            }
            elevation = 0f
            layoutParams = LayoutParams((28 * density).toInt(), (3.5f * density).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(0, 0, 0, (4 * density).toInt())
            }
        }
        addView(liquidIndicator)
    }

    fun setTabs(
        icons: List<Int>,
        initialIndex: Int = 0,
        descriptions: List<String>? = null
    ) {
        tabsLayout.removeAllViews()
        tabContainers.clear()
        tabIcons.clear()
        tabLabels.clear()
        val density = context.resources.displayMetrics.density

        for (i in icons.indices) {
            val iconRes = icons[i]
            val defaultDesc = when (iconRes) {
                R.drawable.ic_loadout -> "Loadout archive"
                R.drawable.ic_camera -> "Open camera scanner"
                R.drawable.ic_gallery -> "Scanned images gallery"
                else -> "Navigation tab ${i + 1}"
            }
            val shortLabel = when (iconRes) {
                R.drawable.ic_loadout -> "Archive"
                R.drawable.ic_camera -> "Scan"
                R.drawable.ic_gallery -> "Gallery"
                else -> "Tab ${i + 1}"
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val tabH = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, tabH, 1f).apply {
                    val m = (3 * density).toInt()
                    setMargins(m, 0, m, 0)
                }
                val padH = (10 * density).toInt()
                setPadding(padH, 0, padH, 0)
                minimumHeight = tabH
                contentDescription = descriptions?.getOrNull(i) ?: defaultDesc
                setOnClickListener { selectTab(i) }
            }

            val iconView = ImageView(context).apply {
                setImageResource(iconRes)
                val sz = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (6 * density).toInt()
                }
            }
            container.addView(iconView)

            val labelView = android.widget.TextView(context).apply {
                text = shortLabel
                textSize = 12f
                paint.isFakeBoldText = true
                setSingleLine(true)
            }
            container.addView(labelView)

            tabContainers.add(container)
            tabIcons.add(iconView)
            tabLabels.add(labelView)
            tabsLayout.addView(container)
        }

        post { selectTab(initialIndex, animate = false) }
    }

    fun selectTab(position: Int, animate: Boolean = true) {
        if (position !in tabIcons.indices) return
        selectedIndex = position
        val density = context.resources.displayMetrics.density

        for (i in tabIcons.indices) {
            val isSelected = (i == position)
            val tint = if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#94A3B8")
            tabIcons[i].imageTintList = ColorStateList.valueOf(tint)
            tabLabels[i].setTextColor(tint)
            tabContainers[i].background = GradientDrawable().apply {
                cornerRadius = 24f * density
                if (isSelected) {
                    setColor(Color.parseColor("#22FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#45FFFFFF"))
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
        }

        val targetTab = tabsLayout.getChildAt(position)
        targetTab?.post {
            val targetX = targetTab.left + (targetTab.width / 2f) - (liquidIndicator.width / 2f) + tabsLayout.left
            if (animate) {
                liquidIndicator.animate()
                    .translationX(targetX)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(0.9f))
                    .start()
            } else {
                liquidIndicator.translationX = targetX
            }
        }

        onTabSelected?.invoke(position)
    }

    fun getSelectedIndex(): Int = selectedIndex
}
