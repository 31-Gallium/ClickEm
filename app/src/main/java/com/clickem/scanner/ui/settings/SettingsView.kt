package com.clickem.scanner.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private val onClose: () -> Unit,
    private val onPickExportFolder: (() -> Unit)? = null
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private var tvExportPath: TextView? = null
    private var btnResetStorage: TextView? = null

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
        val initialSafeBottom = com.clickem.scanner.util.UiInsetsUtil.getSafeBottomInset(context)
        val pad = (20 * density).toInt()
        val baseBottomPad = (40 * density).toInt()
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, initialSafeTop + (16 * density).toInt(), pad, baseBottomPad + initialSafeBottom)
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val statusBarInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            val safeMin = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
            val topInset = maxOf(statusBarInsets.top, safeMin)
            val safeBottom = maxOf(navInsets.bottom, com.clickem.scanner.util.UiInsetsUtil.getSafeBottomInset(context))
            contentLayout.setPadding(pad, topInset + (16 * density).toInt(), pad, baseBottomPad + safeBottom)
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

        val fpLocation = com.clickem.scanner.util.settings.FingerprintSensorUtil.getSensorLocation(context)
        val locationLabel = com.clickem.scanner.util.settings.FingerprintSensorUtil.getSensorLocationLabel(context)
        val tvFpSub = TextView(context).apply {
            text = when {
                !isFpSupported -> "No biometric scanner hardware detected on device"
                fpLocation == com.clickem.scanner.util.settings.FingerprintSensorUtil.SensorLocation.UNDER_DISPLAY ->
                    "Detected: $locationLabel. Disabled by default to prevent conflicts with on-screen touches."
                else ->
                    "Detected: $locationLabel. Tap physical sensor to capture scan."
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

        val storageRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val storageCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val tvStorageLoc = TextView(context).apply {
            text = "Export Destination"
            textSize = 13f
            setTextColor(Color.parseColor("#f8fafc"))
            paint.isFakeBoldText = true
        }
        storageCol.addView(tvStorageLoc)

        tvExportPath = TextView(context).apply {
            text = settingsManager.exportFolderPath
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        storageCol.addView(tvExportPath)
        storageRow.addView(storageCol)

        val btnActionsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnResetStorage = TextView(context).apply {
            text = "Reset"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            paint.isFakeBoldText = true
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            visibility = if (settingsManager.exportFolderUri != null) View.VISIBLE else View.GONE
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#18202C"))
                setStroke((1 * density).toInt(), Color.parseColor("#2D3B4E"))
            }
            background = bg
            setOnClickListener {
                settingsManager.resetExportFolder()
                tvExportPath?.text = settingsManager.exportFolderPath
                visibility = View.GONE
                Toast.makeText(context, "Export destination reset to default", Toast.LENGTH_SHORT).show()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
            }
            layoutParams = lp
        }
        btnActionsLayout.addView(btnResetStorage)

        val btnBrowseStorage = TextView(context).apply {
            text = "Browse"
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            val hPad = (12 * density).toInt()
            val vPad = (6 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1E293B"))
                setStroke((1 * density).toInt(), Color.parseColor("#334155"))
            }
            background = bg
            contentDescription = "Browse and select export folder"
            setOnClickListener {
                onPickExportFolder?.invoke()
            }
        }
        btnActionsLayout.addView(btnBrowseStorage)

        storageRow.addView(btnActionsLayout)
        cardStorage.addView(storageRow)
        cardStorage.setOnClickListener {
            onPickExportFolder?.invoke()
        }
        contentLayout.addView(cardStorage)

        // Section Title: Updates & About
        contentLayout.addView(createSectionHeader("UPDATES & ABOUT"))

        val cardUpdate = FrostCardView(context).apply {
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

        val updateLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val appInfoRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val ivLogo = ImageView(context).apply {
            setImageResource(R.drawable.ic_clickem_logo)
            val sz = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        appInfoRow.addView(ivLogo)

        val appTitleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvAppName = TextView(context).apply {
            text = "ClickEm Scanner"
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
        }
        appTitleCol.addView(tvAppName)

        val currentVersion = com.clickem.scanner.util.update.UpdateManager.getAppVersionName(context)
        val tvAppVersion = TextView(context).apply {
            text = "Version $currentVersion (GitHub Release)"
            textSize = 11f
            setTextColor(Color.parseColor("#8c96a5"))
        }
        appTitleCol.addView(tvAppVersion)
        appInfoRow.addView(appTitleCol)
        updateLayout.addView(appInfoRow)

        val tvUpdateStatus = TextView(context).apply {
            text = "Ready to check for updates from GitHub"
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        updateLayout.addView(tvUpdateStatus)

        val updateProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * density).toInt()
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        updateLayout.addView(updateProgressBar)

        val btnCheckUpdate = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pX = (16 * density).toInt()
            val pY = (10 * density).toInt()
            setPadding(pX, pY, pX, pY)
            val bg = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(Color.parseColor("#18FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#32FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvBtnLabel = TextView(context).apply {
            text = "Check for Updates"
            textSize = 12.5f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
        }
        btnCheckUpdate.addView(tvBtnLabel)

        var latestInfo: com.clickem.scanner.util.update.UpdateInfo? = null

        btnCheckUpdate.setOnClickListener {
            val info = latestInfo
            if (info != null && info.hasUpdate) {
                tvBtnLabel.text = "Downloading Update..."
                btnCheckUpdate.isEnabled = false
                updateProgressBar.visibility = View.VISIBLE
                updateProgressBar.progress = 0
                com.clickem.scanner.util.update.UpdateManager.downloadAndInstall(
                    context,
                    info.apkDownloadUrl,
                    onProgress = { pct ->
                        updateProgressBar.progress = pct
                        tvUpdateStatus.text = "Downloading APK: $pct%"
                    },
                    onError = { err ->
                        tvBtnLabel.text = "Retry Update"
                        btnCheckUpdate.isEnabled = true
                        tvUpdateStatus.text = "Error: $err"
                        tvUpdateStatus.setTextColor(Color.parseColor("#F43F5E"))
                    }
                )
            } else {
                tvBtnLabel.text = "Checking GitHub..."
                btnCheckUpdate.isEnabled = false
                tvUpdateStatus.text = "Connecting to GitHub Releases API..."
                tvUpdateStatus.setTextColor(Color.parseColor("#CBD5E1"))

                com.clickem.scanner.util.update.UpdateManager.checkForUpdate(context) { res ->
                    btnCheckUpdate.isEnabled = true
                    if (res == null) {
                        tvBtnLabel.text = "Check for Updates"
                        tvUpdateStatus.text = "Could not connect to GitHub. Check internet connection."
                    } else if (res.hasUpdate) {
                        latestInfo = res
                        tvBtnLabel.text = "Install ${res.latestVersion}"
                        tvUpdateStatus.text = "New release available: ${res.latestVersion}\nTap button to download & install."
                        tvUpdateStatus.setTextColor(Color.parseColor("#FFFFFF"))
                    } else {
                        tvBtnLabel.text = "Check for Updates"
                        tvUpdateStatus.text = "ClickEm is up to date (${res.currentVersion})."
                        tvUpdateStatus.setTextColor(Color.parseColor("#10B981"))
                    }
                }
            }
        }

        updateLayout.addView(btnCheckUpdate)
        cardUpdate.addView(updateLayout)
        contentLayout.addView(cardUpdate)

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

    fun updateExportLocation(displayPath: String) {
        tvExportPath?.text = displayPath
        btnResetStorage?.visibility = if (settingsManager.exportFolderUri != null) View.VISIBLE else View.GONE
    }
}
