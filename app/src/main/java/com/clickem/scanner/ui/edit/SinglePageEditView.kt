package com.clickem.scanner.ui.edit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.clickem.scanner.R
import com.clickem.scanner.model.FilterMode
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.ui.components.MagnifierLoupeView
import com.clickem.scanner.ui.components.ManualCropOverlayView
import com.clickem.scanner.util.cv.BoundaryDetector
import com.clickem.scanner.util.cv.DocumentFilterEngine
import com.clickem.scanner.util.cv.PerspectiveWarper
import com.clickem.scanner.util.storage.ScanStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SinglePageEditView(
    context: Context,
    private val pages: MutableList<ScannedPage>,
    private val initialIndex: Int,
    private val storageManager: ScanStorageManager,
    private val onPagesSaved: (updatedPages: List<ScannedPage>) -> Unit,
    private val onBackToCamera: () -> Unit,
    private val onRetakeRequested: ((pageIndex: Int) -> Unit)? = null
) : FrameLayout(context) {

    companion object {
        @Volatile
        private var sharedSampleDocBitmap: Bitmap? = null
        private val cachedStaticThumbnails = mutableMapOf<FilterMode, Bitmap>()

        /**
         * Returns a single shared static sample document bitmap used for all filter preset
         * thumbnails across the app to save CPU and memory resources instead of filtering
         * the full current page bitmap for every preset card.
         */
        @Synchronized
        fun getOrCreateSampleDocumentBitmap(): Bitmap {
            val existing = sharedSampleDocBitmap
            if (existing != null && !existing.isRecycled) return existing

            val w = 140
            val h = 186
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Warm slightly shadowed paper background to highlight B/W Clean, Magic Color & Warm Note
            paint.color = Color.rgb(234, 231, 224)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            // Subtle uneven lighting gradient band on right edge
            paint.color = Color.rgb(216, 212, 204)
            canvas.drawRect(w * 0.68f, 0f, w.toFloat(), h.toFloat(), paint)

            // Document header banner (colored accent so Magic Color, Grayscale & Invert are distinct)
            paint.color = Color.rgb(42, 86, 148)
            canvas.drawRoundRect(RectF(14f, 14f, w - 14f, 38f), 4f, 4f, paint)

            // Stamp badge on header
            paint.color = Color.rgb(214, 72, 54)
            canvas.drawCircle(w - 28f, 26f, 7f, paint)

            // Simulated document title and text paragraphs
            paint.color = Color.rgb(34, 38, 46)
            canvas.drawRoundRect(RectF(14f, 48f, w * 0.65f, 56f), 2f, 2f, paint)

            paint.color = Color.rgb(72, 76, 84)
            var y = 66f
            val lineWidths = floatArrayOf(0.82f, 0.76f, 0.80f, 0.55f, 0.82f, 0.78f, 0.72f)
            for (wf in lineWidths) {
                canvas.drawRoundRect(RectF(14f, y, 14f + (w - 28f) * wf, y + 5f), 2f, 2f, paint)
                y += 11f
            }

            // Highlighted summary box at bottom
            paint.color = Color.rgb(196, 152, 68)
            canvas.drawRoundRect(RectF(14f, h - 36f, w * 0.52f, h - 16f), 4f, 4f, paint)
            paint.color = Color.rgb(46, 124, 88)
            canvas.drawRoundRect(RectF(w * 0.56f, h - 36f, w - 14f, h - 16f), 4f, 4f, paint)

            sharedSampleDocBitmap = bmp
            return bmp
        }

        @Synchronized
        fun ensureStaticThumbnailsSync(): Map<FilterMode, Bitmap> {
            if (cachedStaticThumbnails.size == FilterMode.values().size &&
                cachedStaticThumbnails.values.all { !it.isRecycled }
            ) {
                return cachedStaticThumbnails
            }
            val thumbBase = getOrCreateSampleDocumentBitmap()
            cachedStaticThumbnails[FilterMode.ORIGINAL] = thumbBase
            for (mode in listOf(
                FilterMode.MAGIC_COLOR,
                FilterMode.BW_CLEAN,
                FilterMode.TEXT_POP,
                FilterMode.GRAYSCALE,
                FilterMode.WARM_NOTE,
                FilterMode.INVERT
            )) {
                if (!cachedStaticThumbnails.containsKey(mode) || cachedStaticThumbnails[mode]?.isRecycled == true) {
                    cachedStaticThumbnails[mode] = DocumentFilterEngine.applyFilter(thumbBase, mode, 1.0f)
                }
            }
            return cachedStaticThumbnails
        }

        fun prewarmFilterThumbnails() {
            if (cachedStaticThumbnails.size == FilterMode.values().size &&
                cachedStaticThumbnails.values.all { !it.isRecycled }
            ) return
            CoroutineScope(Dispatchers.Default).launch {
                val map = ensureStaticThumbnailsSync()
                withContext(Dispatchers.Main) {
                    cachedStaticThumbnails.putAll(map)
                }
            }
        }
    }

    // Single-page constructor overload for backward compatibility
    constructor(
        context: Context,
        page: ScannedPage,
        pageIndex: Int,
        storageManager: ScanStorageManager,
        onPageSaved: (ScannedPage, Int) -> Unit,
        onBackToCamera: () -> Unit
    ) : this(
        context,
        mutableListOf(page),
        0,
        storageManager,
        { updatedList -> if (updatedList.isNotEmpty()) onPageSaved(updatedList[0], pageIndex) },
        onBackToCamera,
        null
    )

    private val density = context.resources.displayMetrics.density

    private val viewPager: ViewPager2
    private val pagerAdapter: EditPagerAdapter
    private val tvPageIndicator: TextView
    private val btnPrevPage: ImageView
    private val btnNextPage: ImageView
    private val tvCompareBadge: TextView
    private var currentPageIndex = initialIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    private var isSaving = false

    // Tool categories: 0 = FILTERS, 1 = CROP & ROTATE
    private var activeToolCategory = 0
    private val tabFilters: LinearLayout
    private val tabCropRotate: LinearLayout
    private val toolsContainer: FrameLayout = FrameLayout(context)

    // Filter Thumbnail & Sensitivity Slider State
    private var presetThumbnails = mutableMapOf<FilterMode, Bitmap>()
    private var thumbnailJob: Job? = null
    private var sliderContainer: LinearLayout? = null
    private var tvSensitivityLabel: TextView? = null
    private var seekBar: SeekBar? = null
    private val thumbnailCardMap = mutableMapOf<FilterMode, Triple<View, ImageView, TextView>>()
    private var topBarInsetPx = (78 * density).toInt()
    private var bottomPanelInsetPx = (220 * density).toInt()

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.BLACK)

        // 1. ViewPager2 for horizontal multi-page swiping
        viewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }

        pagerAdapter = EditPagerAdapter()
        viewPager.adapter = pagerAdapter
        viewPager.setCurrentItem(currentPageIndex, false)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == currentPageIndex && toolsContainer.childCount > 0) {
                    return
                }
                getCurrentPageHolder()?.flushCurrentEdits()
                currentPageIndex = position
                updateTopBarTitle()
                renderToolCategoryUI()
                generateFilterThumbnails()
            }
        })
        addView(viewPager)

        // 2. Floating "Original Comparison" Badge (Monochrome Frost)
        tvCompareBadge = TextView(context).apply {
            text = "SHOWING ORIGINAL (RAW)"
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            letterSpacing = 0.06f
            val hP = (14 * density).toInt()
            val vP = (6 * density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(Color.parseColor("#E6090B10"))
                setStroke((1 * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            background = bg
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setMargins(0, (96 * density).toInt(), 0, 0)
            }
            visibility = View.GONE
        }
        addView(tvCompareBadge)

        // 3. Top Action Bar
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hP = (16 * density).toInt()
            setPadding(hP, (14 * density).toInt(), hP, (12 * density).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#D9090B10"))
            }
            background = bg
            elevation = 0f
            val initTop = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
            val padH = (16 * density).toInt()
            setPadding(padH, initTop + (8 * density).toInt(), padH, (12 * density).toInt())
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val statusBarInsets = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                )
                val safeMin = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
                val topInset = maxOf(statusBarInsets.top, safeMin)
                v.setPadding(padH, topInset + (8 * density).toInt(), padH, (12 * density).toInt())
                insets
            }
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (h > 0 && h != topBarInsetPx) {
                    topBarInsetPx = h + (8 * density).toInt()
                    updateActiveHolderViewportPadding()
                    val badgeLp = tvCompareBadge.layoutParams as? LayoutParams
                    if (badgeLp != null) {
                        badgeLp.topMargin = h + (12 * density).toInt()
                        tvCompareBadge.layoutParams = badgeLp
                    }
                }
            }
        }

        val btnBack = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Navigate back to camera"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
            setColorFilter(Color.parseColor("#FFFFFF"))
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            background = bg
            setOnClickListener { onBackToCamera() }
        }
        topBar.addView(btnBack)

        // Centered Page Stepper Pill
        val pageNavWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pagePill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hP = (6 * density).toInt()
            val vP = (2 * density).toInt()
            setPadding(hP, vP, hP, vP)
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#22FFFFFF"))
            }
        }

        btnPrevPage = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_left)
            contentDescription = "Previous page"
            setColorFilter(Color.parseColor("#FFFFFF"))
            val sz = (36 * density).toInt()
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            visibility = if (pages.size > 1) View.VISIBLE else View.GONE
            setOnClickListener {
                if (currentPageIndex > 0) {
                    viewPager.setCurrentItem(currentPageIndex - 1, true)
                }
            }
        }
        pagePill.addView(btnPrevPage)

        tvPageIndicator = TextView(context).apply {
            text = "Page ${currentPageIndex + 1} of ${pages.size}"
            textSize = 13.5f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            val hP = (8 * density).toInt()
            setPadding(hP, 0, hP, 0)
        }
        pagePill.addView(tvPageIndicator)

        btnNextPage = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            contentDescription = "Next page"
            setColorFilter(Color.parseColor("#FFFFFF"))
            val sz = (36 * density).toInt()
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            visibility = if (pages.size > 1) View.VISIBLE else View.GONE
            setOnClickListener {
                if (currentPageIndex < pages.size - 1) {
                    viewPager.setCurrentItem(currentPageIndex + 1, true)
                }
            }
        }
        pagePill.addView(btnNextPage)
        pageNavWrap.addView(pagePill)
        topBar.addView(pageNavWrap)

        // "Hold to Compare" icon button on top bar
        val btnHoldCompare = ImageView(context).apply {
            setImageResource(R.drawable.ic_compare)
            contentDescription = "Hold to compare with original raw image"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginEnd = (8 * density).toInt()
            }
            val normalBg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            val activeBg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#FFFFFF"))
            }
            background = normalBg
            setColorFilter(Color.parseColor("#FFFFFF"))

            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        background = activeBg
                        setColorFilter(Color.parseColor("#090B10"))
                        getCurrentPageHolder()?.showRawOriginal(true)
                        tvCompareBadge.visibility = View.VISIBLE
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = normalBg
                        setColorFilter(Color.parseColor("#FFFFFF"))
                        getCurrentPageHolder()?.showRawOriginal(false)
                        tvCompareBadge.visibility = View.GONE
                        true
                    }
                    else -> false
                }
            }
        }
        topBar.addView(btnHoldCompare)

        // Global Save All icon button (Saves all pages and edits in session)
        val btnSaveAll = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            contentDescription = "Save and export edited pages"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#FFFFFF"))
            }
            background = bg
            setColorFilter(Color.parseColor("#090B10"))
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
            setOnClickListener { saveAllEditedPages() }
        }
        topBar.addView(btnSaveAll)
        addView(topBar)

        // 4. Bottom Categorized Tool Sheet (Flush Monochrome Frost Panel)
        val bottomContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (14 * density).toInt()
            setPadding(p, (8 * density).toInt(), p, (16 * density).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            val topR = 26f * density
            val bg = GradientDrawable().apply {
                cornerRadii = floatArrayOf(topR, topR, topR, topR, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#F00D1017"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#28FFFFFF"))
            }
            background = bg
            elevation = 0f
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (h > 0 && h != bottomPanelInsetPx) {
                    bottomPanelInsetPx = h + (8 * density).toInt()
                    updateActiveHolderViewportPadding()
                }
            }
        }

        // Subtle sheet pill indicator
        val sheetHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (3.5f * density).toInt()).apply {
                bottomMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 2f * density
                setColor(Color.parseColor("#38FFFFFF"))
            }
        }
        bottomContainer.addView(sheetHandle)

        toolsContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        bottomContainer.addView(toolsContainer)

        // Full-Width Bottom Navigation Dock Row: [Retake Icon]  [Filters | Crop Segmented Toggle]  [Reset Icon]
        val navDockRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
        }

        // Left Action: Retake Current Page (Symmetric 48dp icon button; preserves position in queue/project)
        val btnRetake = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            contentDescription = "Retake current page photo"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginEnd = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#18FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#32FFFFFF"))
            }
            setColorFilter(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                requestRetakeCurrentPage()
            }
        }
        navDockRow.addView(btnRetake)

        // Center: Full-Width Category Segmented Pill Dock: [Filters] [Crop & Rotate]
        val categoryTabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (4 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#28FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                0,
                (48 * density).toInt(),
                1f
            )
        }

        tabFilters = createTabButton(R.drawable.ic_filter, "Filters", "Filter adjustments", isSelected = true) {
            switchToolCategory(0)
        }
        categoryTabRow.addView(tabFilters)

        tabCropRotate = createTabButton(R.drawable.ic_crop, "Crop", "Crop and rotate adjustments", isSelected = false) {
            switchToolCategory(1)
        }
        categoryTabRow.addView(tabCropRotate)
        navDockRow.addView(categoryTabRow)

        // Right Action: Reset Current Page Edits
        val btnResetPage = ImageView(context).apply {
            setImageResource(R.drawable.ic_restore)
            contentDescription = "Reset current page edits"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginStart = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#18FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#32FFFFFF"))
            }
            setColorFilter(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                getCurrentPageHolder()?.resetCurrentPage()
            }
        }
        navDockRow.addView(btnResetPage)

        bottomContainer.addView(navDockRow)
        addView(bottomContainer)

        updateTopBarTitle()
        renderToolCategoryUI()
    }

    fun requestRetakeCurrentPage() {
        getCurrentPageHolder()?.flushCurrentEdits()
        if (onRetakeRequested != null) {
            onRetakeRequested.invoke(currentPageIndex)
        } else {
            onBackToCamera()
        }
    }

    private fun createTabButton(
        iconRes: Int,
        labelText: String,
        contentDesc: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = contentDesc
            val btnH = (40 * density).toInt()
            minimumHeight = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, btnH, 1f).apply {
                val margin = (2 * density).toInt()
                setMargins(margin, 0, margin, 0)
            }
            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                val isz = (18 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(isz, isz).apply {
                    marginEnd = (6 * density).toInt()
                }
            }
            addView(icon)

            val tv = TextView(context).apply {
                text = labelText
                textSize = 12f
                paint.isFakeBoldText = true
            }
            addView(tv)

            setOnClickListener { onClick() }
            updateTabStyle(this, isSelected)
        }
    }

    private fun updateTabStyle(btn: LinearLayout, isSelected: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = 20f * density
            setColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.TRANSPARENT)
            if (isSelected) {
                setStroke((1 * density).toInt(), Color.parseColor("#FFFFFF"))
            }
        }
        btn.background = bg
        val fgColor = if (isSelected) Color.parseColor("#090B10") else Color.parseColor("#8C96A5")
        for (i in 0 until btn.childCount) {
            when (val child = btn.getChildAt(i)) {
                is ImageView -> child.setColorFilter(fgColor)
                is TextView -> child.setTextColor(fgColor)
            }
        }
    }

    private fun switchToolCategory(categoryIndex: Int) {
        activeToolCategory = categoryIndex
        updateTabStyle(tabFilters, activeToolCategory == 0)
        updateTabStyle(tabCropRotate, activeToolCategory == 1)
        renderToolCategoryUI()
    }

    private fun renderToolCategoryUI() {
        toolsContainer.removeAllViews()

        if (activeToolCategory == 0) {
            renderFiltersUI(toolsContainer)
        } else {
            renderCropRotateUI(toolsContainer)
        }
    }

    private fun renderFiltersUI(container: FrameLayout) {
        val currentPage = pages.getOrNull(currentPageIndex)
        val currentMode = currentPage?.filterMode ?: FilterMode.ORIGINAL
        val currentIntensity = currentPage?.filterIntensity ?: 1.0f

        val filtersRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Sensitivity Slider Section (Frosted Control Capsule)
        val sliderLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (12 * density).toInt()
            val vPad = (8 * density).toInt()
            setPadding(hPad, vPad, hPad, (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            visibility = if (currentMode == FilterMode.ORIGINAL) View.GONE else View.VISIBLE
        }
        sliderContainer = sliderLayout

        val sliderHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (2 * density).toInt()
            }
        }

        val percent = Math.round(currentIntensity * 100).toInt().coerceIn(0, 100)
        val label = TextView(context).apply {
            text = "SENSITIVITY: $percent%"
            textSize = 11f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            letterSpacing = 0.06f
            setPadding((4 * density).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvSensitivityLabel = label
        sliderHeaderRow.addView(label)

        val btnDefaultIntensity = TextView(context).apply {
            text = "Default 100%"
            textSize = 10f
            setTextColor(Color.parseColor("#CBD5E1"))
            paint.isFakeBoldText = true
            val px = (8 * density).toInt()
            val py = (3 * density).toInt()
            setPadding(px, py, px, py)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#18FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#28FFFFFF"))
            }
            setOnClickListener {
                seekBar?.progress = 100
                tvSensitivityLabel?.text = "SENSITIVITY: 100%"
                pages.getOrNull(currentPageIndex)?.filterIntensity = 1.0f
                getCurrentPageHolder()?.applyFullFilterIntensity(1.0f)
                val activeMode = pages.getOrNull(currentPageIndex)?.filterMode ?: FilterMode.ORIGINAL
                if (activeMode != FilterMode.ORIGINAL) {
                    updateActivePresetThumbnail(activeMode, 1.0f)
                }
            }
        }
        sliderHeaderRow.addView(btnDefaultIntensity)
        sliderLayout.addView(sliderHeaderRow)

        val bar = SeekBar(context).apply {
            max = 100
            progress = percent
            splitTrack = false
            progressTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#30FFFFFF"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            val vPad = (4 * density).toInt()
            setPadding(paddingLeft, vPad, paddingRight, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val intensity = prog / 100f
                    tvSensitivityLabel?.text = "SENSITIVITY: $prog%"
                    pages.getOrNull(currentPageIndex)?.filterIntensity = intensity
                    getCurrentPageHolder()?.updateLiveFilterScrub(intensity)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val finalProg = sb?.progress ?: 100
                    val intensity = finalProg / 100f
                    pages.getOrNull(currentPageIndex)?.filterIntensity = intensity
                    getCurrentPageHolder()?.applyFullFilterIntensity(intensity)
                    val activeMode = pages.getOrNull(currentPageIndex)?.filterMode ?: FilterMode.ORIGINAL
                    if (activeMode != FilterMode.ORIGINAL) {
                        updateActivePresetThumbnail(activeMode, intensity)
                    }
                }
            })
        }
        seekBar = bar
        sliderLayout.addView(bar)
        filtersRoot.addView(sliderLayout)

        // 2. Preset Thumbnail Preview Ribbon (Uses static shared sample image to save resources)
        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val ribbonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (4 * density).toInt()
            setPadding(hPad, (2 * density).toInt(), hPad, (2 * density).toInt())
        }

        val filterModes = listOf(
            FilterMode.ORIGINAL to "Original",
            FilterMode.MAGIC_COLOR to "Magic Color",
            FilterMode.BW_CLEAN to "B/W Clean",
            FilterMode.TEXT_POP to "Text Pop",
            FilterMode.GRAYSCALE to "Grayscale",
            FilterMode.WARM_NOTE to "Warm Note",
            FilterMode.INVERT to "Invert"
        )

        thumbnailCardMap.clear()

        for ((mode, name) in filterModes) {
            val isSelected = (mode == currentMode)

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val cardW = (66 * density).toInt()
                val cardH = (84 * density).toInt()
                val pad = (4 * density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(cardW, cardH).apply {
                    marginEnd = (10 * density).toInt()
                }
                isClickable = true
                isFocusable = true
                contentDescription = if (isSelected) "$name filter preset, selected" else "$name filter preset"
            }

            val ivThumb = ImageView(context).apply {
                val thumbH = (52 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    thumbH
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(Color.parseColor("#12151D"))
                }
                clipToOutline = true
                val staticThumbnails = ensureStaticThumbnailsSync()
                val existingBmp = presetThumbnails[mode] ?: staticThumbnails[mode]
                if (existingBmp != null && !existingBmp.isRecycled) {
                    setImageBitmap(existingBmp)
                }
            }
            card.addView(ivThumb)

            val tvName = TextView(context).apply {
                text = name
                textSize = 9.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (22 * density).toInt()
                )
            }
            card.addView(tvName)

            stylePresetCard(card, tvName, isSelected)

            card.setOnClickListener {
                selectFilterMode(mode)
            }

            thumbnailCardMap[mode] = Triple(card, ivThumb, tvName)
            ribbonRow.addView(card)
        }

        filterScroll.addView(ribbonRow)
        filtersRoot.addView(filterScroll)
        container.addView(filtersRoot)

        generateFilterThumbnails()
    }

    private fun stylePresetCard(card: View, titleView: TextView, isSelected: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = 12f * density
            if (isSelected) {
                setColor(Color.parseColor("#25FFFFFF"))
                setStroke((2 * density).toInt(), Color.parseColor("#FFFFFF"))
            } else {
                setColor(Color.parseColor("#E612151D"))
                setStroke((1 * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
        }
        card.background = bg
        titleView.setTextColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#C8D2DC"))
        titleView.paint.isFakeBoldText = isSelected
    }

    private fun selectFilterMode(mode: FilterMode) {
        val currentPage = pages.getOrNull(currentPageIndex) ?: return
        currentPage.filterMode = mode

        for ((cardMode, triple) in thumbnailCardMap) {
            val (card, _, tvName) = triple
            val isSelected = (cardMode == mode)
            stylePresetCard(card, tvName, isSelected)
            val displayName = when (cardMode) {
                FilterMode.ORIGINAL -> "Original"
                FilterMode.MAGIC_COLOR -> "Magic Color"
                FilterMode.BW_CLEAN -> "B/W Clean"
                FilterMode.TEXT_POP -> "Text Pop"
                FilterMode.GRAYSCALE -> "Grayscale"
                FilterMode.WARM_NOTE -> "Warm Note"
                FilterMode.INVERT -> "Invert"
            }
            card.contentDescription = if (isSelected) {
                "$displayName filter preset, selected"
            } else {
                "$displayName filter preset"
            }
        }

        if (mode == FilterMode.ORIGINAL) {
            sliderContainer?.visibility = View.GONE
        } else {
            sliderContainer?.visibility = View.VISIBLE
            val percent = Math.round(currentPage.filterIntensity * 100).toInt().coerceIn(0, 100)
            seekBar?.progress = percent
            tvSensitivityLabel?.text = "SENSITIVITY: $percent%"
        }

        getCurrentPageHolder()?.setFilterMode(mode)
    }

    /**
     * Generates filter preset thumbnails using a single shared static sample document bitmap
     * instead of re-filtering the current high-resolution scanned page image, saving CPU and memory.
     */
    private fun generateFilterThumbnails() {
        val currentPage = pages.getOrNull(currentPageIndex)
        val activeMode = currentPage?.filterMode ?: FilterMode.ORIGINAL
        val pageIntensity = currentPage?.filterIntensity ?: 1.0f

        // Fast path: reuse canonical cached sample thumbnails if already rendered
        val staticThumbs = ensureStaticThumbnailsSync()
        if (staticThumbs.size == FilterMode.values().size &&
            staticThumbs.values.all { !it.isRecycled }
        ) {
            presetThumbnails.clear()
            presetThumbnails.putAll(staticThumbs)
            for ((mode, triple) in thumbnailCardMap) {
                staticThumbs[mode]?.let { triple.second.setImageBitmap(it) }
            }
            if (activeMode != FilterMode.ORIGINAL && pageIntensity != 1.0f) {
                updateActivePresetThumbnail(activeMode, pageIntensity)
            }
            return
        }

        thumbnailJob?.cancel()
        val intensity = 1.0f

        thumbnailJob = CoroutineScope(Dispatchers.Default).launch {
            val thumbBase = getOrCreateSampleDocumentBitmap()

            val map = mutableMapOf<FilterMode, Bitmap>()
            map[FilterMode.ORIGINAL] = thumbBase

            for (mode in listOf(
                FilterMode.MAGIC_COLOR,
                FilterMode.BW_CLEAN,
                FilterMode.TEXT_POP,
                FilterMode.GRAYSCALE,
                FilterMode.WARM_NOTE,
                FilterMode.INVERT
            )) {
                map[mode] = DocumentFilterEngine.applyFilter(thumbBase, mode, intensity)
            }

            withContext(Dispatchers.Main) {
                cachedStaticThumbnails.putAll(map)
                presetThumbnails.clear()
                presetThumbnails.putAll(map)
                for ((mode, triple) in thumbnailCardMap) {
                    val bmp = map[mode]
                    if (bmp != null) {
                        triple.second.setImageBitmap(bmp)
                        triple.second.invalidateOutline()
                        triple.second.invalidate()
                    }
                }
                if (activeMode != FilterMode.ORIGINAL && pageIntensity != 1.0f) {
                    updateActivePresetThumbnail(activeMode, pageIntensity)
                }
            }
        }
    }

    private fun updateActivePresetThumbnail(mode: FilterMode, intensity: Float) {
        val baseThumb = cachedStaticThumbnails[FilterMode.ORIGINAL]
            ?: presetThumbnails[FilterMode.ORIGINAL]
            ?: getOrCreateSampleDocumentBitmap()
        CoroutineScope(Dispatchers.Default).launch {
            val updated = DocumentFilterEngine.applyFilter(baseThumb, mode, intensity)
            withContext(Dispatchers.Main) {
                presetThumbnails[mode] = updated
                thumbnailCardMap[mode]?.second?.setImageBitmap(updated)
                thumbnailCardMap[mode]?.second?.invalidateOutline()
                thumbnailCardMap[mode]?.second?.invalidate()
            }
        }
    }

    private fun renderCropRotateUI(container: FrameLayout) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val vPad = (4 * density).toInt()
            setPadding(0, vPad, 0, vPad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun createToolColumn(
            iconRes: Int,
            labelText: String,
            contentDesc: String,
            onClick: () -> Unit
        ): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val vPad = (8 * density).toInt()
                setPadding((4 * density).toInt(), vPad, (4 * density).toInt(), vPad)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    val m = (3 * density).toInt()
                    setMargins(m, 0, m, 0)
                }
                background = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(Color.parseColor("#12FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#26FFFFFF"))
                }
                setOnClickListener { onClick() }

                val btn = ImageView(context).apply {
                    setImageResource(iconRes)
                    contentDescription = contentDesc
                    val btnSz = (48 * density).toInt()
                    val p = (12 * density).toInt()
                    setPadding(p, p, p, p)
                    val bg = GradientDrawable().apply {
                        cornerRadius = 24f * density
                        setColor(Color.parseColor("#18FFFFFF"))
                        setStroke((1 * density).toInt(), Color.parseColor("#32FFFFFF"))
                    }
                    background = bg
                    setColorFilter(Color.parseColor("#FFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
                    setOnClickListener { onClick() }
                }
                addView(btn)

                val tv = TextView(context).apply {
                    text = labelText
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#E2E8F0"))
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, (5 * density).toInt(), 0, 0)
                }
                addView(tv)
            }
        }

        row.addView(createToolColumn(
            R.drawable.ic_rotate_left,
            "Rotate L",
            "Rotate 90 degrees counter-clockwise"
        ) {
            getCurrentPageHolder()?.rotateCounterClockwise90()
        })

        row.addView(createToolColumn(
            R.drawable.ic_rotate,
            "Rotate R",
            "Rotate 90 degrees clockwise"
        ) {
            getCurrentPageHolder()?.rotate90()
        })

        row.addView(createToolColumn(
            R.drawable.ic_auto_detect,
            "Auto Edge",
            "Automatically detect document boundary"
        ) {
            getCurrentPageHolder()?.autoDetectCrop()
        })

        row.addView(createToolColumn(
            R.drawable.ic_reset,
            "Full Frame",
            "Reset crop to full image bounds"
        ) {
            getCurrentPageHolder()?.resetFullFrameCrop()
        })

        container.addView(row)
    }

    private fun updateTopBarTitle() {
        tvPageIndicator.text = "Page ${currentPageIndex + 1} of ${pages.size}"
        val hasMultiple = pages.size > 1
        btnPrevPage.visibility = if (hasMultiple) View.VISIBLE else View.GONE
        btnNextPage.visibility = if (hasMultiple) View.VISIBLE else View.GONE
        btnPrevPage.alpha = if (currentPageIndex > 0) 1.0f else 0.35f
        btnNextPage.alpha = if (currentPageIndex < pages.size - 1) 1.0f else 0.35f
    }

    private fun getCurrentPageHolder(): PageViewHolder? {
        val child = (viewPager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(currentPageIndex)
        return child as? PageViewHolder
    }

    private fun updateActiveHolderViewportPadding() {
        val rv = viewPager.getChildAt(0) as? RecyclerView ?: return
        val hPad = (12 * density).toInt()
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? PageViewHolder
            if (holder != null) {
                holder.container.setPadding(hPad, topBarInsetPx, hPad, bottomPanelInsetPx)
                holder.container.post { holder.updateImageVisibleRect() }
            }
        }
    }

    private fun saveAllEditedPages() {
        if (isSaving) return
        isSaving = true

        // Collect updated quad/filter/rotation states from active holder
        getCurrentPageHolder()?.flushCurrentEdits()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (page in pages) {
                    val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = 1800) ?: continue
                    val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
                    val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)

                    // Save fresh rendered thumbnail matching edits
                    val thumbFile = File(page.thumbnailPath)
                    FileOutputStream(thumbFile).use { fos ->
                        val thumbW = 480
                        val thumbH = (thumbW.toFloat() / filtered.width * filtered.height).toInt().coerceAtLeast(100)
                        val thumbBmp = Bitmap.createScaledBitmap(filtered, thumbW, thumbH, true)
                        thumbBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                        thumbBmp.recycle()
                    }

                    if (src != warped) src.recycle()
                    warped.recycle()
                    filtered.recycle()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    onPagesSaved(pages.map { it.copyPage() })
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Inner ViewPager2 Adapter
    // -------------------------------------------------------------
    inner class EditPagerAdapter : RecyclerView.Adapter<PageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val hPad = (12 * density).toInt()
            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                clipToPadding = false
                clipChildren = false
                setPadding(
                    hPad,
                    topBarInsetPx,
                    hPad,
                    bottomPanelInsetPx
                )
            }
            return PageViewHolder(container)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(pages[position])
        }

        override fun getItemCount(): Int = pages.size
    }

    // -------------------------------------------------------------
    // Page View Holder
    // -------------------------------------------------------------
    inner class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        val ivPreview: ImageView
        val cropOverlay: ManualCropOverlayView
        val magnifierLoupe: MagnifierLoupeView

        var rawBitmap: Bitmap? = null
        var orientedBitmap: Bitmap? = null
        var downscaledBitmap: Bitmap? = null
        var displayBitmap: Bitmap? = null
        var boundPage: ScannedPage? = null

        var onBitmapReady: ((Bitmap) -> Unit)? = null
        private var liveScrubJob: Job? = null

        init {
            ivPreview = ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            container.addView(ivPreview)

            magnifierLoupe = MagnifierLoupeView(context).apply {
                val sz = (loupeRadius * 2).toInt()
                layoutParams = LayoutParams(sz, sz)
                visibility = View.GONE
            }

            cropOverlay = ManualCropOverlayView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                onHandleTouchUpdate = { _, screenX, screenY, isTouching, normX, normY ->
                    // Disable ViewPager2 horizontal scrolling during crop handle drag
                    viewPager.isUserInputEnabled = !isTouching
                    if (isTouching) {
                        magnifierLoupe.updatePosition(screenX, screenY, normX, normY, width, height)
                    } else {
                        magnifierLoupe.hide()
                        flushCurrentEdits()
                    }
                }
            }
            container.addView(cropOverlay)
            container.addView(magnifierLoupe)

            ivPreview.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    updateImageVisibleRect()
                }
            })
        }

        fun bind(page: ScannedPage) {
            boundPage = page
            cropOverlay.setQuadFromNormalized(page.cropQuad)

            CoroutineScope(Dispatchers.IO).launch {
                val bmp = storageManager.loadBitmap(page.originalImagePath, maxDimension = 1800)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        rawBitmap = bmp
                        applyTransforms {
                            orientedBitmap?.let { onBitmapReady?.invoke(it) }
                        }
                    }
                }
            }
        }

        fun flushCurrentEdits() {
            boundPage?.let { p ->
                p.cropQuad = cropOverlay.quadCorners.map { PointF(it.x, it.y) }
            }
        }

        fun setFilterMode(mode: FilterMode) {
            boundPage?.filterMode = mode
            applyTransforms()
        }

        fun rotate90() {
            boundPage?.let { p ->
                p.rotationDegrees = (p.rotationDegrees + 90) % 360
                val rotatedQuad = PerspectiveWarper.rotateQuad90CW(cropOverlay.quadCorners)
                cropOverlay.setQuadFromNormalized(rotatedQuad)
                flushCurrentEdits()
                applyTransforms {
                    orientedBitmap?.let { onBitmapReady?.invoke(it) }
                }
            }
        }

        fun rotateCounterClockwise90() {
            boundPage?.let { p ->
                p.rotationDegrees = (p.rotationDegrees + 270) % 360
                val rotatedQuad = PerspectiveWarper.rotateQuad90CCW(cropOverlay.quadCorners)
                cropOverlay.setQuadFromNormalized(rotatedQuad)
                flushCurrentEdits()
                applyTransforms {
                    orientedBitmap?.let { onBitmapReady?.invoke(it) }
                }
            }
        }

        fun autoDetectCrop() {
            val bmp = orientedBitmap ?: rawBitmap ?: return
            val detected = BoundaryDetector.detectDocumentBounds(bmp)
            cropOverlay.setQuadFromNormalized(detected)
            flushCurrentEdits()
            Toast.makeText(context, "Boundary Auto-Detected", Toast.LENGTH_SHORT).show()
        }

        fun resetFullFrameCrop() {
            cropOverlay.resetToFull()
            flushCurrentEdits()
            Toast.makeText(context, "Full Frame Reset", Toast.LENGTH_SHORT).show()
        }

        fun resetCurrentPage() {
            val p = boundPage ?: return
            p.cropQuad = mutableListOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))
            p.rotationDegrees = 0
            p.filterMode = FilterMode.ORIGINAL
            p.filterIntensity = 1.0f
            cropOverlay.resetToFull()
            applyTransforms {
                orientedBitmap?.let { onBitmapReady?.invoke(it) }
            }
            flushCurrentEdits()
            renderToolCategoryUI()
            Toast.makeText(context, "Reset Page ${currentPageIndex + 1} to original", Toast.LENGTH_SHORT).show()
        }

        fun showRawOriginal(show: Boolean) {
            if (show) {
                ivPreview.setImageBitmap(orientedBitmap ?: rawBitmap)
                cropOverlay.visibility = View.GONE
            } else {
                ivPreview.setImageBitmap(displayBitmap ?: orientedBitmap ?: rawBitmap)
                cropOverlay.visibility = View.VISIBLE
            }
        }

        fun applyTransforms(onComplete: (() -> Unit)? = null) {
            val src = rawBitmap ?: return
            val p = boundPage ?: return

            CoroutineScope(Dispatchers.Default).launch {
                val oriented = if (p.rotationDegrees != 0) {
                    val m = Matrix().apply { postRotate(p.rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                } else {
                    src
                }
                orientedBitmap = oriented

                // Prepare downscaled intermediate bitmap (600px width) for 60fps responsive live scrubbing
                val scrubW = 600
                val scrubH = (scrubW.toFloat() / oriented.width * oriented.height).toInt().coerceAtLeast(100)
                downscaledBitmap = Bitmap.createScaledBitmap(oriented, scrubW, scrubH, true)

                val filtered = DocumentFilterEngine.applyFilter(oriented, p.filterMode, p.filterIntensity)

                withContext(Dispatchers.Main) {
                    displayBitmap = filtered
                    ivPreview.setImageBitmap(filtered)
                    magnifierLoupe.setSourceBitmap(filtered)
                    updateImageVisibleRect()
                    onComplete?.invoke()
                }
            }
        }

        fun updateLiveFilterScrub(intensity: Float) {
            val base = downscaledBitmap ?: return
            val p = boundPage ?: return
            val mode = p.filterMode
            if (mode == FilterMode.ORIGINAL) return

            liveScrubJob?.cancel()
            liveScrubJob = CoroutineScope(Dispatchers.Default).launch {
                val fastFiltered = DocumentFilterEngine.applyFilter(base, mode, intensity)
                if (!isActive) {
                    if (fastFiltered != base) fastFiltered.recycle()
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        ivPreview.setImageBitmap(fastFiltered)
                    } else {
                        if (fastFiltered != base) fastFiltered.recycle()
                    }
                }
            }
        }

        fun applyFullFilterIntensity(intensity: Float) {
            liveScrubJob?.cancel()
            val p = boundPage ?: return
            p.filterIntensity = intensity
            applyTransforms()
        }

        fun updateImageVisibleRect() {
            val bmp = displayBitmap ?: rawBitmap ?: return
            val viewW = ivPreview.width.toFloat()
            val viewH = ivPreview.height.toFloat()
            if (viewW <= 0 || viewH <= 0) return

            val srcAspect = bmp.width.toFloat() / bmp.height.toFloat()
            val viewAspect = viewW / viewH

            val rect = if (srcAspect > viewAspect) {
                val dstW = viewW
                val dstH = viewW / srcAspect
                val top = (viewH - dstH) / 2f
                RectF(0f, top, dstW, top + dstH)
            } else {
                val dstH = viewH
                val dstW = viewH * srcAspect
                val left = (viewW - dstW) / 2f
                RectF(left, 0f, left + dstW, dstH)
            }
            cropOverlay.imageVisibleRect = rect
        }
    }
}
