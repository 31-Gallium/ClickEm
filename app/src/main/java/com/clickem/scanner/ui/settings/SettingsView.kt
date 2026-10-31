package com.clickem.scanner.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.clickem.scanner.R
import com.clickem.scanner.model.FilterMode
import com.clickem.scanner.ui.components.FrostCardView
import com.clickem.scanner.ui.components.GridBackgroundView
import com.clickem.scanner.util.settings.ExportQuality
import com.clickem.scanner.util.settings.SettingsManager
import com.clickem.scanner.util.settings.VolumeKeyAction

class SettingsView(
    context: Context,
    private val settingsManager: SettingsManager,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.BLACK)

        // 1. Grid Background
        addView(GridBackgroundView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        })

        val mainScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }

        val initialSafeTop = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, initialSafeTop + (16 * density).toInt(), pad, (40 * density).toInt())
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val statusBarInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val safeMin = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
            val topInset = maxOf(statusBarInsets.top, safeMin)
            val pad = (20 * density).toInt()
            contentLayout.setPadding(pad, topInset + (16 * density).toInt(), pad, (40 * density).toInt())
            insets
        }

        // 2. Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Close settings"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (14 * density).toInt()
            }
            setColorFilter(Color.parseColor("#F8FAFC"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            setOnClickListener { onClose() }
        }
        header.addView(btnClose)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val tvTitle = TextView(context).apply {
            text = "SETTINGS"
            textSize = 17f
            setTextColor(Color.parseColor("#f8fafc"))
            paint.isFakeBoldText = true
            letterSpacing = 0.05f
        }
        titleCol.addView(tvTitle)

        val tvSubtitle = TextView(context).apply {
            text = "Scanner preferences & hardware controls"
            textSize = 11f
            setTextColor(Color.parseColor("#64748b"))
        }
        titleCol.addView(tvSubtitle)
        header.addView(titleCol)

        contentLayout.addView(header)

        // Section Title: Camera Controls
        contentLayout.addView(createSectionHeader("CAMERA CONTROLS"))

        // Card 1: Aspect Ratio
        val cardRatio = FrostCardView(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val ratioLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val ratioTitleRow = createOptionTitleRow(
            title = "Camera Aspect Ratio",
            subtitle = "Native sensor capture framing"
        )
        ratioLayout.addView(ratioTitleRow)

        val ratioPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pTop = (12 * density).toInt()
            setPadding(0, pTop, 0, 0)
        }

        val btn43 = TextView(context)
        val btn169 = TextView(context)
        val btn11 = TextView(context)
        val btnFull = TextView(context)

        fun updateRatioPills(selected: String) {
            fun stylePill(tv: TextView, isSelected: Boolean) {
                val pX = (10 * density).toInt()
                val pY = (6 * density).toInt()
                tv.setPadding(pX, pY, pX, pY)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor(if (isSelected) "#FFFFFF" else "#30FFFFFF"))
                }
                tv.background = bg
                tv.paint.isFakeBoldText = isSelected
                tv.textSize = 10.5f
                tv.setTextColor(if (isSelected) Color.parseColor("#090b10") else Color.parseColor("#8c96a5"))
            }

            stylePill(btn43, selected == "4:3")
            stylePill(btn169, selected == "16:9")
            stylePill(btn11, selected == "1:1")
            stylePill(btnFull, selected == "FULL")
        }

        btn43.apply {
            text = "4:3 Standard"
            setOnClickListener {
                settingsManager.cameraAspectRatio = "4:3"
                updateRatioPills("4:3")
            }
        }
        btn169.apply {
            text = "16:9 Wide"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                settingsManager.cameraAspectRatio = "16:9"
                updateRatioPills("16:9")
            }
        }
        btn11.apply {
            text = "1:1 Square"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                settingsManager.cameraAspectRatio = "1:1"
                updateRatioPills("1:1")
            }
        }
        btnFull.apply {
            text = "Full Screen"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                settingsManager.cameraAspectRatio = "FULL"
                updateRatioPills("FULL")
            }
        }
        updateRatioPills(settingsManager.cameraAspectRatio)
        ratioPillRow.addView(btn43)
        ratioPillRow.addView(btn169)
        ratioPillRow.addView(btn11)
        ratioPillRow.addView(btnFull)
        ratioLayout.addView(ratioPillRow)
        cardRatio.addView(ratioLayout)
        contentLayout.addView(cardRatio)

        // Card 2: Volume Keys Action
        val cardVolume = FrostCardView(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val volLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val volTitleRow = createOptionTitleRow(
            title = "Volume Key Action",
            subtitle = "Physical hardware volume button behavior"
        )
        volLayout.addView(volTitleRow)

        val volPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pTop = (12 * density).toInt()
            setPadding(0, pTop, 0, 0)
        }

        val btnVolShutter = TextView(context)
        val btnVolZoom = TextView(context)
        val btnVolOff = TextView(context)

        fun updateVolPills(action: VolumeKeyAction) {
            fun stylePill(tv: TextView, isSelected: Boolean) {
                val pX = (12 * density).toInt()
                val pY = (6 * density).toInt()
                tv.setPadding(pX, pY, pX, pY)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor(if (isSelected) "#FFFFFF" else "#30FFFFFF"))
                }
                tv.background = bg
                tv.paint.isFakeBoldText = isSelected
                tv.textSize = 11f
                tv.setTextColor(if (isSelected) Color.parseColor("#090b10") else Color.parseColor("#8c96a5"))
            }

            stylePill(btnVolShutter, action == VolumeKeyAction.SHUTTER)
            stylePill(btnVolZoom, action == VolumeKeyAction.ZOOM)
            stylePill(btnVolOff, action == VolumeKeyAction.OFF)
        }

        btnVolShutter.apply {
            text = "Shutter"
            setOnClickListener {
                settingsManager.volumeKeyAction = VolumeKeyAction.SHUTTER
                updateVolPills(VolumeKeyAction.SHUTTER)
            }
        }
        btnVolZoom.apply {
            text = "Zoom +/-"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                settingsManager.volumeKeyAction = VolumeKeyAction.ZOOM
                updateVolPills(VolumeKeyAction.ZOOM)
            }
        }
        btnVolOff.apply {
            text = "Off"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                settingsManager.volumeKeyAction = VolumeKeyAction.OFF
                updateVolPills(VolumeKeyAction.OFF)
            }
        }
        updateVolPills(settingsManager.volumeKeyAction)
        volPillRow.addView(btnVolShutter)
        volPillRow.addView(btnVolZoom)
        volPillRow.addView(btnVolOff)
        volLayout.addView(volPillRow)
        cardVolume.addView(volLayout)
        contentLayout.addView(cardVolume)

        // Section Title: Hardware Gestures
        contentLayout.addView(createSectionHeader("HARDWARE GESTURES"))

        // Card 3: Fingerprint Sensor Shutter
        val cardFp = FrostCardView(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val fpRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isFpSupported = settingsManager.isFingerprintHardwareSupported()

        val fpCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (12 * density).toInt()
            }
        }

        val tvFpTitle = TextView(context).apply {
            text = "Fingerprint Sensor Shutter"
            textSize = 13f
            setTextColor(Color.parseColor("#f8fafc"))
            paint.isFakeBoldText = true
        }
        fpCol.addView(tvFpTitle)

        val tvFpSub = TextView(context).apply {
            text = if (isFpSupported) {
                "Tap physical fingerprint sensor to take scans"
            } else {
                "Side/rear fingerprint gesture (Active on supported hardware)"
            }
            textSize = 11f
            setTextColor(Color.parseColor("#8c96a5"))
        }
        fpCol.addView(tvFpSub)
        fpRow.addView(fpCol)

        val switchFp = SwitchCompat(context).apply {
            isEnabled = true
            isChecked = settingsManager.isFingerprintShutterEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isFingerprintShutterEnabled = isChecked
            }
        }
        fpRow.addView(switchFp)
        cardFp.addView(fpRow)
        contentLayout.addView(cardFp)

        // Section Title: Filter Presets
        contentLayout.addView(createSectionHeader("PROCESSING DEFAULTS"))

        // Card 4: Default Filter Mode
        val cardFilter = FrostCardView(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val filterLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val filterTitleRow = createOptionTitleRow(
            title = "Default Capture Filter",
            subtitle = "Preset applied automatically upon scanning"
        )
        filterLayout.addView(filterTitleRow)

        val filterPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pTop = (12 * density).toInt()
            setPadding(0, pTop, 0, 0)
        }

        val filterModes = listOf(
            FilterMode.MAGIC_COLOR to "Magic Color",
            FilterMode.BW_CLEAN to "Clean B/W",
            FilterMode.GRAYSCALE to "Grayscale",
            FilterMode.ORIGINAL to "Original"
        )
        val filterViews = mutableListOf<TextView>()

        fun updateFilterPills(selectedMode: FilterMode) {
            for (i in filterModes.indices) {
                val mode = filterModes[i].first
                val tv = filterViews[i]
                val isSel = mode == selectedMode
                val pX = (10 * density).toInt()
                val pY = (6 * density).toInt()
                tv.setPadding(pX, pY, pX, pY)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (isSel) Color.parseColor("#FFFFFF") else Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor(if (isSel) "#FFFFFF" else "#30FFFFFF"))
                }
                tv.background = bg
                tv.paint.isFakeBoldText = isSel
                tv.textSize = 10.5f
                tv.setTextColor(if (isSel) Color.parseColor("#090b10") else Color.parseColor("#8c96a5"))
            }
        }

        for (item in filterModes) {
            val tv = TextView(context).apply {
                text = item.second
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    settingsManager.defaultFilterMode = item.first
                    updateFilterPills(item.first)
                }
            }
            filterViews.add(tv)
            filterPillRow.addView(tv)
        }
        updateFilterPills(settingsManager.defaultFilterMode)
        filterLayout.addView(filterPillRow)
        cardFilter.addView(filterLayout)
        contentLayout.addView(cardFilter)

        // Section Title: Storage Info
        contentLayout.addView(createSectionHeader("STORAGE & EXPORTS"))

        // Card: Default Export Quality
        val cardQuality = FrostCardView(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val qualityLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val qualityTitleRow = createOptionTitleRow(
            title = "Default Export Quality",
            subtitle = "Native/High preserves original sensor fidelity for PDFs & images"
        )
        qualityLayout.addView(qualityTitleRow)

        val qualityPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pTop = (12 * density).toInt()
            setPadding(0, pTop, 0, 0)
        }

        val qualityOptions = listOf(
            ExportQuality.LOW to "Low (1600px)",
            ExportQuality.MEDIUM to "Medium (2400px)",
            ExportQuality.HIGH to "Native / High"
        )
        val qualityViews = mutableListOf<TextView>()

        fun updateQualityPills(selected: ExportQuality) {
            for (i in qualityOptions.indices) {
                val opt = qualityOptions[i].first
                val tv = qualityViews[i]
                val isSel = opt == selected
                val pX = (11 * density).toInt()
                val pY = (6 * density).toInt()
                tv.setPadding(pX, pY, pX, pY)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (isSel) Color.parseColor("#FFFFFF") else Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor(if (isSel) "#FFFFFF" else "#30FFFFFF"))
                }
                tv.background = bg
                tv.paint.isFakeBoldText = isSel
                tv.textSize = 10.5f
                tv.setTextColor(if (isSel) Color.parseColor("#090b10") else Color.parseColor("#8c96a5"))
            }
        }

        for (item in qualityOptions) {
            val tv = TextView(context).apply {
                text = item.second
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    settingsManager.defaultExportQuality = item.first
                    updateQualityPills(item.first)
                }
            }
            qualityViews.add(tv)
            qualityPillRow.addView(tv)
        }
        updateQualityPills(settingsManager.defaultExportQuality)
        qualityLayout.addView(qualityPillRow)
        cardQuality.addView(qualityLayout)
        contentLayout.addView(cardQuality)

        val cardStorage = FrostCardView(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val storageCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val tvStorageLoc = TextView(context).apply {
            text = "Export Destination"
            textSize = 13f
            setTextColor(Color.parseColor("#f8fafc"))
            paint.isFakeBoldText = true
        }
        storageCol.addView(tvStorageLoc)

        val tvPath = TextView(context).apply {
            text = "/Downloads/ClickEm/ (Public Device Storage)"
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        storageCol.addView(tvPath)

        cardStorage.addView(storageCol)
        contentLayout.addView(cardStorage)

        mainScroll.addView(contentLayout)
        addView(mainScroll)
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            letterSpacing = 0.08f
            setPadding((4 * density).toInt(), (10 * density).toInt(), 0, (6 * density).toInt())
        }
    }

    private fun createOptionTitleRow(title: String, subtitle: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val tvT = TextView(context).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor("#f8fafc"))
                paint.isFakeBoldText = true
            }
            val tvSub = TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#8c96a5"))
            }
            addView(tvT)
            addView(tvSub)
        }
    }
}
