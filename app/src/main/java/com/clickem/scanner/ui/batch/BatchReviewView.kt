package com.clickem.scanner.ui.batch

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.clickem.scanner.R
import com.clickem.scanner.model.DocumentProject
import com.clickem.scanner.model.FilterMode
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.util.cv.DocumentFilterEngine
import com.clickem.scanner.util.cv.PerspectiveWarper
import com.clickem.scanner.util.export.ElongatedImageStitcher
import com.clickem.scanner.util.export.PdfGenerator
import com.clickem.scanner.util.export.ShareHelper
import com.clickem.scanner.util.storage.ScanStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class BatchReviewView(
    context: Context,
    val pages: MutableList<ScannedPage>,
    private val storageManager: ScanStorageManager,
    private val onEditPageRequested: (allPages: MutableList<ScannedPage>, initialIndex: Int) -> Unit,
    private val onResumeCameraRequested: () -> Unit,
    private val onLoadoutRequested: () -> Unit,
    private val existingProject: DocumentProject? = null
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val settingsManager = com.clickem.scanner.util.settings.SettingsManager(context)

    private lateinit var adapter: BatchGridAdapter
    private val progressBar: ProgressBar
    private val tvPageCount: TextView

    init {
        // Prevent touches on blank areas from falling through to underlying screens
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.BLACK)

        val initialSafeTop = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
        val initialSafeBottom = com.clickem.scanner.util.UiInsetsUtil.getSafeBottomInset(context)
        val baseShareMargin = (22 * density).toInt()
        val baseGridBottomPad = (90 * density).toInt()

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // 1. Top Header Bar
        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = 28f * density
                setColor(Color.parseColor("#E60C1018"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
            background = bg
            val pX = (16 * density).toInt()
            val pY = (12 * density).toInt()
            setPadding(pX, pY, pX, pY)
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, initialSafeTop + (8 * density).toInt(), 0, (10 * density).toInt())
            }
        }



        val btnResumeCam = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Close review and return to camera"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
            setColorFilter(Color.parseColor("#F8FAFC"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            setOnClickListener { onResumeCameraRequested() }
        }
        headerBar.addView(btnResumeCam)

        val centerHeaderWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvHeaderTitle = TextView(context).apply {
            text = "REVIEW"
            textSize = 13.5f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
        }
        centerHeaderWrap.addView(tvHeaderTitle)

        tvPageCount = TextView(context).apply {
            text = "${pages.size} ${if (pages.size == 1) "PAGE" else "PAGES"}"
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            val hP = (8 * density).toInt()
            val vP = (3 * density).toInt()
            setPadding(hP, vP, hP, vP)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#1FFFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
        }
        centerHeaderWrap.addView(tvPageCount)
        headerBar.addView(centerHeaderWrap)

        val btnSaveLoadout = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            contentDescription = "Save to Loadout"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
            setColorFilter(Color.parseColor("#090B10"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFFFF"))
            }
            setOnClickListener { promptSaveToLoadout() }
        }
        headerBar.addView(btnSaveLoadout)

        mainLayout.addView(headerBar)

        // 2. Global Quick Filters Row
        val filterScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (8 * density).toInt())
            }
        }

        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hP = (12 * density).toInt()
            setPadding(hP, 0, hP, 0)
        }

        fun createFilterChip(title: String, mode: FilterMode): TextView {
            return TextView(context).apply {
                text = title
                textSize = 11f
                val hP = (12 * density).toInt()
                val vP = (6 * density).toInt()
                setPadding(hP, vP, hP, vP)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                }
                background = bg
                setTextColor(Color.parseColor("#FFFFFF"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener { applyGlobalFilter(mode) }
            }
        }

        filterRow.addView(createFilterChip("All Original", FilterMode.ORIGINAL))
        filterRow.addView(createFilterChip("All Magic Color", FilterMode.MAGIC_COLOR))
        filterRow.addView(createFilterChip("All B/W", FilterMode.BW_CLEAN))
        filterRow.addView(createFilterChip("All Text Pop", FilterMode.TEXT_POP))
        filterRow.addView(createFilterChip("All Gray", FilterMode.GRAYSCALE))
        filterRow.addView(createFilterChip("All Warm Note", FilterMode.WARM_NOTE))
        filterRow.addView(createFilterChip("All Invert", FilterMode.INVERT))
        filterScrollView.addView(filterRow)
        mainLayout.addView(filterScrollView)

        // 3. Page Grid Adapter with Drag-to-Reorder & Long-press Single-Page Export
        adapter = BatchGridAdapter(
            pages = pages,
            onPageClick = { pos ->
                if (pos in pages.indices) {
                    onEditPageRequested(pages, pos)
                }
            },
            onDeleteClick = { pos ->
                deletePageAt(pos)
            },
            onRotateClick = { pos ->
                if (pos in pages.indices) {
                    val page = pages[pos]
                    page.rotationDegrees = (page.rotationDegrees + 90) % 360
                    page.cropQuad = PerspectiveWarper.rotateQuad90CW(page.cropQuad)
                    CoroutineScope(Dispatchers.IO).launch {
                        storageManager.regeneratePageThumbnail(page)
                        if (existingProject != null) {
                            existingProject.pages = pages
                            storageManager.saveProject(existingProject)
                        }
                        withContext(Dispatchers.Main) {
                            adapter.notifyItemChanged(pos)
                        }
                    }
                }
            },
            onPageLongClick = { pos ->
                if (pos in pages.indices) {
                    showSinglePageActionDialog(pos)
                }
            }
        )

        val gridRv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@BatchReviewView.adapter
            clipToPadding = false
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), baseGridBottomPad + initialSafeBottom)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    (viewHolder as? BatchGridAdapter.ViewHolder)?.cancelHoldProgress()
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    adapter.onItemMove(fromPos, toPos)
                    if (existingProject != null) {
                        existingProject.pages = pages
                        CoroutineScope(Dispatchers.IO).launch {
                            storageManager.saveProject(existingProject)
                        }
                    }
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(gridRv)
        adapter.onRequestDrag = { vh -> touchHelper.startDrag(vh) }
        mainLayout.addView(gridRv)

        // 4. Bottom Unified "Share Document" Action Bar (Requirement 3: Single button with modal options)
        val shareBar = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, baseShareMargin + initialSafeBottom)
            }
        }

        val btnUnifiedShare = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hP = (24 * density).toInt()
            val vP = (14 * density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 28f * density
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            elevation = 10f * density

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_share)
                val sz = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (10 * density).toInt()
                }
                setColorFilter(Color.BLACK)
            }
            addView(icon)

            val tvShare = TextView(context).apply {
                text = "Share Document"
                textSize = 15f
                setTextColor(Color.BLACK)
                paint.isFakeBoldText = true
            }
            addView(tvShare)

            setOnClickListener {
                showShareFormatModalSheet()
            }
        }
        shareBar.addView(btnUnifiedShare)

        addView(mainLayout)
        addView(shareBar)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val statusBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val safeMin = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
            val topInset = maxOf(statusBars.top, safeMin)
            (headerBar.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.topMargin = topInset + (8 * density).toInt()
                headerBar.layoutParams = lp
            }

            val navBottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
            ).bottom
            val safeBottom = maxOf(navBottom, com.clickem.scanner.util.UiInsetsUtil.getSafeBottomInset(context))
            (shareBar.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = baseShareMargin + safeBottom
                shareBar.layoutParams = lp
            }
            gridRv.setPadding(
                (12 * density).toInt(),
                0,
                (12 * density).toInt(),
                baseGridBottomPad + safeBottom
            )
            insets
        }

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        addView(progressBar)
    }

    private fun deletePageAt(pos: Int) {
        if (pos !in pages.indices) return
        pages.removeAt(pos)
        adapter.notifyItemRemoved(pos)
        if (pos < pages.size) {
            adapter.notifyItemRangeChanged(pos, pages.size - pos)
        }
        updateHeaderCount()
        if (existingProject != null) {
            existingProject.pages = pages
            CoroutineScope(Dispatchers.IO).launch {
                if (pages.isEmpty()) {
                    storageManager.deleteProject(existingProject.id)
                } else {
                    storageManager.saveProject(existingProject)
                }
            }
        }
        if (pages.isEmpty()) onResumeCameraRequested()
    }

    private fun updateHeaderCount() {
        val count = pages.size
        tvPageCount.text = "$count ${if (count == 1) "PAGE" else "PAGES"}"
    }

    private fun applyGlobalFilter(mode: FilterMode) {
        for (page in pages) {
            page.filterMode = mode
        }
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            for (page in pages) {
                storageManager.regeneratePageThumbnail(page)
            }
            if (existingProject != null) {
                existingProject.pages = pages
                storageManager.saveProject(existingProject)
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                adapter.notifyDataSetChanged()
                Toast.makeText(context, "Applied ${mode.name} to all pages", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun notifyPagesUpdated() {
        adapter.notifyDataSetChanged()
        updateHeaderCount()
    }

    private fun promptSaveToLoadout() {
        if (pages.isEmpty()) return
        val defaultName = existingProject?.title ?: ("Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()))
        val dialogTitle = if (existingProject != null) "Update Document" else "Save to Loadout"

        showNameDialog(dialogTitle, defaultName) { chosenName ->
            progressBar.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                val projToSave = if (existingProject != null) {
                    existingProject.title = chosenName
                    existingProject.pages = pages.map { it.copyPage() }.toMutableList()
                    existingProject.updatedAt = System.currentTimeMillis()
                    existingProject
                } else {
                    DocumentProject(
                        title = chosenName,
                        pages = pages.map { it.copyPage() }.toMutableList()
                    )
                }
                storageManager.saveProject(projToSave)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(context, if (existingProject != null) "Updated ${projToSave.title}" else "Saved to Loadout!", Toast.LENGTH_SHORT).show()
                    onLoadoutRequested()
                }
            }
        }
    }

    /**
     * Requirement 3: Unified Share Dialog offering 3 distinct formats:
     * 1) PDF Document (Borderless)
     * 2) Elongated Continuous Image
     * 3) Separate Images
     */
    private fun showShareFormatModalSheet() {
        if (pages.isEmpty()) return

        val defaultName = existingProject?.title
            ?: ("ClickEm_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()))
        var dialog: AlertDialog? = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
        }

        val tvTitle = TextView(context).apply {
            text = "Share Scanned Session"
            textSize = 17f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close share dialog"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#15FFFFFF"))
            }
            background = bg
            setColorFilter(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnClose)
        container.addView(headerRow)

        val etDocName = EditText(context).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            setTextColor(Color.parseColor("#f8fafc"))
            setHintTextColor(Color.parseColor("#64748b"))
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#090B10"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (14 * density).toInt())
            }
        }
        container.addView(etDocName)

        fun createFormatRow(title: String, subtitle: String, iconRes: Int, onAction: (String) -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (10 * density).toInt())
                }

                val icon = ImageView(context).apply {
                    setImageResource(iconRes)
                    contentDescription = "$title format"
                    val isz = (26 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(isz, isz).apply {
                        marginEnd = (12 * density).toInt()
                    }
                    setColorFilter(Color.parseColor("#FFFFFF"))
                }
                addView(icon)

                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvT = TextView(context).apply {
                    text = title
                    textSize = 13f
                    setTextColor(Color.parseColor("#f8fafc"))
                    paint.isFakeBoldText = true
                }
                col.addView(tvT)

                val tvSub = TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Color.parseColor("#8c96a5"))
                }
                col.addView(tvSub)
                addView(col)

                setOnClickListener {
                    val name = etDocName.text.toString().trim().ifEmpty { defaultName }
                    dialog?.dismiss()
                    onAction(name)
                }
            }
        }

        container.addView(createFormatRow("PDF Document", "Borderless edge-to-edge document", R.drawable.ic_pdf) { name ->
            exportToPdf(name)
        })

        container.addView(createFormatRow("Elongated Image", "Single vertically continuous receipt/contract", R.drawable.ic_stitch) { name ->
            exportToElongatedImage(name)
        })

        container.addView(createFormatRow("Separate Images", "High-res individual JPEG image files", R.drawable.ic_gallery) { name ->
            exportIndividualImages(name)
        })

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showNameDialog(title: String, defaultName: String, onConfirm: (String) -> Unit) {
        var dialog: AlertDialog? = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        val tvTitle = TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close dialog"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#15FFFFFF"))
            }
            background = bg
            setColorFilter(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnClose)
        container.addView(headerRow)

        val input = EditText(context).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            val pX = (16 * density).toInt()
            val pY = (14 * density).toInt()
            setPadding(pX, pY, pX, pY)
            val bg = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#090B10"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (20 * density).toInt()
            }
        }
        container.addView(input)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCancel = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Cancel save"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.parseColor("#8C96A5"))
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (12 * density).toInt()
            }
            setOnClickListener { dialog?.dismiss() }
        }
        btnRow.addView(btnCancel)

        val btnConfirm = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            contentDescription = "Confirm save"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.parseColor("#090B10"))
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener {
                val name = input.text.toString().trim().ifEmpty { defaultName }
                dialog?.dismiss()
                onConfirm(name)
            }
        }
        btnRow.addView(btnConfirm)
        container.addView(btnRow)

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /**
     * Requirement 5: Memory-safe Borderless PDF Generation (0% white margins)
     */
    private fun exportToPdf(docName: String) {
        if (pages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val exportDir = storageManager.exportsDir
            val outputFile = File(exportDir, "${docName}.pdf")

            val document = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var success = false

            try {
                for (i in pages.indices) {
                    val page = pages[i]
                    val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = quality.maxDimension) ?: continue
                    val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
                    val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)

                    if (src != warped) src.recycle()
                    if (warped != filtered) warped.recycle()

                    // Use exact pixel dimensions for 100% native quality with zero border margins
                    val pageW = filtered.width
                    val pageH = filtered.height

                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                    val pdfPage = document.startPage(pageInfo)
                    val canvas: Canvas = pdfPage.canvas

                    // Zero-margin borderless fill at 1:1 pixel fidelity
                    canvas.drawBitmap(filtered, 0f, 0f, paint)
                    document.finishPage(pdfPage)
                    filtered.recycle()
                }

                FileOutputStream(outputFile).use { fos ->
                    document.writeTo(fos)
                }
                success = true
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                try { document.close() } catch (_: Exception) {}
            }

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "application/pdf", "${docName}.pdf")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "application/pdf", "Share $docName")
                    showPostSharePrompt()
                } else {
                    Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportToElongatedImage(docName: String) {
        if (pages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val maxElongated = when (quality) {
                com.clickem.scanner.util.settings.ExportQuality.HIGH -> 3600
                com.clickem.scanner.util.settings.ExportQuality.MEDIUM -> 2400
                com.clickem.scanner.util.settings.ExportQuality.LOW -> 1600
            }
            val bitmaps = mutableListOf<Bitmap>()
            for (page in pages) {
                val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = maxElongated) ?: continue
                val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
                val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)
                if (src != warped) src.recycle()
                if (warped != filtered) warped.recycle()
                bitmaps.add(filtered)
            }

            val exportDir = storageManager.exportsDir
            val outputFile = File(exportDir, "${docName}_stitched.jpg")
            val success = ElongatedImageStitcher.stitchPagesVertically(bitmaps, outputFile)

            bitmaps.forEach { it.recycle() }

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "image/jpeg", "${docName}_stitched.jpg")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "image/jpeg", "Share $docName")
                    showPostSharePrompt()
                } else {
                    Toast.makeText(context, "Failed to stitch pages", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportIndividualImages(docName: String) {
        if (pages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val exportDir = storageManager.exportsDir
            val files = mutableListOf<File>()

            for (i in pages.indices) {
                val page = pages[i]
                val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = quality.maxDimension) ?: continue
                val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
                val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)
                if (src != warped) src.recycle()
                if (warped != filtered) warped.recycle()

                val f = File(exportDir, "${docName}_Page_${i + 1}.jpg")
                FileOutputStream(f).use { fos ->
                    filtered.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, fos)
                }
                filtered.recycle()
                files.add(f)
                storageManager.exportToPublicStorage(f, "image/jpeg", f.name)
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                ShareHelper.shareMultipleFiles(context, files, "image/jpeg", "Share Images")
                showPostSharePrompt()
            }
        }
    }

    private fun showPostSharePrompt() {
        if (existingProject != null) return
        var dialog: AlertDialog? = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (22 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
        }

        val tvTitle = TextView(context).apply {
            text = "Document Shared"
            textSize = 18f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(tvTitle)

        val tvMsg = TextView(context).apply {
            text = "Your document was successfully shared. How would you like to handle this scan session?"
            textSize = 13f
            setTextColor(Color.parseColor("#8C96A5"))
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        container.addView(tvMsg)

        fun createPromptAction(label: String, isPrimary: Boolean, isOutline: Boolean, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                textSize = 13.5f
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
                val bg = GradientDrawable().apply {
                    cornerRadius = 20f * density
                    when {
                        isPrimary -> {
                            setColor(Color.parseColor("#FFFFFF"))
                        }
                        isOutline -> {
                            setColor(Color.parseColor("#15FFFFFF"))
                            setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                        }
                        else -> {
                            setColor(Color.TRANSPARENT)
                        }
                    }
                }
                background = bg
                setTextColor(
                    when {
                        isPrimary -> Color.parseColor("#090B10")
                        isOutline -> Color.parseColor("#F8FAFC")
                        else -> Color.parseColor("#8C96A5")
                    }
                )
                setOnClickListener {
                    dialog?.dismiss()
                    onClick()
                }
            }
        }

        container.addView(createPromptAction("Clear & Return to Loadout", isPrimary = true, isOutline = false) {
            onLoadoutRequested()
        })

        container.addView(createPromptAction("Save to Loadout Archive", isPrimary = false, isOutline = true) {
            promptSaveToLoadout()
        })

        container.addView(createPromptAction("Keep Session Open", isPrimary = false, isOutline = false) {
            // Dismiss only
        })

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /**
     * Requirement 4: Hold it to see option to just share that single image as pdf or image
     */
    private fun showSinglePageActionDialog(pos: Int) {
        val page = pages[pos]
        var dialog: AlertDialog? = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        val tvTitle = TextView(context).apply {
            text = "Page ${pos + 1} Actions"
            textSize = 17f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close page actions"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#15FFFFFF"))
            }
            background = bg
            setColorFilter(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnClose)
        container.addView(headerRow)

        fun createActionRow(title: String, iconRes: Int, isDestructive: Boolean = false, onClick: () -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (13 * density).toInt()
                setPadding(p, p, p, p)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (isDestructive) Color.parseColor("#15F43F5E") else Color.parseColor("#15FFFFFF"))
                    setStroke(
                        (1 * density).toInt(),
                        if (isDestructive) Color.parseColor("#40F43F5E") else Color.parseColor("#25FFFFFF")
                    )
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }

                val iv = ImageView(context).apply {
                    setImageResource(iconRes)
                    val isz = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(isz, isz).apply {
                        marginEnd = (14 * density).toInt()
                    }
                    setColorFilter(if (isDestructive) Color.parseColor("#F43F5E") else Color.parseColor("#FFFFFF"))
                }
                addView(iv)

                val tv = TextView(context).apply {
                    text = title
                    textSize = 13.5f
                    setTextColor(if (isDestructive) Color.parseColor("#F43F5E") else Color.parseColor("#F8FAFC"))
                    paint.isFakeBoldText = true
                }
                addView(tv)

                setOnClickListener {
                    dialog?.dismiss()
                    onClick()
                }
            }
        }

        container.addView(createActionRow("Share Page as PDF", R.drawable.ic_pdf) {
            shareSinglePagePdf(page, "Page_${pos + 1}")
        })

        container.addView(createActionRow("Share Page as Image", R.drawable.ic_gallery) {
            shareSinglePageImage(page, "Page_${pos + 1}")
        })

        container.addView(createActionRow("Edit Page", R.drawable.ic_edit) {
            onEditPageRequested(pages, pos)
        })

        container.addView(createActionRow("Delete Page", R.drawable.ic_delete, isDestructive = true) {
            deletePageAt(pos)
        })

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun shareSinglePagePdf(page: ScannedPage, docName: String) {
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = quality.maxDimension)
            if (src == null) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
            val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)
            if (src != warped) src.recycle()
            if (warped != filtered) warped.recycle()

            val outputFile = File(storageManager.exportsDir, "${docName}.pdf")
            val success = PdfGenerator.createSinglePagePdf(filtered, outputFile)
            filtered.recycle()

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "application/pdf", "${docName}.pdf")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "application/pdf", "Share $docName")
                } else {
                    Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareSinglePageImage(page: ScannedPage, docName: String) {
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = quality.maxDimension)
            if (src == null) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
            val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)
            if (src != warped) src.recycle()
            if (warped != filtered) warped.recycle()

            val outputFile = File(storageManager.exportsDir, "${docName}.jpg")
            var success = false
            try {
                FileOutputStream(outputFile).use { fos ->
                    filtered.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, fos)
                }
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                filtered.recycle()
            }

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "image/jpeg", "${docName}.jpg")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "image/jpeg", "Share $docName")
                } else {
                    Toast.makeText(context, "Failed to export image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
