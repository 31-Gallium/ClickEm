package com.clickem.scanner.ui.batch

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.clickem.scanner.util.BackPressHandler

class BatchReviewView(
    context: Context,
    val pages: MutableList<ScannedPage>,
    private val storageManager: ScanStorageManager,
    private val onEditPageRequested: (allPages: MutableList<ScannedPage>, initialIndex: Int) -> Unit,
    private val onResumeCameraRequested: () -> Unit,
    private val onLoadoutRequested: () -> Unit,
    private val existingProject: DocumentProject? = null,
    private val onSnapNewPagesRequested: (() -> Unit)? = null
) : FrameLayout(context), BackPressHandler {

    private val density = context.resources.displayMetrics.density
    private val settingsManager = com.clickem.scanner.util.settings.SettingsManager(context)

    private lateinit var adapter: BatchGridAdapter
    private val progressBar: ProgressBar
    private val tvPageCount: TextView
    private val tvHeaderTitle: TextView
    private val btnResumeCam: ImageView
    private lateinit var btnSnapPage: ImageView
    private val btnReorderPages: ImageView
    private val btnUnifiedShare: LinearLayout
    private val selectionDock: LinearLayout
    private lateinit var moveOverlayBar: LinearLayout
    data class PageOrderSnapshot(
        val pages: List<ScannedPage>,
        val selectedPageIds: Set<String>
    )

    private var btnMoveUndo: ImageView? = null
    private val moveHistory = mutableListOf<PageOrderSnapshot>()
    private val undoBar: LinearLayout
    private val tvUndoMessage: TextView
    private val btnUndoAction: TextView
    private var undoDismissRunnable: Runnable? = null
    private var lastSavedPagesForUndo: List<ScannedPage>? = null
    private var lastSavedSelectedForUndo: Set<Int>? = null

    override fun handleBackPressed(): Boolean {
        if (moveOverlayBar.visibility == View.VISIBLE) {
            hideMoveOverlay()
            return true
        }
        if (::adapter.isInitialized && adapter.isSelectionMode()) {
            adapter.clearSelection()
            return true
        }
        return false
    }

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
                setColor(Color.parseColor("#E6000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
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

        btnResumeCam = ImageView(context).apply {
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
            setOnClickListener {
                if (::adapter.isInitialized && adapter.isSelectionMode()) {
                    adapter.clearSelection()
                } else {
                    onResumeCameraRequested()
                }
            }
        }
        headerBar.addView(btnResumeCam)

        val centerHeaderWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvHeaderTitle = TextView(context).apply {
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

        btnSnapPage = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            contentDescription = "Snap new page"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginEnd = (8 * density).toInt()
            }
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
            visibility = if (onSnapNewPagesRequested != null) View.VISIBLE else View.GONE
            setOnClickListener {
                onSnapNewPagesRequested?.invoke()
            }
        }
        headerBar.addView(btnSnapPage)

        btnReorderPages = ImageView(context).apply {
            setImageResource(R.drawable.ic_grid)
            contentDescription = "Open reorder grid"
            val btnSz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginEnd = (8 * density).toInt()
            }
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
            visibility = if (pages.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { openReorderCanvas() }
        }
        headerBar.addView(btnReorderPages)

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
                setOnClickListener {
                    if (::adapter.isInitialized && adapter.isSelectionMode()) {
                        applyFilterToPositions(mode, adapter.selectedPositions.toSet())
                    } else {
                        applyGlobalFilter(mode)
                    }
                }
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

        // 3. Page Grid Adapter with Drag-to-Reorder & Selection Support
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
                        withContext(Dispatchers.Main) {
                            adapter.notifyItemChanged(pos)
                        }
                    }
                }
            },
            onPageLongClick = { pos ->
                if (pos in pages.indices) {
                    adapter.toggleSelection(pos)
                }
            }
        )
        adapter.onSelectionChanged = {
            updateSelectionUi()
        }

        val gridRv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@BatchReviewView.adapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            clipToPadding = false
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), baseGridBottomPad + initialSafeBottom)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topGridFade = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (28 * density).toInt()
            ).apply {
                gravity = Gravity.TOP
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F2000000"), Color.parseColor("#99000000"), Color.TRANSPARENT)
            )
            isClickable = false
            isFocusable = false
        }

        val bottomGridFade = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (110 * density).toInt() + initialSafeBottom
            ).apply {
                gravity = Gravity.BOTTOM
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#99000000"), Color.parseColor("#F2000000"))
            )
            isClickable = false
            isFocusable = false
        }

        val gridContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(gridRv)
            addView(topGridFade)
            addView(bottomGridFade)
        }

        var preDragPages = mutableListOf<ScannedPage>()
        var preDragSelected = mutableSetOf<Int>()
        var bulkDraggedPages = ArrayList<ScannedPage>()
        var isBulkDragging = false
        var dragTargetPos = -1
        var dragStartPos = -1

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val vh = viewHolder as? BatchGridAdapter.ViewHolder
                    vh?.cancelHoldProgress()
                    val startPos = viewHolder?.bindingAdapterPosition ?: -1
                    dragStartPos = startPos
                    dragTargetPos = startPos
                    preDragPages = ArrayList(pages)
                    preDragSelected = HashSet(adapter.selectedPositions)

                    if (startPos != -1 && adapter.selectedPositions.contains(startPos) && adapter.selectedPositions.size > 1) {
                        isBulkDragging = true
                        val sortedSelected = adapter.selectedPositions.sorted()
                        val count = sortedSelected.size
                        bulkDraggedPages = ArrayList(sortedSelected.map { preDragPages[it] })

                        vh?.let {
                            it.cascadeLayer1.visibility = View.VISIBLE
                            if (count >= 3) it.cascadeLayer2.visibility = View.VISIBLE
                            if (count >= 4) it.cascadeLayer3.visibility = View.VISIBLE
                            if (count >= 5) it.cascadeLayer4.visibility = View.VISIBLE
                            it.tvStackCountBadge.visibility = View.VISIBLE
                            it.tvStackCountBadge.text = "$count"
                            it.tvBadge.text = "PAGE ${startPos + 1}"
                            it.root.elevation = 24f * density
                        }

                        // Ghost the remaining selected items smoothly without removing them from adapter
                        val tailPositions = sortedSelected.filter { it != startPos }.toSet()
                        adapter.ghostPositions = tailPositions
                        for (i in 0 until gridRv.childCount) {
                            val child = gridRv.getChildAt(i) ?: continue
                            val childHolder = gridRv.getChildViewHolder(child) as? BatchGridAdapter.ViewHolder ?: continue
                            val childPos = childHolder.bindingAdapterPosition
                            if (childPos in tailPositions) {
                                child.animate().alpha(0.35f).scaleX(0.94f).scaleY(0.94f).setDuration(160).start()
                            }
                        }
                    } else {
                        isBulkDragging = false
                        bulkDraggedPages.clear()
                        adapter.ghostPositions = emptySet()
                    }
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
                    dragTargetPos = toPos
                    adapter.onItemMove(fromPos, toPos)

                    if (!isBulkDragging) {
                        for (i in 0 until recyclerView.childCount) {
                            val child = recyclerView.getChildAt(i) ?: continue
                            val holder = recyclerView.getChildViewHolder(child) as? BatchGridAdapter.ViewHolder ?: continue
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                holder.tvBadge.text = "PAGE ${pos + 1}"
                            }
                        }
                    } else {
                        val headPos = viewHolder.bindingAdapterPosition
                        (viewHolder as? BatchGridAdapter.ViewHolder)?.tvBadge?.text = "PAGE ${headPos + 1}"
                    }
                }
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                (viewHolder as? BatchGridAdapter.ViewHolder)?.let { vh ->
                    vh.cascadeLayer1.visibility = View.GONE
                    vh.cascadeLayer2.visibility = View.GONE
                    vh.cascadeLayer3.visibility = View.GONE
                    vh.cascadeLayer4.visibility = View.GONE
                    vh.tvStackCountBadge.visibility = View.GONE
                    vh.root.elevation = 0f
                }

                adapter.ghostPositions = emptySet()

                // Reset visual transforms on all items
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i) ?: continue
                    child.animate().cancel()
                    child.translationX = 0f
                    child.translationY = 0f
                    child.scaleX = 1f
                    child.scaleY = 1f
                    child.alpha = 1f
                    child.rotation = 0f
                }
                viewHolder.itemView.animate().cancel()
                viewHolder.itemView.translationX = 0f
                viewHolder.itemView.translationY = 0f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.rotation = 0f

                if (isBulkDragging && preDragSelected.isNotEmpty()) {
                    val dropPos = if (dragTargetPos != -1) dragTargetPos.coerceIn(0, pages.size - 1) else dragStartPos.coerceIn(0, pages.size - 1)

                    val selectedPages = preDragSelected.sorted().map { preDragPages[it] }
                    val unselectedPages = preDragPages.filterIndexed { index, _ -> !preDragSelected.contains(index) }

                    val dropPage = pages.getOrNull(dropPos)
                    val insertIndex = if (dropPage != null && dropPage !in selectedPages) {
                        val idx = unselectedPages.indexOf(dropPage)
                        if (idx != -1) idx else unselectedPages.size
                    } else {
                        dropPos.coerceIn(0, unselectedPages.size)
                    }

                    val newPages = ArrayList<ScannedPage>()
                    newPages.addAll(unselectedPages.subList(0, insertIndex))
                    newPages.addAll(selectedPages)
                    newPages.addAll(unselectedPages.subList(insertIndex, unselectedPages.size))

                    val changed = (newPages != preDragPages)
                    pages.clear()
                    pages.addAll(newPages)

                    adapter.selectedPositions.clear()
                    for (i in insertIndex until insertIndex + selectedPages.size) {
                        adapter.selectedPositions.add(i)
                    }
                    adapter.notifyDataSetChanged()
                    updateSelectionUi()

                    if (changed) {
                        if (::moveOverlayBar.isInitialized && moveOverlayBar.visibility == View.VISIBLE) {
                            val preIds = preDragSelected.filter { it in preDragPages.indices }.map { preDragPages[it].id }.toSet()
                            if (moveHistory.size >= 25) moveHistory.removeAt(0)
                            moveHistory.add(PageOrderSnapshot(ArrayList(preDragPages), preIds))
                            updateUndoButtonState()
                        } else {
                            showUndoBar("Moved ${selectedPages.size} pages", ArrayList(preDragPages), HashSet(preDragSelected))
                        }
                    }
                    isBulkDragging = false
                    dragTargetPos = -1
                    dragStartPos = -1
                    bulkDraggedPages.clear()
                } else if (!isBulkDragging && dragStartPos != -1 && dragTargetPos != -1 && dragStartPos != dragTargetPos) {
                    adapter.notifyDataSetChanged()
                    val changed = (pages != preDragPages)
                    if (changed) {
                        if (::moveOverlayBar.isInitialized && moveOverlayBar.visibility == View.VISIBLE) {
                            val preIds = preDragSelected.filter { it in preDragPages.indices }.map { preDragPages[it].id }.toSet()
                            if (moveHistory.size >= 25) moveHistory.removeAt(0)
                            moveHistory.add(PageOrderSnapshot(ArrayList(preDragPages), preIds))
                            updateUndoButtonState()
                        } else {
                            showUndoBar("Moved 1 page", ArrayList(preDragPages), HashSet(preDragSelected))
                        }
                    }
                    dragTargetPos = -1
                    dragStartPos = -1
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(gridRv)
        adapter.onRequestDrag = { vh -> touchHelper.startDrag(vh) }
        mainLayout.addView(gridContainer)

        // 4. Bottom Action Bar: Normal Unified Share & 3-Button Selection Dock
        val shareBar = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, baseShareMargin + initialSafeBottom)
            }
        }

        btnUnifiedShare = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hP = (24 * density).toInt()
            val vP = (14 * density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 28f * density
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
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
                showShareFormatModalSheet(pages)
            }
        }
        shareBar.addView(btnUnifiedShare)

        // 3-Button Floating Pill Dock when images are selected (Share / Rotate / Delete)
        selectionDock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hP = (8 * density).toInt()
            val vP = (6 * density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 28f * density
                setColor(Color.parseColor("#E6000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
            elevation = 10f * density
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)

            // Button 1: Share
            val btnDockShare = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                contentDescription = "Share selected pages"
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 22f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#38FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                    marginEnd = (6 * density).toInt()
                }
                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_share)
                    val sz = (18 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (4 * density).toInt()
                    }
                    setColorFilter(Color.WHITE)
                }
                addView(icon)
                val text = TextView(context).apply {
                    text = "Share"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                }
                addView(text)
                setOnClickListener {
                    showSelectionShareDialog()
                }
            }
            addView(btnDockShare)

            // Button 2: Rotate
            val btnDockRotate = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                contentDescription = "Rotate page 90 degrees"
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 22f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#38FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                    marginEnd = (6 * density).toInt()
                }
                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_rotate)
                    val sz = (18 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (4 * density).toInt()
                    }
                    setColorFilter(Color.WHITE)
                }
                addView(icon)
                val text = TextView(context).apply {
                    text = "Rotate"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                }
                addView(text)
                setOnClickListener {
                    rotateSelectedPages()
                }
            }
            addView(btnDockRotate)

            // Button 3: Move
            val btnDockMove = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                contentDescription = "Move selected pages"
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 22f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#38FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                    marginEnd = (6 * density).toInt()
                }
                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_stitch)
                    val sz = (18 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (4 * density).toInt()
                    }
                    setColorFilter(Color.WHITE)
                }
                addView(icon)
                val text = TextView(context).apply {
                    text = "Move"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                }
                addView(text)
                setOnClickListener {
                    toggleMoveOverlay()
                }
            }
            addView(btnDockMove)

            // Button 4: Delete
            val btnDockDelete = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                contentDescription = "Delete page"
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 22f * density
                    setColor(Color.parseColor("#25F43F5E"))
                    setStroke((1 * density).toInt(), Color.parseColor("#60F43F5E"))
                }
                layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f)
                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_delete)
                    val sz = (18 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (4 * density).toInt()
                    }
                    setColorFilter(Color.parseColor("#F43F5E"))
                }
                addView(icon)
                val text = TextView(context).apply {
                    text = "Delete"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#F43F5E"))
                    paint.isFakeBoldText = true
                }
                addView(text)
                setOnClickListener {
                    deleteSelectedPages()
                }
            }
            addView(btnDockDelete)
        }
        shareBar.addView(selectionDock)

        // 4B. Compact Move Overlay Bar positioned above selectionDock
        moveOverlayBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hP = (8 * density).toInt()
            val vP = (6 * density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 28f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F5000000"),
                    Color.parseColor("#E6000000")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
            elevation = 16f * density
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = baseShareMargin + initialSafeBottom + (60 * density).toInt()
            }
            visibility = View.GONE

            fun createMoveButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
                val sz = (42 * density).toInt()
                val isz = (20 * density).toInt()
                return ImageView(context).apply {
                    setImageResource(iconRes)
                    contentDescription = desc
                    setColorFilter(Color.WHITE)
                    val p = (sz - isz) / 2
                    setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        val m = (3 * density).toInt()
                        setMargins(m, 0, m, 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#15FFFFFF"))
                        setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                    }
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onClick()
                    }
                }
            }

            // Button 1: To Start
            addView(createMoveButton(R.drawable.ic_first_page, "Move to start") {
                moveSelectedPagesToStart()
            })

            // Button 2: Shift Earlier (-1)
            addView(createMoveButton(R.drawable.ic_chevron_left, "Shift earlier") {
                shiftSelectedPages(-1)
            })

            // Button 3: Reorder Grid Canvas
            addView(createMoveButton(R.drawable.ic_grid, "Open reorder grid") {
                hideMoveOverlay()
                openReorderCanvas()
            })

            // Button 4: Shift Later (+1)
            addView(createMoveButton(R.drawable.ic_chevron_right, "Shift later") {
                shiftSelectedPages(1)
            })

            // Button 5: To End
            addView(createMoveButton(R.drawable.ic_last_page, "Move to end") {
                moveSelectedPagesToEnd()
            })

            // Button 6: Undo Move
            val undoBtn = createMoveButton(R.drawable.ic_undo, "Undo move") {
                if (moveHistory.isNotEmpty()) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val snapshot = moveHistory.removeAt(moveHistory.size - 1)
                    pages.clear()
                    pages.addAll(snapshot.pages)

                    // Keep holding to the correct images!
                    adapter.selectedPositions.clear()
                    for (i in pages.indices) {
                        if (pages[i].id in snapshot.selectedPageIds) {
                            adapter.selectedPositions.add(i)
                        }
                    }
                    adapter.notifyDataSetChanged()
                    updateHeaderCount()
                    updateSelectionUi()
                    updateUndoButtonState()
                }
            }
            btnMoveUndo = undoBtn
            undoBtn.alpha = 0.4f
            undoBtn.isEnabled = false
            addView(undoBtn)

            // Button 7: Close Move Overlay
            val btnCloseMove = ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = "Close move panel"
                val sz = (36 * density).toInt()
                val isz = (16 * density).toInt()
                val p = (sz - isz) / 2
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    val m = (3 * density).toInt()
                    setMargins(m, 0, (2 * density).toInt(), 0)
                }
                setColorFilter(Color.parseColor("#8C96A5"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#10FFFFFF"))
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    hideMoveOverlay()
                }
            }
            addView(btnCloseMove)
        }

        // 5. Floating Undo Notification Bar
        undoBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (18 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#F0000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            background = bg
            elevation = 20f * density
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = baseShareMargin + initialSafeBottom + (68 * density).toInt()
            }
            visibility = View.GONE
        }

        tvUndoMessage = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        undoBar.addView(tvUndoMessage)

        val tvUndoDot = TextView(context).apply {
            text = " • "
            textSize = 13f
            setTextColor(Color.parseColor("#8C96A5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        undoBar.addView(tvUndoDot)

        btnUndoAction = TextView(context).apply {
            text = "UNDO"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            letterSpacing = 0.06f
            val hP = (12 * density).toInt()
            val vP = (4 * density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(Color.parseColor("#25FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#40FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val origPages = lastSavedPagesForUndo
                val origSelected = lastSavedSelectedForUndo
                hideUndoBar()
                if (origPages != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    pages.clear()
                    pages.addAll(origPages)
                    adapter.selectedPositions.clear()
                    if (origSelected != null) {
                        val origIds = origSelected.filter { it in origPages.indices }.map { origPages[it].id }.toSet()
                        for (i in pages.indices) {
                            if (pages[i].id in origIds) {
                                adapter.selectedPositions.add(i)
                            }
                        }
                    }
                    adapter.notifyDataSetChanged()
                    updateSelectionUi()
                    Toast.makeText(context, "Page order restored", Toast.LENGTH_SHORT).show()
                }
            }
        }
        undoBar.addView(btnUndoAction)

        addView(mainLayout)
        addView(shareBar)
        addView(moveOverlayBar)
        addView(undoBar)

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
            (moveOverlayBar.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = baseShareMargin + safeBottom + (60 * density).toInt()
                moveOverlayBar.layoutParams = lp
            }
            (undoBar.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = baseShareMargin + safeBottom + (68 * density).toInt()
                undoBar.layoutParams = lp
            }
            bottomGridFade.layoutParams.height = (110 * density).toInt() + safeBottom
            bottomGridFade.requestLayout()
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

    private fun showUndoBar(message: String, originalPages: List<ScannedPage>, originalSelected: Set<Int>) {
        lastSavedPagesForUndo = ArrayList(originalPages)
        lastSavedSelectedForUndo = HashSet(originalSelected)
        tvUndoMessage.text = message

        undoDismissRunnable?.let { removeCallbacks(it) }

        undoBar.animate().cancel()
        undoBar.alpha = 0f
        undoBar.scaleX = 0.9f
        undoBar.scaleY = 0.9f
        undoBar.visibility = View.VISIBLE
        undoBar.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        val r = Runnable { hideUndoBar() }
        undoDismissRunnable = r
        postDelayed(r, 4500L)
    }

    private fun hideUndoBar() {
        undoDismissRunnable?.let { removeCallbacks(it) }
        undoDismissRunnable = null
        undoBar.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(180)
            .withEndAction {
                undoBar.visibility = View.GONE
            }
            .start()
    }

    fun updateSelectionUi() {
        if (!::adapter.isInitialized) return
        if (adapter.isSelectionMode()) {
            val count = adapter.selectedPositions.size
            tvHeaderTitle.text = "$count Selected"
            tvPageCount.visibility = View.GONE
            btnResumeCam.setImageResource(R.drawable.ic_close)
            btnResumeCam.contentDescription = "Cancel selection"
            btnUnifiedShare.visibility = View.GONE
            selectionDock.visibility = View.VISIBLE
            btnSnapPage.visibility = View.GONE
            btnReorderPages.visibility = View.GONE
        } else {
            hideMoveOverlay()
            tvHeaderTitle.text = "REVIEW"
            tvPageCount.visibility = View.VISIBLE
            updateHeaderCount()
            btnResumeCam.setImageResource(R.drawable.ic_back)
            btnResumeCam.contentDescription = "Close review and return to camera"
            btnUnifiedShare.visibility = View.VISIBLE
            selectionDock.visibility = View.GONE
            btnSnapPage.visibility = if (onSnapNewPagesRequested != null) View.VISIBLE else View.GONE
            btnReorderPages.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
        }
    }

    private fun showSelectionShareDialog() {
        val selected = adapter.selectedPositions.sorted().map { pages[it] }
        if (selected.isNotEmpty()) {
            showShareFormatModalSheet(selected)
        }
    }

    private fun rotateSelectedPages() {
        val selected = adapter.selectedPositions.toList().sorted()
        if (selected.isEmpty()) return
        progressBar.visibility = View.VISIBLE
        for (pos in selected) {
            if (pos in pages.indices) {
                val page = pages[pos]
                page.rotationDegrees = (page.rotationDegrees + 90) % 360
                page.cropQuad = PerspectiveWarper.rotateQuad90CW(page.cropQuad)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            for (pos in selected) {
                if (pos in pages.indices) {
                    storageManager.regeneratePageThumbnail(pages[pos])
                }
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                for (pos in selected) {
                    adapter.notifyItemChanged(pos)
                }
                Toast.makeText(context, "Rotated ${selected.size} pages", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteSelectedPages() {
        val selected = adapter.selectedPositions.toList().sortedDescending()
        if (selected.isEmpty()) return
        val count = selected.size
        for (pos in selected) {
            if (pos in pages.indices) {
                pages.removeAt(pos)
            }
        }
        adapter.selectedPositions.clear()
        adapter.notifyDataSetChanged()
        updateSelectionUi()
        updateHeaderCount()
        Toast.makeText(context, "Deleted $count pages", Toast.LENGTH_SHORT).show()
        if (pages.isEmpty()) {
            onResumeCameraRequested()
        }
    }

    private fun deletePageAt(pos: Int) {
        if (pos !in pages.indices) return
        pages.removeAt(pos)
        adapter.notifyItemRemoved(pos)
        if (pos < pages.size) {
            adapter.notifyItemRangeChanged(pos, pages.size - pos)
        }
        updateHeaderCount()
        if (pages.isEmpty()) onResumeCameraRequested()
    }

    private fun updateHeaderCount() {
        val count = pages.size
        tvPageCount.text = "$count ${if (count == 1) "PAGE" else "PAGES"}"
        btnReorderPages.visibility = if (count > 1 && (!::adapter.isInitialized || !adapter.isSelectionMode())) View.VISIBLE else View.GONE
    }

    private fun applyFilterToPositions(mode: FilterMode, targetPositions: Set<Int>) {
        if (targetPositions.isEmpty()) return
        progressBar.visibility = View.VISIBLE
        for (pos in targetPositions) {
            if (pos in pages.indices) {
                pages[pos].filterMode = mode
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            for (pos in targetPositions) {
                if (pos in pages.indices) {
                    storageManager.regeneratePageThumbnail(pages[pos])
                }
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                for (pos in targetPositions) {
                    adapter.notifyItemChanged(pos)
                }
                Toast.makeText(context, "Applied ${mode.name} to ${targetPositions.size} selected pages", Toast.LENGTH_SHORT).show()
            }
        }
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
        if (existingProject != null) {
            // Requirement 3: Overwrite existing loadout silently without prompting for name
            progressBar.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                existingProject.pages = pages.map { it.copyPage() }.toMutableList()
                existingProject.updatedAt = System.currentTimeMillis()
                storageManager.saveProject(existingProject)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(context, "Saved changes to ${existingProject.title}", Toast.LENGTH_SHORT).show()
                    onLoadoutRequested()
                }
            }
            return
        }

        // Requirement 4: New scan session - show naming dialog with category tag pills
        val defaultName = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        showNewSessionDialog(defaultName) { chosenName, chosenCategory ->
            progressBar.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                val newProject = DocumentProject(
                    title = chosenName,
                    pages = pages.map { it.copyPage() }.toMutableList(),
                    category = chosenCategory
                )
                storageManager.saveProject(newProject)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(context, "Saved to Loadout!", Toast.LENGTH_SHORT).show()
                    onLoadoutRequested()
                }
            }
        }
    }

    /**
     * Requirement 3: Unified Share Dialog offering formats for single or multiple pages:
     * 1) PDF Document (Borderless)
     * 2) Elongated Continuous Image (if multi-page) / JPG Image (if single page)
     * 3) Separate Images
     */
    private fun showShareFormatModalSheet(targetPages: List<ScannedPage> = this.pages) {
        if (targetPages.isEmpty()) return

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
                    Color.parseColor("#F5000000"),
                    Color.parseColor("#E6000000")
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
            text = if (targetPages.size == 1) {
                "Share Selected Image"
            } else if (targetPages.size == pages.size) {
                "Share Scanned Session"
            } else {
                "Share ${targetPages.size} Selected Pages"
            }
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

        if (targetPages.size == 1) {
            container.addView(createFormatRow("PDF Document", "Borderless single-page PDF", R.drawable.ic_pdf) { name ->
                shareSinglePagePdf(targetPages[0], name)
            })
            container.addView(createFormatRow("JPG Image", "High-res individual JPEG image file", R.drawable.ic_gallery) { name ->
                shareSinglePageImage(targetPages[0], name)
            })
        } else {
            container.addView(createFormatRow("PDF Document", "Borderless edge-to-edge document", R.drawable.ic_pdf) { name ->
                exportToPdf(name, targetPages)
            })
            container.addView(createFormatRow("Elongated Image", "Single vertically continuous receipt/contract", R.drawable.ic_stitch) { name ->
                exportToElongatedImage(name, targetPages)
            })
            container.addView(createFormatRow("Separate Images", "High-res individual JPEG image files", R.drawable.ic_gallery) { name ->
                exportIndividualImages(name, targetPages)
            })
        }

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showNewSessionDialog(defaultName: String, onConfirm: (name: String, category: String) -> Unit) {
        var dialog: AlertDialog? = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F5000000"),
                    Color.parseColor("#E6000000")
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
            text = "SAVE TO LOADOUT"
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close dialog"
            val sz = (44 * density).toInt()
            val p = (11 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(Color.parseColor("#15FFFFFF"))
            }
            background = bg
            setColorFilter(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnClose)
        container.addView(headerRow)

        // Document Title Label
        val lblTitle = TextView(context).apply {
            text = "DOCUMENT TITLE"
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
            paint.isFakeBoldText = true
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }
        container.addView(lblTitle)

        val input = EditText(context).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            val pX = (16 * density).toInt()
            val pY = (12 * density).toInt()
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
                bottomMargin = (16 * density).toInt()
            }
        }
        container.addView(input)

        // Category / Type Label
        val lblType = TextView(context).apply {
            text = "DOCUMENT TYPE"
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
            paint.isFakeBoldText = true
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        container.addView(lblType)

        // Tag Pills Row (HorizontalScrollView)
        val categories = listOf("Documents", "Contracts", "Invoices", "Receipts", "Notes", "Personal", "Work", "Finance", "Archive")
        var selectedCategory = "Documents"

        val categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (20 * density).toInt()
            }
        }

        val categoryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val chipViews = mutableListOf<TextView>()

        fun updateChips() {
            for ((idx, cat) in categories.withIndex()) {
                val chip = chipViews[idx]
                val isSelected = cat.equals(selectedCategory, ignoreCase = true)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor(if (isSelected) "#FFFFFF" else "#30FFFFFF"))
                }
                chip.background = bg
                chip.setTextColor(if (isSelected) Color.parseColor("#090B10") else Color.parseColor("#CBD5E1"))
                chip.paint.isFakeBoldText = isSelected
            }
        }

        for (cat in categories) {
            val chip = TextView(context).apply {
                text = cat
                textSize = 12f
                val hP = (13 * density).toInt()
                val vP = (6 * density).toInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    selectedCategory = cat
                    updateChips()
                }
            }
            chipViews.add(chip)
            categoryRow.addView(chip)
        }
        updateChips()
        categoryScroll.addView(categoryRow)
        container.addView(categoryScroll)

        // Action Buttons Row
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
                onConfirm(name, selectedCategory)
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
    private fun exportToPdf(docName: String, targetPages: List<ScannedPage> = this.pages) {
        if (targetPages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val exportDir = storageManager.exportsDir
            val outputFile = File(exportDir, "${docName}.pdf")

            val document = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var success = false

            try {
                for (i in targetPages.indices) {
                    val page = targetPages[i]
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

    private fun exportToElongatedImage(docName: String, targetPages: List<ScannedPage> = this.pages) {
        if (targetPages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val maxElongated = when (quality) {
                com.clickem.scanner.util.settings.ExportQuality.HIGH -> 3600
                com.clickem.scanner.util.settings.ExportQuality.MEDIUM -> 2400
                com.clickem.scanner.util.settings.ExportQuality.LOW -> 1600
            }
            val bitmaps = mutableListOf<Bitmap>()
            for (page in targetPages) {
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

    private fun exportIndividualImages(docName: String, targetPages: List<ScannedPage> = this.pages) {
        if (targetPages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val exportDir = storageManager.exportsDir
            val files = mutableListOf<File>()

            for (i in targetPages.indices) {
                val page = targetPages[i]
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
                    Color.parseColor("#F5000000"),
                    Color.parseColor("#E6000000")
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
                    Color.parseColor("#F5000000"),
                    Color.parseColor("#E6000000")
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

    fun showMoveOverlay() {
        if (!adapter.isSelectionMode()) return
        moveOverlayBar.animate().cancel()
        moveOverlayBar.alpha = 0f
        moveOverlayBar.translationY = 16f * density
        moveOverlayBar.visibility = View.VISIBLE
        moveOverlayBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    fun hideMoveOverlay() {
        if (moveOverlayBar.visibility != View.VISIBLE) return
        moveOverlayBar.animate().cancel()
        moveOverlayBar.animate()
            .alpha(0f)
            .translationY(16f * density)
            .setDuration(140)
            .withEndAction {
                moveOverlayBar.visibility = View.GONE
            }
            .start()
    }

    private fun toggleMoveOverlay() {
        if (moveOverlayBar.visibility == View.VISIBLE) {
            hideMoveOverlay()
        } else {
            showMoveOverlay()
        }
    }

    private fun recordMoveHistory() {
        if (moveHistory.size >= 25) {
            moveHistory.removeAt(0)
        }
        val currentSelectedIds = adapter.selectedPositions
            .filter { it in pages.indices }
            .map { pages[it].id }
            .toSet()
        moveHistory.add(PageOrderSnapshot(pages.map { it.copyPage() }, currentSelectedIds))
        updateUndoButtonState()
    }

    private fun updateUndoButtonState() {
        btnMoveUndo?.let { btn ->
            val hasHistory = moveHistory.isNotEmpty()
            btn.alpha = if (hasHistory) 1f else 0.4f
            btn.isEnabled = hasHistory
        }
    }

    private fun moveSelectedPagesToStart() {
        val sel = adapter.selectedPositions.sorted()
        if (sel.isEmpty()) return
        val prePages = ArrayList(pages)
        val movingPages = sel.map { prePages[it] }
        val otherPages = prePages.filterIndexed { idx, _ -> idx !in sel }
        val newPages = ArrayList<ScannedPage>()
        newPages.addAll(movingPages)
        newPages.addAll(otherPages)
        if (newPages == prePages) return

        recordMoveHistory()
        pages.clear()
        pages.addAll(newPages)
        adapter.selectedPositions.clear()
        for (i in 0 until movingPages.size) adapter.selectedPositions.add(i)
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun moveSelectedPagesToEnd() {
        val sel = adapter.selectedPositions.sorted()
        if (sel.isEmpty()) return
        val prePages = ArrayList(pages)
        val movingPages = sel.map { prePages[it] }
        val otherPages = prePages.filterIndexed { idx, _ -> idx !in sel }
        val newPages = ArrayList<ScannedPage>()
        newPages.addAll(otherPages)
        newPages.addAll(movingPages)
        if (newPages == prePages) return

        recordMoveHistory()
        pages.clear()
        pages.addAll(newPages)
        adapter.selectedPositions.clear()
        val startIdx = otherPages.size
        for (i in startIdx until startIdx + movingPages.size) adapter.selectedPositions.add(i)
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun shiftSelectedPages(direction: Int) {
        val sel = adapter.selectedPositions.sorted()
        if (sel.isEmpty()) return
        if (direction < 0 && sel.first() == 0) return
        if (direction > 0 && sel.last() == pages.size - 1) return

        recordMoveHistory()
        val newPages = ArrayList(pages)
        val newSel = HashSet<Int>()

        if (direction < 0) {
            for (pos in sel) {
                Collections.swap(newPages, pos, pos - 1)
                newSel.add(pos - 1)
            }
        } else {
            for (pos in sel.reversed()) {
                Collections.swap(newPages, pos, pos + 1)
                newSel.add(pos + 1)
            }
        }

        pages.clear()
        pages.addAll(newPages)
        adapter.selectedPositions.clear()
        adapter.selectedPositions.addAll(newSel)
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun persistPagesAndShowUndo(msg: String, prePages: ArrayList<ScannedPage>, preSel: HashSet<Int>) {
        showUndoBar(msg, prePages, preSel)
    }

    private fun openReorderCanvas() {
        if (pages.size <= 1) return
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#090B10")))

        val reorderPages = ArrayList(pages)
        // Initialize with any pages already selected in the review screen
        val selectedIds = mutableSetOf<String>()
        for (pos in adapter.selectedPositions) {
            if (pos in pages.indices) {
                selectedIds.add(pages[pos].id)
            }
        }

        val reorderHistory = mutableListOf<PageOrderSnapshot>()
        var preDragCanvasPages = ArrayList(reorderPages)
        var preDragCanvasSelected = HashSet(selectedIds)
        var isCanvasBulkDragging = false
        var canvasDragStartPos = -1
        var canvasDragTargetPos = -1

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090B10"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val statusBarHeight = (36 * density).toInt()
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hP = (16 * density).toInt()
            val vP = (12 * density).toInt()
            setPadding(hP, statusBarHeight + vP, hP, vP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Close button
        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close reorder canvas"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }
        headerLayout.addView(btnClose)

        // Center Title & Subtitle
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvCanvasTitle = TextView(context).apply {
            text = "REORDER PAGES"
            textSize = 14f
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        titleCol.addView(tvCanvasTitle)

        val tvCanvasSub = TextView(context).apply {
            textSize = 11f
            letterSpacing = 0.04f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        titleCol.addView(tvCanvasSub)
        headerLayout.addView(titleCol)

        fun updateCanvasSubtitle() {
            val count = selectedIds.size
            tvCanvasSub.text = if (count > 0) {
                "$count selected • Drag to arrange"
            } else {
                "${reorderPages.size} pages • Tap to select • Drag to arrange"
            }
        }
        updateCanvasSubtitle()

        // Undo button
        val btnReorderUndo = ImageView(context).apply {
            setImageResource(R.drawable.ic_undo)
            contentDescription = "Undo reorder"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (8 * density).toInt()
            }
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            alpha = 0.4f
            isEnabled = false
            setOnClickListener {
                if (reorderHistory.isNotEmpty()) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val snapshot = reorderHistory.removeAt(reorderHistory.size - 1)
                    reorderPages.clear()
                    reorderPages.addAll(snapshot.pages)

                    // Keep holding to the correct images!
                    selectedIds.clear()
                    selectedIds.addAll(snapshot.selectedPageIds)

                    val rvAdapter = (root.getChildAt(1) as? RecyclerView)?.adapter
                    rvAdapter?.notifyDataSetChanged()
                    updateCanvasSubtitle()

                    val hasHistory = reorderHistory.isNotEmpty()
                    alpha = if (hasHistory) 1f else 0.4f
                    isEnabled = hasHistory
                }
            }
        }
        headerLayout.addView(btnReorderUndo)

        // Done button
        val btnDone = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            contentDescription = "Apply reordered pages"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#090B10"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener {
                pages.clear()
                pages.addAll(reorderPages)

                // Keep holding to the correct images in main review adapter!
                adapter.selectedPositions.clear()
                for (i in pages.indices) {
                    if (pages[i].id in selectedIds) {
                        adapter.selectedPositions.add(i)
                    }
                }
                adapter.notifyDataSetChanged()
                updateHeaderCount()
                updateSelectionUi()
                dialog.dismiss()
            }
        }
        headerLayout.addView(btnDone)
        root.addView(headerLayout)

        // 3-Column RecyclerView
        val rv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, (96 * density).toInt())
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        lateinit var reorderAdapter: ReorderGridAdapter
        reorderAdapter = ReorderGridAdapter(
            items = reorderPages,
            selectedIds = selectedIds,
            onItemClick = { _, _ ->
                updateCanvasSubtitle()
                reorderAdapter.notifyDataSetChanged()
            }
        )
        rv.adapter = reorderAdapter

        val reorderTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val to = target.bindingAdapterPosition
                if (to != RecyclerView.NO_POSITION && to in reorderPages.indices) {
                    if (canvasDragTargetPos != to) {
                        canvasDragTargetPos = to
                        recyclerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val count = if (isCanvasBulkDragging) selectedIds.size else 1
                        reorderAdapter.setHoverTarget(recyclerView, to, count)
                        tvCanvasSub.text = if (count > 1) {
                            "Placing $count pages at position ${to + 1} of ${reorderPages.size}"
                        } else {
                            "Placing at position ${to + 1} of ${reorderPages.size}"
                        }
                    }
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val startPos = viewHolder?.bindingAdapterPosition ?: -1
                    canvasDragStartPos = startPos
                    canvasDragTargetPos = startPos
                    preDragCanvasPages = ArrayList(reorderPages)
                    preDragCanvasSelected = HashSet(selectedIds)

                    val draggedPage = if (startPos in reorderPages.indices) reorderPages[startPos] else null
                    if (draggedPage != null && selectedIds.contains(draggedPage.id) && selectedIds.size > 1) {
                        isCanvasBulkDragging = true
                        val ghostIndices = reorderPages.indices.filter {
                            it != startPos && selectedIds.contains(reorderPages[it].id)
                        }.toSet()
                        reorderAdapter.ghostPositions = ghostIndices
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i) ?: continue
                            val holder = rv.getChildViewHolder(child) as? ReorderGridAdapter.ViewHolder ?: continue
                            val pos = holder.bindingAdapterPosition
                            if (pos in ghostIndices) {
                                child.animate().alpha(0.35f).scaleX(0.94f).scaleY(0.94f).setDuration(160).start()
                            }
                        }
                    } else {
                        isCanvasBulkDragging = false
                        reorderAdapter.ghostPositions = emptySet()
                    }

                    if (startPos != -1) {
                        val count = if (isCanvasBulkDragging) selectedIds.size else 1
                        reorderAdapter.setHoverTarget(rv, startPos, count)
                    }

                    viewHolder?.itemView?.let {
                        it.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                        it.elevation = 20f * density
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                    val draggedView = viewHolder.itemView
                    val centerX = draggedView.left + dX + draggedView.width / 2f
                    val centerY = draggedView.top + dY + draggedView.height / 2f

                    var hoveredChild = recyclerView.findChildViewUnder(centerX, centerY)
                    if (hoveredChild == null && recyclerView.childCount > 0) {
                        val lastChild = recyclerView.getChildAt(recyclerView.childCount - 1)
                        val firstChild = recyclerView.getChildAt(0)
                        if (lastChild != null && centerY > lastChild.top) {
                            hoveredChild = lastChild
                        } else if (firstChild != null && centerY < firstChild.bottom) {
                            hoveredChild = firstChild
                        }
                    }

                    if (hoveredChild != null && hoveredChild != draggedView) {
                        val holder = recyclerView.getChildViewHolder(hoveredChild)
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION && pos in reorderPages.indices) {
                            if (canvasDragTargetPos != pos) {
                                canvasDragTargetPos = pos
                                recyclerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                val count = if (isCanvasBulkDragging) selectedIds.size else 1
                                reorderAdapter.setHoverTarget(recyclerView, pos, count)
                                tvCanvasSub.text = if (count > 1) {
                                    "Placing $count pages at position ${pos + 1} of ${reorderPages.size}"
                                } else {
                                    "Placing at position ${pos + 1} of ${reorderPages.size}"
                                }
                            }
                        }
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                reorderAdapter.clearHoverTarget(recyclerView)
                reorderAdapter.ghostPositions = emptySet()

                val dropPos = if (canvasDragTargetPos != -1) canvasDragTargetPos.coerceIn(0, reorderPages.size - 1)
                    else canvasDragStartPos.coerceIn(0, reorderPages.size - 1)

                if (isCanvasBulkDragging && preDragCanvasSelected.isNotEmpty()) {
                    val movingPages = preDragCanvasPages.filter { it.id in preDragCanvasSelected }
                    val unselectedPages = preDragCanvasPages.filter { it.id !in preDragCanvasSelected }

                    val dropPage = reorderPages.getOrNull(dropPos)
                    val insertIndex = if (dropPage != null && dropPage.id !in preDragCanvasSelected) {
                        val idx = unselectedPages.indexOfFirst { it.id == dropPage.id }
                        if (idx != -1) idx else unselectedPages.size
                    } else {
                        dropPos.coerceIn(0, unselectedPages.size)
                    }

                    val newPages = ArrayList<ScannedPage>()
                    newPages.addAll(unselectedPages.subList(0, insertIndex))
                    newPages.addAll(movingPages)
                    newPages.addAll(unselectedPages.subList(insertIndex, unselectedPages.size))

                    val changed = (newPages != preDragCanvasPages)
                    reorderPages.clear()
                    reorderPages.addAll(newPages)

                    if (changed) {
                        if (reorderHistory.size >= 25) reorderHistory.removeAt(0)
                        reorderHistory.add(PageOrderSnapshot(preDragCanvasPages.map { it.copyPage() }, HashSet(preDragCanvasSelected)))
                        btnReorderUndo.alpha = 1f
                        btnReorderUndo.isEnabled = true
                    }
                    isCanvasBulkDragging = false
                    canvasDragStartPos = -1
                    canvasDragTargetPos = -1
                } else {
                    if (canvasDragStartPos != -1 && dropPos != canvasDragStartPos && canvasDragStartPos in reorderPages.indices) {
                        val movedPage = reorderPages.removeAt(canvasDragStartPos)
                        val safeDrop = dropPos.coerceIn(0, reorderPages.size)
                        reorderPages.add(safeDrop, movedPage)

                        val changed = (reorderPages != preDragCanvasPages)
                        if (changed) {
                            if (reorderHistory.size >= 25) reorderHistory.removeAt(0)
                            reorderHistory.add(PageOrderSnapshot(preDragCanvasPages.map { it.copyPage() }, HashSet(preDragCanvasSelected)))
                            btnReorderUndo.alpha = 1f
                            btnReorderUndo.isEnabled = true
                        }
                    }
                    canvasDragStartPos = -1
                    canvasDragTargetPos = -1
                }

                viewHolder.itemView.animate().cancel()
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
                viewHolder.itemView.translationX = 0f
                viewHolder.itemView.translationY = 0f
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.rotation = 0f
                viewHolder.itemView.elevation = 0f

                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i) ?: continue
                    child.animate().cancel()
                    child.translationX = 0f
                    child.translationY = 0f
                    child.scaleX = 1f
                    child.scaleY = 1f
                    child.alpha = 1f
                    child.rotation = 0f
                    child.elevation = 0f
                }
                reorderAdapter.notifyDataSetChanged()
                updateCanvasSubtitle()
            }
        })
        reorderTouchHelper.attachToRecyclerView(rv)

        root.addView(rv)
        dialog.setContentView(root)
        dialog.show()
    }

    private class ReorderGridAdapter(
        val items: MutableList<ScannedPage>,
        val selectedIds: MutableSet<String>,
        var onItemClick: ((pos: Int, page: ScannedPage) -> Unit)? = null
    ) : RecyclerView.Adapter<ReorderGridAdapter.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        var ghostPositions: Set<Int> = emptySet()
        var hoverTargetPos: Int = -1

        fun setHoverTarget(recyclerView: RecyclerView, pos: Int, count: Int) {
            if (hoverTargetPos != pos) {
                val prev = hoverTargetPos
                hoverTargetPos = pos
                if (prev != -1) {
                    val prevHolder = recyclerView.findViewHolderForAdapterPosition(prev) as? ViewHolder
                    prevHolder?.setHoverState(false, 1, prev)
                }
                if (pos != -1) {
                    val newHolder = recyclerView.findViewHolderForAdapterPosition(pos) as? ViewHolder
                    newHolder?.setHoverState(true, count, pos)
                }
            }
        }

        fun clearHoverTarget(recyclerView: RecyclerView) {
            if (hoverTargetPos != -1) {
                val holder = recyclerView.findViewHolderForAdapterPosition(hoverTargetPos) as? ViewHolder
                holder?.setHoverState(false, 1, hoverTargetPos)
                hoverTargetPos = -1
            }
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i) ?: continue
                val vh = recyclerView.getChildViewHolder(child) as? ViewHolder ?: continue
                vh.dropTargetOverlay.visibility = View.GONE
                vh.card.scaleX = 1f
                vh.card.scaleY = 1f
                vh.card.elevation = 0f
            }
        }

        override fun getItemId(position: Int): Long {
            return if (position in items.indices) items[position].id.hashCode().toLong() else RecyclerView.NO_ID
        }

        class ViewHolder(
            val root: FrameLayout,
            val card: LinearLayout,
            val ivThumb: ImageView,
            val tvBadge: TextView,
            val ivCheckBadge: ImageView,
            val ivDragHandle: ImageView,
            val dropTargetOverlay: FrameLayout,
            val tvDropIndicator: TextView
        ) : RecyclerView.ViewHolder(root) {

            fun setHoverState(isHovered: Boolean, count: Int, position: Int) {
                val density = root.context.resources.displayMetrics.density
                if (isHovered) {
                    dropTargetOverlay.visibility = View.VISIBLE
                    tvDropIndicator.text = if (count > 1) {
                        "DROP $count HERE\n→ POS ${position + 1}"
                    } else {
                        "DROP HERE\n→ POS ${position + 1}"
                    }
                    card.animate().cancel()
                    card.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
                    card.elevation = 8f * density
                } else {
                    dropTargetOverlay.visibility = View.GONE
                    card.animate().cancel()
                    card.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    card.elevation = 0f
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density

            val root = FrameLayout(ctx).apply {
                val hM = (6 * density).toInt()
                val vM = (6 * density).toInt()
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(hM, vM, hM, vM)
                }
                isClickable = true
                isFocusable = true
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                }
                val p = (4 * density).toInt()
                setPadding(p, p, p, p)
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }

            val imgContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (140 * density).toInt()
                )
            }

            val ivThumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(Color.parseColor("#090B10"))
                }
                clipToOutline = true
            }
            imgContainer.addView(ivThumb)

            val tvBadge = TextView(ctx).apply {
                textSize = 10.5f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                val hP = (6 * density).toInt()
                val vP = (2 * density).toInt()
                setPadding(hP, vP, hP, vP)
                background = GradientDrawable().apply {
                    cornerRadius = 6f * density
                    setColor(Color.parseColor("#CC000000"))
                    setStroke((1 * density).toInt(), Color.parseColor("#40FFFFFF"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins((6 * density).toInt(), (6 * density).toInt(), 0, 0)
                }
            }
            imgContainer.addView(tvBadge)

            val ivCheckBadge = ImageView(ctx).apply {
                val sz = (22 * density).toInt()
                val p = (3 * density).toInt()
                setPadding(p, p, p, p)
                layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, (6 * density).toInt(), (6 * density).toInt(), 0)
                }
            }
            imgContainer.addView(ivCheckBadge)

            val ivDragHandle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_stitch)
                val sz = (14 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
                }
                setColorFilter(Color.parseColor("#80FFFFFF"))
            }
            imgContainer.addView(ivDragHandle)

            val dropTargetOverlay = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(Color.parseColor("#40FFFFFF"))
                    setStroke((3 * density).toInt(), Color.parseColor("#FFFFFF"))
                }
                visibility = View.GONE
            }

            val tvDropIndicator = TextView(ctx).apply {
                textSize = 10f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                val hP = (10 * density).toInt()
                val vP = (5 * density).toInt()
                setPadding(hP, vP, hP, vP)
                background = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#EE090B10"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#FFFFFF"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
            }
            dropTargetOverlay.addView(tvDropIndicator)
            imgContainer.addView(dropTargetOverlay)

            card.addView(imgContainer)
            root.addView(card)

            return ViewHolder(root, card, ivThumb, tvBadge, ivCheckBadge, ivDragHandle, dropTargetOverlay, tvDropIndicator)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val page = items[position]
            val isSelected = selectedIds.contains(page.id)
            val isGhosted = position in ghostPositions
            val isHoverTarget = (position == hoverTargetPos)
            val density = holder.root.context.resources.displayMetrics.density

            holder.tvBadge.text = "${position + 1}"
            holder.root.contentDescription = "Page ${position + 1}${if (isSelected) ", selected" else ""}, tap to select, long press to drag"

            // Card highlight & stroke
            val cardBg = GradientDrawable().apply {
                cornerRadius = 10f * density
                if (isSelected) {
                    setColor(Color.parseColor("#26FFFFFF"))
                    setStroke((2 * density).toInt(), Color.parseColor("#FFFFFF"))
                } else {
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                }
            }
            holder.card.background = cardBg

            // Selection indicator badge in top right
            if (isSelected) {
                holder.ivCheckBadge.visibility = View.VISIBLE
                holder.ivCheckBadge.setImageResource(R.drawable.ic_check)
                holder.ivCheckBadge.setColorFilter(Color.parseColor("#090B10"))
                holder.ivCheckBadge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FFFFFF"))
                }
            } else if (selectedIds.isNotEmpty()) {
                holder.ivCheckBadge.visibility = View.VISIBLE
                holder.ivCheckBadge.setImageDrawable(null)
                holder.ivCheckBadge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#15000000"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#60FFFFFF"))
                }
            } else {
                holder.ivCheckBadge.visibility = View.GONE
            }

            // Drop target indicator
            if (isHoverTarget) {
                holder.dropTargetOverlay.visibility = View.VISIBLE
                val count = selectedIds.size
                holder.tvDropIndicator.text = if (count > 1) {
                    "DROP $count HERE\n→ POS ${position + 1}"
                } else {
                    "DROP HERE\n→ POS ${position + 1}"
                }
                holder.card.scaleX = 1.05f
                holder.card.scaleY = 1.05f
                holder.card.elevation = 8f * density
            } else {
                holder.dropTargetOverlay.visibility = View.GONE
                holder.card.scaleX = 1f
                holder.card.scaleY = 1f
                holder.card.elevation = 0f
            }

            // Ghosting during bulk drag
            holder.root.alpha = if (isGhosted) 0.35f else 1f

            holder.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    holder.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val p = items[pos]
                    if (selectedIds.contains(p.id)) {
                        selectedIds.remove(p.id)
                    } else {
                        selectedIds.add(p.id)
                    }
                    onItemClick?.invoke(pos, p)
                }
            }

            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bmp = BitmapFactory.decodeFile(page.thumbnailPath, opts)
                holder.ivThumb.setImageBitmap(bmp)
            } catch (_: Exception) {
                holder.ivThumb.setImageBitmap(null)
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.root.animate().cancel()
            holder.dropTargetOverlay.visibility = View.GONE
            holder.root.alpha = 1f
            holder.root.scaleX = 1f
            holder.root.scaleY = 1f
            holder.root.translationX = 0f
            holder.root.translationY = 0f
            holder.root.rotation = 0f
            holder.root.elevation = 0f
            holder.card.scaleX = 1f
            holder.card.scaleY = 1f
            holder.card.elevation = 0f
        }

        override fun getItemCount(): Int = items.size
    }
}
