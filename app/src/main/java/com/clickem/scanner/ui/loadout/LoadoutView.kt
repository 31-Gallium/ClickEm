package com.clickem.scanner.ui.loadout

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clickem.scanner.R
import com.clickem.scanner.model.DocumentProject
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.ui.components.GridBackgroundView
import com.clickem.scanner.ui.components.NavPillView
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

class LoadoutView(
    context: Context,
    private val storageManager: ScanStorageManager,
    private val onOpenProject: (DocumentProject) -> Unit,
    private val onOpenProjectAtPage: ((DocumentProject, Int) -> Unit)? = null,
    private val onReturnToCamera: () -> Unit
) : FrameLayout(context) {

    data class GalleryPhotoItem(
        val project: DocumentProject,
        val page: ScannedPage,
        val pageIndex: Int
    )

    private val density = context.resources.displayMetrics.density
    private val settingsManager = com.clickem.scanner.util.settings.SettingsManager(context)

    private val gridBackground: GridBackgroundView
    private lateinit var adapter: LoadoutAdapter
    private val galleryAdapter: ScannedGalleryAdapter
    private val galleryThumbCache = LruCache<String, Bitmap>(60)
    private val allProjects = mutableListOf<DocumentProject>()
    private val displayedProjects = mutableListOf<DocumentProject>()
    private val displayedGalleryPhotos = mutableListOf<GalleryPhotoItem>()
    private val categoryChips = mutableListOf<TextView>()

    private var activeFilter = "All"
    private var searchQuery = ""
    private var isGalleryMode = false

    private val tvHeaderTitle: TextView
    private val tvHeaderSubtitle: TextView
    private val rvProjects: RecyclerView
    private val rvGalleryGrid: RecyclerView
    private val progressBar: ProgressBar
    private val tvEmptyState: TextView

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.BLACK)

        // 1. Grid Background View
        gridBackground = GridBackgroundView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(gridBackground)

        // Initialize adapter with multi-thumbnail strip and single-page share callbacks
        adapter = LoadoutAdapter(
            projects = displayedProjects,
            onProjectClick = { proj -> onOpenProject(proj) },
            onPageThumbnailClick = { proj, pageIdx ->
                if (onOpenProjectAtPage != null) {
                    onOpenProjectAtPage.invoke(proj, pageIdx)
                } else {
                    onOpenProject(proj)
                }
            },
            onPageThumbnailLongClick = { proj, page, pageIdx ->
                showSinglePageShareDialog(proj, page, pageIdx)
            },
            onShareClick = { proj ->
                showShareFormatModalSheet(proj)
            },
            onDeleteClick = { proj, pos ->
                storageManager.deleteProject(proj.id)
                displayedProjects.removeAt(pos)
                allProjects.removeAll { it.id == proj.id }
                adapter.notifyItemRemoved(pos)
                rebuildGalleryItems()
                updateEmptyState()
                Toast.makeText(context, "Deleted scan session", Toast.LENGTH_SHORT).show()
            },
            onEditProjectClick = { proj ->
                showEditProjectDialog(proj)
            }
        )

        galleryAdapter = ScannedGalleryAdapter(
            items = displayedGalleryPhotos,
            onPhotoClick = { item ->
                if (onOpenProjectAtPage != null) {
                    onOpenProjectAtPage.invoke(item.project, item.pageIndex)
                } else {
                    onOpenProject(item.project)
                }
            },
            onPhotoLongClick = { item ->
                showSinglePageShareDialog(item.project, item.page, item.pageIndex)
            }
        )

        val initialSafeTop = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(0, initialSafeTop + (12 * density).toInt(), 0, (96 * density).toInt())
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val statusBarInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val safeMin = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
            val topInset = maxOf(statusBarInsets.top, safeMin)
            contentLayout.setPadding(0, topInset + (12 * density).toInt(), 0, (96 * density).toInt())
            insets
        }

        // 2. Top Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (20 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnBack = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Return to camera"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (12 * density).toInt()
            }
            setColorFilter(Color.parseColor("#F8FAFC"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            setOnClickListener { onReturnToCamera() }
        }
        header.addView(btnBack)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvHeaderTitle = TextView(context).apply {
            text = "LOADOUT ARCHIVE"
            textSize = 17f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            letterSpacing = 0.05f
        }
        titleCol.addView(tvHeaderTitle)

        tvHeaderSubtitle = TextView(context).apply {
            text = "Saved scan sessions & bundles"
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
        }
        titleCol.addView(tvHeaderSubtitle)
        header.addView(titleCol)

        contentLayout.addView(header)

        // 3. Search Bar with leading ic_search icon and padding
        val searchBox = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(Color.parseColor("#12151D"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            val m = (16 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply {
                setMargins(m, (14 * density).toInt(), m, (10 * density).toInt())
            }
        }

        val etSearch = EditText(context).apply {
            hint = "Search documents, contracts, receipts..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            isSingleLine = true
            background = null
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = (10 * density).toInt()
            setCompoundDrawableTintList(ColorStateList.valueOf(Color.parseColor("#8C96A5")))
            val pX = (14 * density).toInt()
            setPadding(pX, 0, pX, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    filterProjects()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(etSearch)
        contentLayout.addView(searchBox)

        // 4. Horizontal Category Filter Chips
        val chipScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (10 * density).toInt())
            }
        }

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * density).toInt()
            setPadding(pad, 0, pad, 0)
        }

        val categories = listOf("All", "Documents", "Contracts", "Invoices", "Receipts", "Notes")
        for (cat in categories) {
            val chip = TextView(context).apply {
                text = cat
                textSize = 12f
                val hP = (14 * density).toInt()
                val vP = (6 * density).toInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    activeFilter = cat
                    updateChipStyles()
                    filterProjects()
                }
            }
            categoryChips.add(chip)
            chipRow.addView(chip)
        }
        updateChipStyles()
        chipScroll.addView(chipRow)
        contentLayout.addView(chipScroll)

        // 5. Projects RecyclerView, Scanned Gallery Grid RecyclerView & Empty State
        val listContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rvProjects = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(context)
            adapter = this@LoadoutView.adapter
        }
        listContainer.addView(rvProjects)

        rvGalleryGrid = RecyclerView(context).apply {
            val pad = (10 * density).toInt()
            setPadding(pad, (2 * density).toInt(), pad, pad)
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(context, 3)
            adapter = this@LoadoutView.galleryAdapter
            visibility = View.GONE
        }
        listContainer.addView(rvGalleryGrid)

        tvEmptyState = TextView(context).apply {
            text = "No documents found\nTap the camera to start scanning"
            textSize = 13f
            setTextColor(Color.parseColor("#64748b"))
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        listContainer.addView(tvEmptyState)
        contentLayout.addView(listContainer)

        addView(contentLayout)

        // 6. Floating Bottom Nav Pill: [0: Loadout Archive] [1: Camera] [2: Google Photos Grid Gallery]
        val navPill = NavPillView(context).apply {
            layoutParams = LayoutParams(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, (22 * density).toInt())
            }
            setTabs(listOf(R.drawable.ic_loadout, R.drawable.ic_camera, R.drawable.ic_gallery), initialIndex = 0)
            onTabSelected = { tabIdx ->
                when (tabIdx) {
                    0 -> switchDisplayMode(galleryMode = false)
                    1 -> onReturnToCamera()
                    2 -> switchDisplayMode(galleryMode = true)
                }
            }
        }
        addView(navPill)

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        addView(progressBar)

        loadProjectsFromStorage()
    }

    private fun switchDisplayMode(galleryMode: Boolean) {
        isGalleryMode = galleryMode
        if (galleryMode) {
            tvHeaderTitle.text = "SCANNED GALLERY"
            val count = displayedGalleryPhotos.size
            tvHeaderSubtitle.text = "$count scanned ${if (count == 1) "image" else "images"} • Grid view"
            rvProjects.visibility = View.GONE
            rvGalleryGrid.visibility = View.VISIBLE
        } else {
            tvHeaderTitle.text = "LOADOUT ARCHIVE"
            tvHeaderSubtitle.text = "Saved scan sessions & bundles"
            rvProjects.visibility = View.VISIBLE
            rvGalleryGrid.visibility = View.GONE
        }
        updateEmptyState()
    }

    private fun updateChipStyles() {
        val categories = listOf("All", "Documents", "Contracts", "Invoices", "Receipts", "Notes")
        for (i in categories.indices) {
            val cat = categories[i]
            val chip = categoryChips[i]
            val isSelected = (cat == activeFilter)
            val bg = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#12151D"))
                setStroke((1 * density).toInt(), Color.parseColor(if (isSelected) "#FFFFFF" else "#30FFFFFF"))
            }
            chip.background = bg
            chip.setTextColor(if (isSelected) Color.parseColor("#090B10") else Color.parseColor("#8C96A5"))
            chip.paint.isFakeBoldText = isSelected
        }
    }

    fun refreshProjects() {
        loadProjectsFromStorage()
    }

    private fun loadProjectsFromStorage() {
        allProjects.clear()
        allProjects.addAll(storageManager.getAllProjects())
        filterProjects()
    }

    private fun filterProjects() {
        displayedProjects.clear()
        for (p in allProjects) {
            val matchesCategory = (activeFilter == "All" || p.category.equals(activeFilter, ignoreCase = true))
            val matchesQuery = searchQuery.isEmpty() || p.title.contains(searchQuery, ignoreCase = true)
            if (matchesCategory && matchesQuery) {
                displayedProjects.add(p)
            }
        }
        adapter.notifyDataSetChanged()
        rebuildGalleryItems()
        updateEmptyState()
    }

    private fun rebuildGalleryItems() {
        displayedGalleryPhotos.clear()
        for (proj in displayedProjects) {
            for (i in proj.pages.indices) {
                displayedGalleryPhotos.add(GalleryPhotoItem(proj, proj.pages[i], i))
            }
        }
        galleryAdapter.notifyDataSetChanged()
        if (isGalleryMode) {
            val count = displayedGalleryPhotos.size
            tvHeaderSubtitle.text = "$count scanned ${if (count == 1) "image" else "images"} • Grid view"
        }
    }

    private fun updateEmptyState() {
        val isEmpty = if (isGalleryMode) displayedGalleryPhotos.isEmpty() else displayedProjects.isEmpty()
        tvEmptyState.text = if (isGalleryMode) {
            "No scanned images found\nTap the camera icon below to start scanning"
        } else {
            "No documents found\nTap the camera icon below to start scanning"
        }
        tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    /**
     * Requirement R4: Overhauled Edit Project Dialog with Frost Modal Card,
     * category selection chips (replacing Spinner), and ic_close / ic_check icon buttons.
     */
    private fun showEditProjectDialog(proj: DocumentProject) {
        val dialogCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
        }

        var dialog: AlertDialog? = null

        // Header Row: Title & Close Button
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (18 * density).toInt()
            }
        }

        val tvDialogTitle = TextView(context).apply {
            text = "EDIT SCAN DETAILS"
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvDialogTitle)

        val btnCloseHeader = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Cancel edit"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#8C96A5"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnCloseHeader)
        dialogCard.addView(headerRow)

        // Document Title Input Field
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
        dialogCard.addView(lblTitle)

        val etTitle = EditText(context).apply {
            setText(proj.title)
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            hint = "Enter document title"
            textSize = 14f
            isSingleLine = true
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (18 * density).toInt()
            }
        }
        dialogCard.addView(etTitle)

        // Category Chips Selection (replaces Spinner)
        val lblCategory = TextView(context).apply {
            text = "CATEGORY"
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
        dialogCard.addView(lblCategory)

        val editCategories = listOf("Documents", "Contracts", "Invoices", "Receipts", "Notes", "Personal", "Work", "Finance", "Archive")
        var selectedCategory = if (proj.category.isNotBlank()) proj.category else "Documents"

        val categoryScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (22 * density).toInt()
            }
        }

        val categoryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val chipViews = mutableListOf<TextView>()

        fun updateEditChipStyles() {
            for ((idx, cat) in editCategories.withIndex()) {
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

        for (cat in editCategories) {
            val chip = TextView(context).apply {
                text = cat
                textSize = 12f
                val hP = (12 * density).toInt()
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
                    updateEditChipStyles()
                }
            }
            chipViews.add(chip)
            categoryRow.addView(chip)
        }
        updateEditChipStyles()
        categoryScroll.addView(categoryRow)
        dialogCard.addView(categoryScroll)

        // Action Buttons Row: Cancel (ic_close) & Save (ic_check)
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCancel = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Cancel edit"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (12 * density).toInt()
            }
            setColorFilter(Color.parseColor("#8C96A5"))
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            setOnClickListener { dialog?.dismiss() }
        }
        actionRow.addView(btnCancel)

        val btnSave = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            contentDescription = "Save changes"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#090B10"))
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#FFFFFF"))
            }
            setOnClickListener {
                val newTitle = etTitle.text.toString().trim()
                if (newTitle.isNotEmpty()) proj.title = newTitle
                proj.category = selectedCategory
                proj.updatedAt = System.currentTimeMillis()
                storageManager.saveProject(proj)
                filterProjects()
                Toast.makeText(context, "Updated document details", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }
        actionRow.addView(btnSave)
        dialogCard.addView(actionRow)

        dialog = AlertDialog.Builder(context)
            .setView(dialogCard)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /**
     * Requirement R4: Overhauled Single-Page Share Dialog with Custom Frost Modal Card,
     * using ic_pdf, ic_gallery, ic_edit, ic_delete, and ic_close vector icons.
     */
    private fun showSinglePageShareDialog(proj: DocumentProject, page: ScannedPage, pageIdx: Int) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
        }

        var dialog: AlertDialog? = null

        // Header Row: Title & Close Button
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

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(context).apply {
            text = proj.title
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            maxLines = 1
        }
        titleCol.addView(tvTitle)

        val tvSub = TextView(context).apply {
            text = "Page ${pageIdx + 1} of ${proj.pages.size} • Action Options"
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
        }
        titleCol.addView(tvSub)
        headerRow.addView(titleCol)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close page options"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#8C96A5"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnClose)
        container.addView(headerRow)

        fun createActionRow(title: String, subtitle: String, iconRes: Int, onAction: () -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }

                val icon = ImageView(context).apply {
                    setImageResource(iconRes)
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
                    setTextColor(Color.parseColor("#F8FAFC"))
                    paint.isFakeBoldText = true
                }
                col.addView(tvT)

                val tvS = TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Color.parseColor("#8C96A5"))
                }
                col.addView(tvS)
                addView(col)

                setOnClickListener {
                    dialog?.dismiss()
                    onAction()
                }
            }
        }

        // 1. Share as PDF (ic_pdf)
        container.addView(createActionRow(
            "Share Single Page as PDF",
            "Borderless edge-to-edge PDF of page ${pageIdx + 1}",
            R.drawable.ic_pdf
        ) {
            shareSinglePagePdf(page, "${proj.title}_Page_${pageIdx + 1}")
        })

        // 2. Share as Image (ic_gallery)
        container.addView(createActionRow(
            "Share Single Page as Image",
            "High-resolution individual JPEG image file",
            R.drawable.ic_gallery
        ) {
            shareSinglePageImage(page, "${proj.title}_Page_${pageIdx + 1}")
        })

        // 3. Edit Page (ic_edit)
        container.addView(createActionRow(
            "Edit Page in Scanner",
            "Fine-tune crop quad, rotation, or filter preset",
            R.drawable.ic_edit
        ) {
            if (onOpenProjectAtPage != null) {
                onOpenProjectAtPage.invoke(proj, pageIdx)
            } else {
                onOpenProject(proj)
            }
        })

        // 4. Delete Page (ic_delete)
        container.addView(createActionRow(
            "Delete Scanned Page",
            "Remove page ${pageIdx + 1} from ${proj.title}",
            R.drawable.ic_delete
        ) {
            if (pageIdx in proj.pages.indices) {
                proj.pages.removeAt(pageIdx)
                if (proj.pages.isEmpty()) {
                    storageManager.deleteProject(proj.id)
                } else {
                    proj.updatedAt = System.currentTimeMillis()
                    storageManager.saveProject(proj)
                }
                loadProjectsFromStorage()
                Toast.makeText(context, "Deleted page ${pageIdx + 1}", Toast.LENGTH_SHORT).show()
            }
        })

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun shareSinglePagePdf(page: ScannedPage, name: String) {
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = 2048)
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

            val outputFile = File(storageManager.exportsDir, "${name}.pdf")
            val success = PdfGenerator.createSinglePagePdf(filtered, outputFile)
            filtered.recycle()

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "application/pdf", "${name}.pdf")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "application/pdf", "Share $name")
                } else {
                    Toast.makeText(context, "Failed to export PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareSinglePageImage(page: ScannedPage, name: String) {
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = 2048)
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

            val outputFile = File(storageManager.exportsDir, "${name}.jpg")
            var success = false
            try {
                FileOutputStream(outputFile).use { fos ->
                    filtered.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                }
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                filtered.recycle()
            }

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "image/jpeg", "${name}.jpg")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "image/jpeg", "Share $name")
                } else {
                    Toast.makeText(context, "Failed to export image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Requirement 3: Unified Share Dialog offering 3 formats:
     * 1) Borderless PDF
     * 2) Elongated Continuous Image
     * 3) Separate Images
     */
    /**
     * Requirement R4: Overhauled Unified Share Dialog with Frost Modal Card,
     * vector icon close button (replacing text "Cancel"), and Monochrome Frost styling.
     */
    private fun showShareFormatModalSheet(proj: DocumentProject) {
        if (proj.pages.isEmpty()) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2161A26"),
                    Color.parseColor("#EA0E1118")
                )
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
        }

        var dialog: AlertDialog? = null

        // Header Row: Title & Close Button
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

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(context).apply {
            text = proj.title
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            maxLines = 1
        }
        titleCol.addView(tvTitle)

        val tvPrompt = TextView(context).apply {
            text = "Select Document Export Format (${proj.pages.size} pages)"
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
        }
        titleCol.addView(tvPrompt)
        headerRow.addView(titleCol)

        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Cancel share"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#8C96A5"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            setOnClickListener { dialog?.dismiss() }
        }
        headerRow.addView(btnClose)
        container.addView(headerRow)

        fun createFormatRow(title: String, subtitle: String, iconRes: Int, onAction: () -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
                val bg = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(Color.parseColor("#15FFFFFF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }

                val icon = ImageView(context).apply {
                    setImageResource(iconRes)
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
                    setTextColor(Color.parseColor("#F8FAFC"))
                    paint.isFakeBoldText = true
                }
                col.addView(tvT)

                val tvSub = TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Color.parseColor("#8C96A5"))
                }
                col.addView(tvSub)
                addView(col)

                setOnClickListener {
                    dialog?.dismiss()
                    onAction()
                }
            }
        }

        container.addView(createFormatRow("PDF Document", "Borderless edge-to-edge document", R.drawable.ic_pdf) {
            shareProjectAsPdf(proj)
        })

        container.addView(createFormatRow("Elongated Image", "Single vertically continuous receipt/contract", R.drawable.ic_stitch) {
            shareProjectAsElongated(proj)
        })

        container.addView(createFormatRow("Separate Images", "High-res individual JPEG image files", R.drawable.ic_gallery) {
            shareProjectAsImages(proj)
        })

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /**
     * Requirement 5: Borderless 0-margin PDF generation
     */
    private fun shareProjectAsPdf(proj: DocumentProject) {
        if (proj.pages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val exportDir = storageManager.exportsDir
            val safeName = proj.title.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), "")
            val outputFile = File(exportDir, "${safeName}.pdf")

            val document = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var success = false

            try {
                for (i in proj.pages.indices) {
                    val page = proj.pages[i]
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

                    // Borderless edge-to-edge draw at 1:1 pixel fidelity
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
                storageManager.exportToPublicStorage(outputFile, "application/pdf", "${safeName}.pdf")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "application/pdf", "Share ${proj.title}")
                } else {
                    Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareProjectAsElongated(proj: DocumentProject) {
        if (proj.pages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val maxElongated = when (quality) {
                com.clickem.scanner.util.settings.ExportQuality.HIGH -> 3600
                com.clickem.scanner.util.settings.ExportQuality.MEDIUM -> 2400
                com.clickem.scanner.util.settings.ExportQuality.LOW -> 1600
            }
            val bitmaps = mutableListOf<Bitmap>()
            for (page in proj.pages) {
                val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = maxElongated) ?: continue
                val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
                val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)
                if (src != warped) src.recycle()
                if (warped != filtered) warped.recycle()
                bitmaps.add(filtered)
            }

            val exportDir = storageManager.exportsDir
            val safeName = proj.title.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), "")
            val outputFile = File(exportDir, "${safeName}_stitched.jpg")
            val success = ElongatedImageStitcher.stitchPagesVertically(bitmaps, outputFile)

            bitmaps.forEach { it.recycle() }

            if (success) {
                storageManager.exportToPublicStorage(outputFile, "image/jpeg", "${safeName}_stitched.jpg")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    ShareHelper.shareFile(context, outputFile, "image/jpeg", "Share ${proj.title}")
                } else {
                    Toast.makeText(context, "Failed to stitch pages", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareProjectAsImages(proj: DocumentProject) {
        if (proj.pages.isEmpty()) return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val quality = settingsManager.defaultExportQuality
            val exportDir = storageManager.exportsDir
            val safeName = proj.title.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), "")
            val files = mutableListOf<File>()

            for (i in proj.pages.indices) {
                val page = proj.pages[i]
                val src = storageManager.loadBitmap(page.originalImagePath, maxDimension = quality.maxDimension) ?: continue
                val warped = PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
                val filtered = DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)
                if (src != warped) src.recycle()
                if (warped != filtered) warped.recycle()

                val f = File(exportDir, "${safeName}_Page_${i + 1}.jpg")
                FileOutputStream(f).use { fos ->
                    filtered.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, fos)
                }
                filtered.recycle()
                files.add(f)
                storageManager.exportToPublicStorage(f, "image/jpeg", f.name)
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                ShareHelper.shareMultipleFiles(context, files, "image/jpeg", "Share ${proj.title}")
            }
        }
    }

    /**
     * Google Photos-style 3-column grid adapter displaying all scanned images across projects.
     */
    inner class ScannedGalleryAdapter(
        private val items: List<GalleryPhotoItem>,
        private val onPhotoClick: (GalleryPhotoItem) -> Unit,
        private val onPhotoLongClick: (GalleryPhotoItem) -> Unit
    ) : RecyclerView.Adapter<ScannedGalleryAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(
            val tile: FrameLayout,
            val ivPhoto: ImageView,
            val tvLabel: TextView,
            val tvDateBadge: TextView
        ) : RecyclerView.ViewHolder(tile)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val m = (4 * density).toInt()
            val tileHeight = (138 * density).toInt()

            val tile = FrameLayout(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    tileHeight
                ).apply {
                    setMargins(m, m, m, m)
                }
                background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(Color.parseColor("#12151D"))
                    setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
                }
                clipToOutline = true
            }

            val ivPhoto = ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tile.addView(ivPhoto)

            // Subtle top-right date pill
            val tvDateBadge = TextView(context).apply {
                textSize = 8.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                paint.isFakeBoldText = true
                val hP = (6 * density).toInt()
                val vP = (2 * density).toInt()
                setPadding(hP, vP, hP, vP)
                background = GradientDrawable().apply {
                    cornerRadius = 6f * density
                    setColor(Color.parseColor("#B3090B10"))
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    val bM = (6 * density).toInt()
                    setMargins(bM, bM, bM, bM)
                }
            }
            tile.addView(tvDateBadge)

            // Bottom caption overlay bar
            val tvLabel = TextView(context).apply {
                textSize = 9.5f
                setTextColor(Color.parseColor("#F8FAFC"))
                paint.isFakeBoldText = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                val hP = (6 * density).toInt()
                val vP = (4 * density).toInt()
                setPadding(hP, vP, hP, vP)
                setBackgroundColor(Color.parseColor("#CC090B10"))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM
                }
            }
            tile.addView(tvLabel)

            return PhotoViewHolder(tile, ivPhoto, tvLabel, tvDateBadge)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val item = items[position]
            val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

            holder.tvLabel.text = "${item.project.title} • P${item.pageIndex + 1}"
            holder.tvDateBadge.text = dateFmt.format(Date(item.project.createdAt))
            holder.tile.contentDescription = "${item.project.title}, Page ${item.pageIndex + 1} of ${item.project.pages.size}"

            try {
                val thumbFile = File(item.page.thumbnailPath)
                val cacheKey = "${item.page.thumbnailPath}_${thumbFile.lastModified()}"
                var bmp = galleryThumbCache.get(cacheKey)
                if (bmp == null || bmp.isRecycled) {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 2
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    bmp = BitmapFactory.decodeFile(item.page.thumbnailPath, opts)
                    if (bmp != null) {
                        galleryThumbCache.put(cacheKey, bmp)
                    }
                }
                holder.ivPhoto.setImageBitmap(bmp)
            } catch (_: Exception) {
                holder.ivPhoto.setImageDrawable(null)
            }

            holder.tile.setOnClickListener {
                onPhotoClick(item)
            }
            holder.tile.setOnLongClickListener {
                onPhotoLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
