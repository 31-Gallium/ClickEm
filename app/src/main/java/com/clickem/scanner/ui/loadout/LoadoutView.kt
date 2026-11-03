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
import android.view.HapticFeedbackConstants
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
import com.clickem.scanner.util.BackPressHandler
import androidx.viewpager2.widget.ViewPager2
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
) : FrameLayout(context), BackPressHandler {

    data class GalleryPhotoItem(
        val project: DocumentProject,
        val page: ScannedPage,
        val pageIndex: Int
    )

    private val density = context.resources.displayMetrics.density
    private val settingsManager = com.clickem.scanner.util.settings.SettingsManager(context)

    private val gridBackground: GridBackgroundView
    private lateinit var adapter: LoadoutAdapter
    private lateinit var galleryAdapter: ScannedGalleryAdapter
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
    private val btnBack: ImageView
    private val rvProjects: RecyclerView
    private val rvGalleryGrid: RecyclerView
    private val progressBar: ProgressBar
    private val tvEmptyState: TextView

    private val navPill: NavPillView
    private val gallerySelectionDock: LinearLayout

    private val galleryDetailOverlay: FrameLayout
    private val detailViewPager: ViewPager2
    private val tvDetailTitle: TextView
    private val tvDetailSubtitle: TextView
    private val detailTopBar: LinearLayout
    private val detailBottomBar: FrameLayout
    private val isLandscapeCache = mutableMapOf<String, Boolean>()
    private var gallerySpanSizes = IntArray(0)

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
                val idx = displayedGalleryPhotos.indexOf(item)
                if (idx != -1) {
                    openGalleryDetail(idx)
                }
            },
            onPhotoLongClick = { item ->
                if (!galleryAdapter.isSelectionMode()) {
                    galleryAdapter.toggleSelection(item)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        )
        galleryAdapter.onSelectionChanged = {
            updateGallerySelectionUi()
        }

        val initialSafeTop = com.clickem.scanner.util.UiInsetsUtil.getSafeTopInset(context)
        val initialSafeBottom = com.clickem.scanner.util.UiInsetsUtil.getSafeBottomInset(context)
        val baseNavMargin = (22 * density).toInt()
        val baseContentBottom = (96 * density).toInt()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(0, initialSafeTop + (12 * density).toInt(), 0, 0)
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

        btnBack = ImageView(context).apply {
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
                setColor(Color.parseColor("#000000"))
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
            clipToPadding = false
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), baseContentBottom + initialSafeBottom)
        }
        listContainer.addView(rvProjects)

        rvGalleryGrid = RecyclerView(context).apply {
            val pad = (1 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, baseContentBottom + initialSafeBottom)
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val gridLM = GridLayoutManager(context, 3)
            gridLM.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return gallerySpanSizes.getOrElse(position) { 1 }
                }
            }
            layoutManager = gridLM
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

        val topFadeView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (26 * density).toInt()
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
        listContainer.addView(topFadeView)

        val bottomFadeView = View(context).apply {
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
        listContainer.addView(bottomFadeView)
        contentLayout.addView(listContainer)

        addView(contentLayout)

        // 6. Floating Bottom Nav Pill: [0: Loadout Archive] [1: Camera] [2: Google Photos Grid Gallery]
        navPill = NavPillView(context).apply {
            layoutParams = LayoutParams(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, baseNavMargin + initialSafeBottom)
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

        // 7. Floating Gallery Selection Dock (2-button dock: Share & Delete)
        gallerySelectionDock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (16 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#F2000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            elevation = 16f * density
            layoutParams = LayoutParams(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, baseNavMargin + initialSafeBottom)
            }
            visibility = View.GONE

            // Share button
            val btnShare = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(Color.TRANSPARENT)
                }

                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_share)
                    setColorFilter(Color.WHITE)
                    val sz = (20 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (8 * density).toInt()
                    }
                }
                addView(icon)

                val label = TextView(context).apply {
                    text = "Share"
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                }
                addView(label)

                setOnClickListener {
                    if (galleryAdapter.selectedItems.isEmpty()) return@setOnClickListener
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val sortedSelected = galleryAdapter.selectedItems.sortedWith(
                        compareBy({ it.project.createdAt }, { it.pageIndex })
                    )
                    if (sortedSelected.size == 1) {
                        val single = sortedSelected.first()
                        showSinglePageShareDialog(single.project, single.page, single.pageIndex)
                    } else {
                        val tempProj = DocumentProject(
                            id = "gallery_selection_export",
                            title = "ClickEm_Scans",
                            pages = ArrayList(sortedSelected.map { it.page })
                        )
                        showShareFormatModalSheet(tempProj)
                    }
                }
            }
            addView(btnShare)

            // Divider line
            val divider = View(context).apply {
                val w = (1 * density).toInt()
                val h = (24 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(w, h).apply {
                    setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                }
                setBackgroundColor(Color.parseColor("#25FFFFFF"))
            }
            addView(divider)

            // Delete button
            val btnDelete = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(Color.TRANSPARENT)
                }

                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(Color.parseColor("#F43F5E"))
                    val sz = (20 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (8 * density).toInt()
                    }
                }
                addView(icon)

                val label = TextView(context).apply {
                    text = "Delete"
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#F43F5E"))
                    paint.isFakeBoldText = true
                }
                addView(label)

                setOnClickListener {
                    if (galleryAdapter.selectedItems.isEmpty()) return@setOnClickListener
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val count = galleryAdapter.selectedItems.size
                    AlertDialog.Builder(context)
                        .setTitle("Delete $count Selected Photo${if (count > 1) "s" else ""}?")
                        .setMessage("Are you sure you want to delete these photos? If all photos from a session are deleted, the entire session will be deleted.")
                        .setPositiveButton("Delete") { _, _ ->
                            val selectedList = ArrayList(galleryAdapter.selectedItems)
                            val byProject = selectedList.groupBy { it.project }
                            for ((proj, items) in byProject) {
                                val pageIdsToRemove = items.map { it.page.id }.toSet()
                                val remaining = proj.pages.filter { it.id !in pageIdsToRemove }
                                for (item in items) {
                                    try { File(item.page.originalImagePath).delete() } catch (_: Exception) {}
                                    try { File(item.page.thumbnailPath).delete() } catch (_: Exception) {}
                                    try { galleryThumbCache.remove("${item.page.thumbnailPath}_${File(item.page.thumbnailPath).lastModified()}") } catch (_: Exception) {}
                                }
                                if (remaining.isEmpty()) {
                                    storageManager.deleteProject(proj.id)
                                } else {
                                    proj.pages = ArrayList(remaining)
                                    proj.updatedAt = System.currentTimeMillis()
                                    storageManager.saveProject(proj)
                                }
                            }
                            galleryAdapter.clearSelection()
                            loadProjectsFromStorage()
                            Toast.makeText(context, "Deleted $count photo${if (count > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            addView(btnDelete)
        }
        addView(gallerySelectionDock)

        // 4. Fullscreen Gallery Detail Overlay (Requirement 5)
        galleryDetailOverlay = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }

        detailViewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }
        galleryDetailOverlay.addView(detailViewPager)

        // Top Bar
        detailTopBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, initialSafeTop + (8 * density).toInt(), p, (8 * density).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            }
        }

        val btnDetailBack = ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Close photo viewer"
            val sz = (40 * density).toInt()
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33000000"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { closeGalleryDetail() }
        }
        detailTopBar.addView(btnDetailBack)

        val detailTitleWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }

        tvDetailTitle = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
        }
        detailTitleWrap.addView(tvDetailTitle)

        tvDetailSubtitle = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
        }
        detailTitleWrap.addView(tvDetailSubtitle)
        detailTopBar.addView(detailTitleWrap)

        detailViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position in displayedGalleryPhotos.indices) {
                    val item = displayedGalleryPhotos[position]
                    tvDetailTitle.text = "IMAGE ${position + 1} OF ${displayedGalleryPhotos.size}"
                    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    tvDetailSubtitle.text = "${item.project.title} • Page ${item.pageIndex + 1} • ${dateFmt.format(Date(item.project.createdAt))}"
                }
            }
        })

        val btnDetailShare = ImageView(context).apply {
            setImageResource(R.drawable.ic_share)
            contentDescription = "Share photo"
            val sz = (40 * density).toInt()
            val p = (9 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33000000"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener {
                val pos = detailViewPager.currentItem
                if (pos in displayedGalleryPhotos.indices) {
                    val item = displayedGalleryPhotos[pos]
                    showSinglePageShareDialog(item.project, item.page, item.pageIndex)
                }
            }
        }
        detailTopBar.addView(btnDetailShare)

        val btnDetailDelete = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = "Delete photo"
            val sz = (40 * density).toInt()
            val p = (9 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.parseColor("#F43F5E"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33000000"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = (10 * density).toInt()
            }
            setOnClickListener {
                val pos = detailViewPager.currentItem
                if (pos in displayedGalleryPhotos.indices) {
                    val item = displayedGalleryPhotos[pos]
                    AlertDialog.Builder(context)
                        .setTitle("Delete Photo?")
                        .setMessage("Are you sure you want to delete this photo? If this is the only photo in the session, the session will be deleted.")
                        .setPositiveButton("Delete") { _, _ ->
                            val proj = item.project
                            val pageIdx = item.pageIndex
                            try { File(item.page.originalImagePath).delete() } catch (_: Exception) {}
                            try { File(item.page.thumbnailPath).delete() } catch (_: Exception) {}
                            try { galleryThumbCache.remove("${item.page.thumbnailPath}_${File(item.page.thumbnailPath).lastModified()}") } catch (_: Exception) {}
                            if (pageIdx in proj.pages.indices) {
                                proj.pages.removeAt(pageIdx)
                                if (proj.pages.isEmpty()) {
                                    storageManager.deleteProject(proj.id)
                                } else {
                                    proj.updatedAt = System.currentTimeMillis()
                                    storageManager.saveProject(proj)
                                }
                            }
                            loadProjectsFromStorage()
                            Toast.makeText(context, "Deleted photo", Toast.LENGTH_SHORT).show()
                            if (displayedGalleryPhotos.isEmpty()) {
                                closeGalleryDetail()
                            } else {
                                val nextPos = pos.coerceAtMost(displayedGalleryPhotos.size - 1)
                                detailViewPager.adapter = GalleryDetailPagerAdapter(displayedGalleryPhotos)
                                detailViewPager.setCurrentItem(nextPos, false)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        detailTopBar.addView(btnDetailDelete)
        galleryDetailOverlay.addView(detailTopBar)

        // Bottom Bar with "Open Document Session" floating pill
        detailBottomBar = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, baseNavMargin + initialSafeBottom)
            }
        }

        val btnDetailOpenSession = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val hP = (22 * density).toInt()
            val vP = (12 * density).toInt()
            setPadding(hP, vP, hP, vP)
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.WHITE)
                setStroke((1 * density).toInt(), Color.parseColor("#40FFFFFF"))
            }
            elevation = 8f * density
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_loadout)
                val sz = (18 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (8 * density).toInt()
                }
                setColorFilter(Color.BLACK)
            }
            addView(icon)

            val text = TextView(context).apply {
                text = "Open Document Session"
                textSize = 13.5f
                setTextColor(Color.BLACK)
                paint.isFakeBoldText = true
            }
            addView(text)

            setOnClickListener {
                val pos = detailViewPager.currentItem
                if (pos in displayedGalleryPhotos.indices) {
                    val item = displayedGalleryPhotos[pos]
                    closeGalleryDetail()
                    if (onOpenProjectAtPage != null) {
                        onOpenProjectAtPage.invoke(item.project, item.pageIndex)
                    } else {
                        onOpenProject(item.project)
                    }
                }
            }
        }
        detailBottomBar.addView(btnDetailOpenSession)
        galleryDetailOverlay.addView(detailBottomBar)

        addView(galleryDetailOverlay)

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
            contentLayout.setPadding(0, topInset + (12 * density).toInt(), 0, 0)
            bottomFadeView.layoutParams.height = (110 * density).toInt() + safeBottom
            bottomFadeView.requestLayout()
            rvProjects.setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), baseContentBottom + safeBottom)
            val pad = (1 * density).toInt()
            rvGalleryGrid.setPadding(pad, (8 * density).toInt(), pad, baseContentBottom + safeBottom)
            (navPill.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = baseNavMargin + safeBottom
                navPill.layoutParams = lp
            }
            (gallerySelectionDock.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = baseNavMargin + safeBottom
                gallerySelectionDock.layoutParams = lp
            }
            (detailTopBar.layoutParams as? LayoutParams)?.let {
                detailTopBar.setPadding((16 * density).toInt(), topInset + (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            }
            (detailBottomBar.layoutParams as? LayoutParams)?.let { lp ->
                lp.bottomMargin = baseNavMargin + safeBottom
                detailBottomBar.layoutParams = lp
            }
            insets
        }

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
        if (galleryAdapter.isSelectionMode()) {
            galleryAdapter.clearSelection()
        }
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
                setColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#000000"))
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

    private fun isItemLandscape(item: GalleryPhotoItem): Boolean {
        val path = item.page.thumbnailPath
        return isLandscapeCache.getOrPut(path) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val isRotated = (item.page.rotationDegrees % 180 != 0)
                val w = if (isRotated) opts.outHeight else opts.outWidth
                val h = if (isRotated) opts.outWidth else opts.outHeight
                w > (h * 1.18f)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun updateGallerySpanSizes() {
        val spanSizes = IntArray(displayedGalleryPhotos.size) { 1 }
        var col = 0
        for (i in displayedGalleryPhotos.indices) {
            val item = displayedGalleryPhotos[i]
            val isLandscape = isItemLandscape(item)
            if (isLandscape && (col == 0 || col == 1)) {
                spanSizes[i] = 2
                col = (col + 2) % 3
            } else {
                spanSizes[i] = 1
                col = (col + 1) % 3
            }
        }
        gallerySpanSizes = spanSizes
    }

    private fun rebuildGalleryItems() {
        displayedGalleryPhotos.clear()
        for (proj in displayedProjects) {
            for (i in proj.pages.indices) {
                displayedGalleryPhotos.add(GalleryPhotoItem(proj, proj.pages[i], i))
            }
        }
        updateGallerySpanSizes()
        galleryAdapter.notifyDataSetChanged()
        if (isGalleryMode) {
            val count = displayedGalleryPhotos.size
            tvHeaderSubtitle.text = "$count scanned ${if (count == 1) "image" else "images"} • Grid view"
        }
    }

    fun openGalleryDetail(position: Int) {
        if (displayedGalleryPhotos.isEmpty() || position !in displayedGalleryPhotos.indices) return
        detailViewPager.adapter = GalleryDetailPagerAdapter(displayedGalleryPhotos)
        detailViewPager.setCurrentItem(position, false)
        val item = displayedGalleryPhotos[position]
        tvDetailTitle.text = "IMAGE ${position + 1} OF ${displayedGalleryPhotos.size}"
        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        tvDetailSubtitle.text = "${item.project.title} • Page ${item.pageIndex + 1} • ${dateFmt.format(Date(item.project.createdAt))}"
        galleryDetailOverlay.alpha = 0f
        galleryDetailOverlay.visibility = View.VISIBLE
        galleryDetailOverlay.animate().alpha(1f).setDuration(180).start()
    }

    fun closeGalleryDetail() {
        if (galleryDetailOverlay.visibility != View.VISIBLE) return
        galleryDetailOverlay.animate().alpha(0f).setDuration(150).withEndAction {
            galleryDetailOverlay.visibility = View.GONE
        }.start()
    }

    override fun handleBackPressed(): Boolean {
        if (galleryDetailOverlay.visibility == View.VISIBLE) {
            closeGalleryDetail()
            return true
        }
        if (isGalleryMode && galleryAdapter.isSelectionMode()) {
            galleryAdapter.clearSelection()
            return true
        }
        return false
    }

    private fun updateGallerySelectionUi() {
        val count = galleryAdapter.selectedItems.size
        if (count > 0) {
            btnBack.setImageResource(R.drawable.ic_close)
            btnBack.contentDescription = "Cancel gallery selection"
            btnBack.setOnClickListener {
                galleryAdapter.clearSelection()
            }
            tvHeaderTitle.text = "$count SELECTED"
            tvHeaderSubtitle.text = "Select photos to share or delete"
            navPill.visibility = View.GONE
            gallerySelectionDock.visibility = View.VISIBLE
        } else {
            btnBack.setImageResource(R.drawable.ic_back)
            btnBack.contentDescription = "Return to camera"
            btnBack.setOnClickListener {
                onReturnToCamera()
            }
            if (isGalleryMode) {
                tvHeaderTitle.text = "SCANNED GALLERY"
                val total = displayedGalleryPhotos.size
                tvHeaderSubtitle.text = "$total scanned ${if (total == 1) "image" else "images"} • Grid view"
            } else {
                tvHeaderTitle.text = "LOADOUT ARCHIVE"
                tvHeaderSubtitle.text = "Saved scan sessions & bundles"
            }
            navPill.visibility = View.VISIBLE
            gallerySelectionDock.visibility = View.GONE
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
            text = "Page ${pageIdx + 1} of ${proj.pages.size} • Share Format"
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

        val selectedItems = mutableSetOf<GalleryPhotoItem>()
        var onSelectionChanged: ((Int) -> Unit)? = null

        fun isSelectionMode(): Boolean = selectedItems.isNotEmpty()

        fun toggleSelection(item: GalleryPhotoItem) {
            val wasMode = isSelectionMode()
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
            } else {
                selectedItems.add(item)
            }
            val isNowMode = isSelectionMode()
            if (wasMode != isNowMode) {
                notifyDataSetChanged()
            } else {
                val idx = items.indexOf(item)
                if (idx != -1) {
                    notifyItemChanged(idx)
                } else {
                    notifyDataSetChanged()
                }
            }
            onSelectionChanged?.invoke(selectedItems.size)
        }

        fun clearSelection() {
            selectedItems.clear()
            notifyDataSetChanged()
            onSelectionChanged?.invoke(0)
        }

        inner class PhotoViewHolder(
            val tile: FrameLayout,
            val ivPhoto: ImageView,
            val tvLabel: TextView,
            val tvDateBadge: TextView,
            val ivSelectionCheck: ImageView
        ) : RecyclerView.ViewHolder(tile)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val m = (1 * density).toInt()
            val colW = context.resources.displayMetrics.widthPixels / 3

            val tile = FrameLayout(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    colW
                ).apply {
                    setMargins(m, m, m, m)
                }
                background = GradientDrawable().apply {
                    cornerRadius = 0f
                    setColor(Color.BLACK)
                }
                clipToOutline = true
            }

            val ivPhoto = ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tile.addView(ivPhoto)

            // Hidden when unopened (Requirement 5)
            val tvDateBadge = TextView(context).apply {
                textSize = 8.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                visibility = View.GONE
            }
            tile.addView(tvDateBadge)

            val tvLabel = TextView(context).apply {
                textSize = 9.5f
                setTextColor(Color.parseColor("#F8FAFC"))
                visibility = View.GONE
            }
            tile.addView(tvLabel)

            val ivSelectionCheck = ImageView(context).apply {
                val sz = (26 * density).toInt()
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                background = bg
                val p = (5 * density).toInt()
                setPadding(p, p, p, p)
                setImageResource(R.drawable.ic_check)
                setColorFilter(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                    gravity = Gravity.TOP or Gravity.END
                    val margin = (6 * density).toInt()
                    setMargins(margin, margin, margin, margin)
                }
                visibility = View.GONE
            }
            tile.addView(ivSelectionCheck)

            return PhotoViewHolder(tile, ivPhoto, tvLabel, tvDateBadge, ivSelectionCheck)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val item = items[position]
            val spanSize = if (position in gallerySpanSizes.indices) gallerySpanSizes[position] else 1
            val colW = holder.tile.context.resources.displayMetrics.widthPixels / 3
            val targetH = if (spanSize == 2) (colW * 1.0f).toInt() else colW
            if (holder.tile.layoutParams.height != targetH) {
                holder.tile.layoutParams.height = targetH
            }

            holder.tile.contentDescription = "${item.project.title}, Page ${item.pageIndex + 1} of ${item.project.pages.size}"

            val isSelected = selectedItems.contains(item)
            val inSelectionMode = isSelectionMode()
            val tileBg = holder.tile.background as? GradientDrawable

            if (isSelected) {
                tileBg?.setStroke((2.5f * density).toInt(), Color.WHITE)
                holder.ivSelectionCheck.visibility = View.VISIBLE
                holder.ivSelectionCheck.isClickable = true
                holder.ivSelectionCheck.setImageResource(R.drawable.ic_check)
                holder.ivSelectionCheck.setColorFilter(Color.BLACK)
                (holder.ivSelectionCheck.background as? GradientDrawable)?.apply {
                    setColor(Color.WHITE)
                    setStroke(0, Color.TRANSPARENT)
                }
            } else if (inSelectionMode) {
                tileBg?.setStroke(0, Color.TRANSPARENT)
                holder.ivSelectionCheck.visibility = View.VISIBLE
                holder.ivSelectionCheck.isClickable = true
                holder.ivSelectionCheck.setImageDrawable(null)
                (holder.ivSelectionCheck.background as? GradientDrawable)?.apply {
                    setColor(Color.TRANSPARENT)
                    setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
                }
            } else {
                tileBg?.setStroke(0, Color.TRANSPARENT)
                holder.ivSelectionCheck.visibility = View.GONE
                holder.ivSelectionCheck.isClickable = false
            }

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
                if (isSelectionMode()) {
                    toggleSelection(item)
                } else {
                    onPhotoClick(item)
                }
            }
            holder.tile.setOnLongClickListener {
                if (!isSelectionMode()) {
                    toggleSelection(item)
                    holder.tile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                true
            }
            holder.ivSelectionCheck.setOnClickListener {
                if (isSelectionMode()) {
                    toggleSelection(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class GalleryDetailPagerAdapter(
        private val items: List<GalleryPhotoItem>
    ) : RecyclerView.Adapter<GalleryDetailPagerAdapter.DetailViewHolder>() {

        inner class DetailViewHolder(val container: FrameLayout, val ivPhoto: ImageView) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
            val fl = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
            }
            val iv = ImageView(parent.context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            fl.addView(iv)
            return DetailViewHolder(fl, iv)
        }

        override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
            val item = items[position]
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeFile(item.page.thumbnailPath, opts)
                holder.ivPhoto.setImageBitmap(bmp)
            } catch (_: Exception) {
                holder.ivPhoto.setImageDrawable(null)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
