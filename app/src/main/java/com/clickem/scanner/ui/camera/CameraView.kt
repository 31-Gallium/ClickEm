package com.clickem.scanner.ui.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clickem.scanner.R
import com.clickem.scanner.model.CaptureAction
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.ui.components.CameraTopHudView
import com.clickem.scanner.ui.components.ViewfinderOverlayView
import com.clickem.scanner.util.cv.BoundaryDetector
import com.clickem.scanner.util.cv.TfliteDocumentDetector
import com.clickem.scanner.util.settings.VolumeKeyAction
import com.clickem.scanner.util.settings.VolumeKeyPolicy
import com.clickem.scanner.util.storage.ScanStorageManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import java.util.concurrent.Executors
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max

class CameraView(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val storageManager: ScanStorageManager,
    private val onGalleryPickRequested: () -> Unit,
    private val onEditPageRequested: (page: ScannedPage, index: Int) -> Unit,
    private val onDoneRequested: (pages: List<ScannedPage>) -> Unit,
    private val onLoadoutRequested: () -> Unit,
    private val onSettingsRequested: () -> Unit
) : FrameLayout(context), SensorEventListener {

    private val density = context.resources.displayMetrics.density

    // CameraX components
    private val previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraTargetRatio = AspectRatio.RATIO_4_3
    var currentRatioString: String = "4:3"
        private set

    // UI overlays
    private val viewfinderOverlay: ViewfinderOverlayView
    private val topHud: CameraTopHudView
    private val thumbnailRv: RecyclerView
    private val thumbnailAdapter: ThumbnailQueueAdapter
    private val tvTrashHint: TextView

    // Shutter & controls
    private val btnGallery: ImageView
    private val btnShutterRing: View
    private val shutterCore: FrameLayout
    private val btnDone: FrameLayout
    private val btnQuickLoadout: ImageView
    private val tvDoneCount: TextView
    private val btnResetFocus: ImageView
    private val btnClearQueue: ImageView
    private val modeDocPill: ImageView
    private val modeQrPill: ImageView
    private val retakeBanner: LinearLayout
    private val ivRetakeIcon: ImageView
    private val tvRetakeBannerText: TextView
    private val btnCancelRetake: ImageView

    // Session Data
    val activePages = mutableListOf<ScannedPage>()

    // Retake in-place state (preserves page order when retaking from Edit screen)
    var retakeTargetIndex: Int? = null
        private set
    private var retakeUpdatesCameraQueue: Boolean = true
    private var onRetakeCapturedCallback: ((ScannedPage, Int) -> Unit)? = null
    var onRetakeCancelledCallback: (() -> Unit)? = null

    // Zoom State Tracking & Visual HUD
    private var activeZoomRatio: Float = 1.0f
    private var activeLinearZoom: Float = 0.0f
    private var minZoomRatio: Float = 1.0f
    private var maxZoomRatio: Float = 10.0f
    private var lastManualZoomTimestamp: Long = 0L
    private val zoomIndicatorView: TextView

    // Sensors
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isSensorRegistered = false
    private var currentUiRotation: Float = 0f
    private val orientationEventListener by lazy {
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val targetAngle = when (orientation) {
                    in 45..134 -> 270f
                    in 135..224 -> 180f
                    in 225..314 -> 90f
                    else -> 0f
                }
                if (targetAngle != currentUiRotation) {
                    currentUiRotation = targetAngle
                    animateUiIconsRotation(targetAngle)
                }
            }
        }
    }

    private fun animateUiIconsRotation(angle: Float) {
        val duration = 220L
        topHud.setControlsRotation(angle)
        listOf(
            btnGallery,
            btnQuickLoadout,
            btnDone,
            zoomIndicatorView,
            modeDocPill,
            modeQrPill,
            btnClearQueue,
            ivRetakeIcon,
            tvRetakeBannerText,
            btnCancelRetake
        ).forEach { view ->
            view.animate().rotation(angle).setDuration(duration).start()
        }
    }

    // ML Kit QR Scanner & Edge Boundary Detection
    private val barcodeScanner = BarcodeScanning.getClient()
    private var isQrScanCoolingDown = false
    private var isQrModeActive = false

    // Shutter capture guard to debounce volume keys and rapid triggers
    @Volatile
    private var isCapturing = false

    private var lastAnalysisTimestamp = 0L
    private var currentDetectedBounds: List<PointF> = listOf(
        PointF(0.08f, 0.10f),
        PointF(0.92f, 0.10f),
        PointF(0.92f, 0.90f),
        PointF(0.08f, 0.90f)
    )

    var onQrDetected: ((String) -> Unit)? = null

    // Shutter flash overlay for visible capture feedback
    private val flashOverlay: View

    private val scaleDetector: ScaleGestureDetector

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                lastManualZoomTimestamp = System.currentTimeMillis()
                camera?.cameraInfo?.zoomState?.value?.let { state ->
                    minZoomRatio = state.minZoomRatio
                    maxZoomRatio = state.maxZoomRatio
                }
                activeZoomRatio = (activeZoomRatio * detector.scaleFactor).coerceIn(minZoomRatio, maxZoomRatio)
                activeLinearZoom = VolumeKeyPolicy.calculateLinearZoomFromRatio(activeZoomRatio, minZoomRatio, maxZoomRatio)
                try {
                    camera?.cameraControl?.setZoomRatio(activeZoomRatio)
                    camera?.cameraControl?.setLinearZoom(activeLinearZoom)
                } catch (_: Exception) {
                    try {
                        camera?.cameraControl?.setLinearZoom(activeLinearZoom)
                    } catch (_: Exception) {}
                }
                showZoomIndicator(activeZoomRatio)
                return true
            }
        })

        // 1. Camera PreviewView with pinch-to-zoom support
        previewView = PreviewView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        addView(previewView)

        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updatePreviewLayout()
            }
        }

        // 2. Viewfinder Overlay (Boundary detection quad + alignment grid)
        viewfinderOverlay = ViewfinderOverlayView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        addView(viewfinderOverlay)

        // 3. Shutter Snap Flash Overlay
        flashOverlay = View(context).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        addView(flashOverlay)

        // 4. Top Camera HUD Bar (Flush edge-to-edge Pixel/CamScanner style)
        topHud = CameraTopHudView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            onTorchToggled = { enabled ->
                camera?.cameraControl?.enableTorch(enabled)
            }
            onQrModeToggled = { qrEnabled ->
                setCameraMode(qrEnabled)
            }
            onRatioToggled = { ratioStr ->
                setAspectRatio(ratioStr)
            }
            onGridToggled = { gridOn ->
                viewfinderOverlay.showGrid = gridOn
            }
            onSettingsClicked = {
                onSettingsRequested()
            }
            onLoadoutClicked = {
                onLoadoutRequested()
            }
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = (bottom - top).toFloat()
                if (h > 0f) {
                    viewfinderOverlay.topPanelInset = h
                }
            }
        }
        addView(topHud)

        // 4B. Retake Mode Icon Pill (Sleek frosted capsule displayed below topHud)
        retakeBanner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hP = (10 * density).toInt()
            val vP = (4 * density).toInt()
            setPadding(hP, vP, (4 * density).toInt(), vP)
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.parseColor("#E60E1118"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (94 * density).toInt()
            }
            visibility = View.GONE
        }

        topHud.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            if (bottom > 0) {
                val lp = retakeBanner.layoutParams as? FrameLayout.LayoutParams
                val targetMargin = bottom + (12 * density).toInt()
                if (lp != null && lp.topMargin != targetMargin) {
                    lp.topMargin = targetMargin
                    retakeBanner.layoutParams = lp
                }
            }
        }

        ivRetakeIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_restore)
            contentDescription = "Retaking page"
            setColorFilter(Color.WHITE)
            val iconSz = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
        }
        retakeBanner.addView(ivRetakeIcon)

        tvRetakeBannerText = TextView(context).apply {
            text = "P1"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            val pStart = (6 * density).toInt()
            val pEnd = (6 * density).toInt()
            setPadding(pStart, 0, pEnd, 0)
        }
        retakeBanner.addView(tvRetakeBannerText)

        btnCancelRetake = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Cancel retake"
            val sz = (28 * density).toInt()
            val p = (6 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#FFFFFF"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#25FFFFFF"))
            }
            setOnClickListener {
                val cancelCb = onRetakeCancelledCallback
                cancelRetakeMode()
                cancelCb?.invoke()
            }
        }
        retakeBanner.addView(btnCancelRetake)
        addView(retakeBanner)

        // 5. Bottom Control Zone Wrapper (Zoom Pill + Flush Bottom Frosted Dock)
        val bottomZoneWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        }

        // 5A. Pixel Camera-style Zoom Quick-Toggle Badge (Symmetrical circle to prevent distortion on rotation)
        val zoomBadgeSz = (42 * density).toInt()
        zoomIndicatorView = TextView(context).apply {
            text = "1.0x"
            contentDescription = "Toggle camera zoom"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D9090B10"))
                setStroke((1 * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            background = bg
            alpha = 0.90f
            layoutParams = LinearLayout.LayoutParams(zoomBadgeSz, zoomBadgeSz).apply {
                bottomMargin = (8 * density).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                val targetZoom = if (activeZoomRatio < 1.5f) {
                    2.0f.coerceIn(minZoomRatio, maxZoomRatio)
                } else {
                    1.0f.coerceIn(minZoomRatio, maxZoomRatio)
                }
                adjustZoom(targetZoom - activeZoomRatio)
            }
        }
        bottomZoneWrapper.addView(zoomIndicatorView)

        // 5B. Floating Bottom Dock Container (Floating controls directly over camera feed)
        val bottomContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = null
            elevation = 0f
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = (bottom - top).toFloat()
                if (h > 0f) {
                    viewfinderOverlay.bottomPanelInset = h
                }
            }
        }

        // Trash hint bar displayed when dragging a thumbnail
        tvTrashHint = TextView(context).apply {
            text = "Swipe up or down to remove page"
            textSize = 11f
            setTextColor(Color.parseColor("#f43f5e"))
            gravity = Gravity.CENTER
            val p = (6 * density).toInt()
            setPadding(0, p, 0, p)
            visibility = View.GONE
        }
        bottomContainer.addView(tvTrashHint)

        // 5C. Horizontal Thumbnail Queue Strip (With Immediate Skeleton Loader & Drag/Swipe)
        thumbnailAdapter = ThumbnailQueueAdapter(
            pages = activePages,
            onThumbnailClick = { pos ->
                if (pos in activePages.indices) {
                    onEditPageRequested(activePages[pos], pos)
                }
            },
            onPageDismissed = { pos ->
                deletePageAt(pos)
                triggerHapticFeedback()
            }
        )

        thumbnailRv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = thumbnailAdapter
            val hPad = (12 * density).toInt()
            setPadding(hPad, (10 * density).toInt(), hPad, (2 * density).toInt())
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (94 * density).toInt()
            )
            visibility = View.GONE
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            ItemTouchHelper.UP or ItemTouchHelper.DOWN
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    thumbnailAdapter.onItemMove(from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !thumbnailAdapter.isSkeletonPosition(pos)) {
                    deletePageAt(pos)
                    triggerHapticFeedback()
                }
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    tvTrashHint.visibility = View.VISIBLE
                    viewHolder?.itemView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                tvTrashHint.visibility = View.GONE
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        })
        itemTouchHelper.attachToRecyclerView(thumbnailRv)
        bottomContainer.addView(thumbnailRv)

        // 5D. CamScanner / Pixel Camera Mode Selector & Queue Utility Bar
        val modeControlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (16 * density).toInt()
            setPadding(hPad, (8 * density).toInt(), hPad, (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val leftUtilWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
        }

        btnResetFocus = ImageView(context).apply {
            setImageResource(R.drawable.ic_restore)
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B30A0D14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            setColorFilter(Color.WHITE)
            contentDescription = "Reset auto focus"
            visibility = View.GONE
            setOnClickListener { resetFocusedQueueItem() }
        }
        leftUtilWrap.addView(btnResetFocus)
        modeControlBar.addView(leftUtilWrap)

        // Center Mode Switcher Pill: [ DOCUMENT ]  [ QR CODE ]
        val modePillWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeSwitchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val p = (3 * density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.parseColor("#B30A0D14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        val modePillSz = (34 * density).toInt()
        val modePad = (7 * density).toInt()

        modeDocPill = ImageView(context).apply {
            setImageResource(R.drawable.ic_document)
            contentDescription = "Document scanning mode"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(modePad, modePad, modePad, modePad)
            layoutParams = LinearLayout.LayoutParams(modePillSz, modePillSz)
            setOnClickListener { setCameraMode(false) }
        }
        modeSwitchRow.addView(modeDocPill)

        modeQrPill = ImageView(context).apply {
            setImageResource(R.drawable.ic_qr_scan)
            contentDescription = "QR code scanning mode"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(modePad, modePad, modePad, modePad)
            layoutParams = LinearLayout.LayoutParams(modePillSz, modePillSz)
            setOnClickListener { setCameraMode(true) }
        }
        modeSwitchRow.addView(modeQrPill)
        modePillWrap.addView(modeSwitchRow)
        modeControlBar.addView(modePillWrap)

        val rightUtilWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
        }

        btnClearQueue = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete)
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#18F43F5E"))
                setStroke((1 * density).toInt(), Color.parseColor("#45F43F5E"))
            }
            setColorFilter(Color.parseColor("#F43F5E"))
            contentDescription = "Clear scanned page queue"
            visibility = View.GONE
            setOnClickListener {
                if (activePages.isNotEmpty()) {
                    clearActivePages()
                }
            }
        }
        rightUtilWrap.addView(btnClearQueue)
        modeControlBar.addView(rightUtilWrap)

        bottomContainer.addView(modeControlBar)
        updateModePillStyles()

        // 5E. Primary Shutter Action Row (Google Pixel Camera / CamScanner 3-Slot Triad)
        val dockHPad = (20 * density).toInt()
        val defaultDockBottomPad = (20 * density).toInt()
        val initialSafeBottom = com.clickem.scanner.util.UiInsetsUtil.getSafeBottomInset(context)
        val initialBottomPad = maxOf(defaultDockBottomPad, initialSafeBottom + (8 * density).toInt())

        val dockBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dockHPad, (8 * density).toInt(), dockHPad, initialBottomPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Left Slot: Gallery Import Button (Centered in left third)
        val leftSlot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnGallery = ImageView(context).apply {
            setImageResource(R.drawable.ic_gallery)
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B30A0D14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            setColorFilter(Color.WHITE)
            contentDescription = "Open gallery"
            setOnClickListener { onGalleryPickRequested() }
        }
        leftSlot.addView(btnGallery)
        dockBar.addView(leftSlot)

        // Center Slot: Precision Pixel/CamScanner Tactile Shutter Button
        val shutterContainer = FrameLayout(context).apply {
            val sz = (76 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            clipChildren = false
            clipToPadding = false
        }

        btnShutterRing = View(context).apply {
            val ringBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#250A0D14"))
                setStroke((3.5f * density).toInt(), Color.WHITE)
            }
            background = ringBg
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            contentDescription = "Capture photo"
        }
        shutterContainer.addView(btnShutterRing)

        shutterCore = FrameLayout(context).apply {
            val coreBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F8FAFC"))
            }
            background = coreBg
            val sz = (60 * density).toInt()
            layoutParams = LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER
            }
            contentDescription = "Take picture"
            setOnClickListener { triggerShutterCapture() }

            val innerRing = View(context).apply {
                val innerSz = (50 * density).toInt()
                layoutParams = LayoutParams(innerSz, innerSz).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((1 * density).toInt(), Color.parseColor("#22090B10"))
                }
                isClickable = false
                isFocusable = false
            }
            addView(innerRing)
        }
        shutterContainer.addView(shutterCore)
        dockBar.addView(shutterContainer)

        // Right Slot: Archive Shortcut (when 0 pages) OR High-Contrast "Done" CTA with Badge (when >= 1 pages)
        val rightSlot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnQuickLoadout = ImageView(context).apply {
            setImageResource(R.drawable.ic_loadout)
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B30A0D14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            setColorFilter(Color.WHITE)
            contentDescription = "Open saved scans"
            setOnClickListener { onLoadoutRequested() }
        }
        rightSlot.addView(btnQuickLoadout)

        btnDone = FrameLayout(context).apply {
            val totalSz = (52 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(totalSz, totalSz).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.INVISIBLE
            contentDescription = "Done"

            // High-Contrast White CTA Base Button
            val iconButton = FrameLayout(context).apply {
                val btnSz = (46 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(btnSz, btnSz).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FFFFFF"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#FFFFFF"))
                }

                val checkIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    val isz = (22 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(isz, isz).apply {
                        gravity = Gravity.CENTER
                    }
                    setColorFilter(Color.parseColor("#090B10"))
                }
                addView(checkIcon)
            }
            addView(iconButton)

            // Numeric Badge Overlay
            tvDoneCount = TextView(context).apply {
                textSize = 10f
                setTextColor(Color.parseColor("#FFFFFF"))
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                val padH = (4 * density).toInt()
                setPadding(padH, 0, padH, 0)
                minWidth = (18 * density).toInt()
                val badgeH = (18 * density).toInt()
                val badgeBg = GradientDrawable().apply {
                    cornerRadius = 9f * density
                    setColor(Color.parseColor("#090B10"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#FFFFFF"))
                }
                background = badgeBg
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    badgeH
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
            }
            addView(tvDoneCount)

            setOnClickListener {
                if (activePages.isNotEmpty()) {
                    triggerHapticFeedback()
                    onDoneRequested(activePages.toList())
                }
            }
        }
        rightSlot.addView(btnDone)
        dockBar.addView(rightSlot)

        bottomContainer.addView(dockBar)
        bottomZoneWrapper.addView(bottomContainer)
        addView(bottomZoneWrapper)

        // Dynamic 3-button navigation bar adjustment for floating dock
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val navBottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
            ).bottom
            val safeBottom = maxOf(defaultDockBottomPad, navBottom + (8 * density).toInt())
            dockBar.setPadding(dockHPad, (8 * density).toInt(), dockHPad, safeBottom)
            insets
        }

        TfliteDocumentDetector.init(context)
        startCamera()
    }

    private fun setCameraMode(qrEnabled: Boolean) {
        isQrModeActive = qrEnabled
        viewfinderOverlay.isQrMode = qrEnabled
        topHud.setQrModeDisplay(qrEnabled)
        updateModePillStyles()
    }

    private fun updateModePillStyles() {
        val docActive = !isQrModeActive
        modeDocPill.background = GradientDrawable().apply {
            cornerRadius = 17f * density
            setColor(if (docActive) Color.parseColor("#FFFFFF") else Color.TRANSPARENT)
        }
        modeDocPill.setColorFilter(Color.parseColor(if (docActive) "#090B10" else "#8C96A5"))

        val qrActive = isQrModeActive
        modeQrPill.background = GradientDrawable().apply {
            cornerRadius = 17f * density
            setColor(if (qrActive) Color.parseColor("#FFFFFF") else Color.TRANSPARENT)
        }
        modeQrPill.setColorFilter(Color.parseColor(if (qrActive) "#090B10" else "#8C96A5"))
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(cameraTargetRatio)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(cameraTargetRatio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(cameraTargetRatio)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        processCameraStreamFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                camera?.cameraInfo?.zoomState?.removeObservers(lifecycleOwner)
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                try {
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }

            try {
                camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
                    if (state != null) {
                        minZoomRatio = state.minZoomRatio
                        maxZoomRatio = state.maxZoomRatio
                        val elapsedSinceManualZoom = System.currentTimeMillis() - lastManualZoomTimestamp
                        if (!scaleDetector.isInProgress && elapsedSinceManualZoom > 600L) {
                            activeZoomRatio = state.zoomRatio
                            activeLinearZoom = state.linearZoom
                            zoomIndicatorView.text = String.format(Locale.US, "%.1fx", activeZoomRatio)
                        }
                    }
                }
                if (activeZoomRatio != 1.0f) {
                    try {
                        camera?.cameraControl?.setZoomRatio(activeZoomRatio.coerceIn(minZoomRatio, maxZoomRatio))
                        camera?.cameraControl?.setLinearZoom(activeLinearZoom.coerceIn(0.0f, 1.0f))
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processCameraStreamFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTimestamp < 120L) {
            imageProxy.close()
            return
        }
        lastAnalysisTimestamp = now

        if (isQrModeActive) {
            val mediaImage = imageProxy.image
            if (mediaImage != null && !isQrScanCoolingDown) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            val raw = barcodes.firstOrNull()?.rawValue
                            if (!raw.isNullOrBlank()) {
                                isQrScanCoolingDown = true
                                post {
                                    triggerHapticFeedback()
                                    onQrDetected?.invoke(raw)
                                    postDelayed({ isQrScanCoolingDown = false }, 3000L)
                                }
                            }
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        } else {
            // Live Document Boundary Detection strictly within active aspect ratio
            try {
                val fullBmp = imageProxy.toBitmap()
                val rotation = imageProxy.imageInfo.rotationDegrees
                imageProxy.close()

                val orientedBmp = if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rot = Bitmap.createBitmap(fullBmp, 0, 0, fullBmp.width, fullBmp.height, m, true)
                    if (rot != fullBmp) fullBmp.recycle()
                    rot
                } else {
                    fullBmp
                }

                // Crop to active aspect ratio framing so auto crop NEVER exceeds camera ratio
                val ratioBmp: Bitmap = when (currentRatioString) {
                    "1:1" -> {
                        val sz = minOf(orientedBmp.width, orientedBmp.height)
                        val x = (orientedBmp.width - sz) / 2
                        val y = (orientedBmp.height - sz) / 2
                        val cr = Bitmap.createBitmap(orientedBmp, x, y, sz, sz)
                        if (cr != orientedBmp) orientedBmp.recycle()
                        cr
                    }
                    "16:9" -> {
                        val isPortrait = orientedBmp.height > orientedBmp.width
                        if (isPortrait) {
                            val targetH = (orientedBmp.width * 16) / 9
                            if (targetH < orientedBmp.height) {
                                val y = (orientedBmp.height - targetH) / 2
                                val cr = Bitmap.createBitmap(orientedBmp, 0, y, orientedBmp.width, targetH)
                                if (cr != orientedBmp) orientedBmp.recycle()
                                cr
                            } else orientedBmp
                        } else {
                            val targetW = (orientedBmp.height * 16) / 9
                            if (targetW < orientedBmp.width) {
                                val x = (orientedBmp.width - targetW) / 2
                                val cr = Bitmap.createBitmap(orientedBmp, x, 0, targetW, orientedBmp.height)
                                if (cr != orientedBmp) orientedBmp.recycle()
                                cr
                            } else orientedBmp
                        }
                    }
                    "FULL" -> {
                        val isPortrait = orientedBmp.height > orientedBmp.width
                        val vH = height.toFloat().coerceAtLeast(1f)
                        val vW = width.toFloat().coerceAtLeast(1f)
                        val aspect = if (isPortrait) vH / vW else vW / vH
                        if (isPortrait) {
                            val targetH = (orientedBmp.width * aspect).toInt()
                            if (targetH < orientedBmp.height) {
                                val y = (orientedBmp.height - targetH) / 2
                                val cr = Bitmap.createBitmap(orientedBmp, 0, y, orientedBmp.width, targetH)
                                if (cr != orientedBmp) orientedBmp.recycle()
                                cr
                            } else orientedBmp
                        } else {
                            val targetW = (orientedBmp.height * aspect).toInt()
                            if (targetW < orientedBmp.width) {
                                val x = (orientedBmp.width - targetW) / 2
                                val cr = Bitmap.createBitmap(orientedBmp, x, 0, targetW, orientedBmp.height)
                                if (cr != orientedBmp) orientedBmp.recycle()
                                cr
                            } else orientedBmp
                        }
                    }
                    else -> orientedBmp
                }

                val targetW = 280
                val targetH = (targetW.toFloat() / ratioBmp.width * ratioBmp.height).toInt().coerceAtLeast(100)
                val smallBmp = Bitmap.createScaledBitmap(ratioBmp, targetW, targetH, true)
                if (smallBmp != ratioBmp) ratioBmp.recycle()

                val bounds = BoundaryDetector.detectDocumentBounds(smallBmp)
                smallBmp.recycle()

                post {
                    currentDetectedBounds = bounds
                    viewfinderOverlay.updateDetectedQuad(bounds)
                    viewfinderOverlay.isBoundaryLocked = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                imageProxy.close()
            }
        }
    }

    var isCoveredByOverlay: Boolean = false

    fun setIsCoveredByOverlay(covered: Boolean) {
        isCoveredByOverlay = covered
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // When covered by an overlay screen and not actively in retake mode, ignore touches
        if (isCoveredByOverlay && !isRetakeModeActive()) {
            return false
        }
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isCoveredByOverlay && !isRetakeModeActive()) {
            return false
        }
        scaleDetector.onTouchEvent(event)
        return true
    }

    fun triggerShutterCapture() {
        val capture = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true

        animateShutterFeedback()
        triggerHapticFeedback()

        // Visual snap feedback: crisp white shutter flash animation
        flashOverlay.visibility = View.VISIBLE
        flashOverlay.alpha = 0.85f
        flashOverlay.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { flashOverlay.visibility = View.GONE }
            .start()

        val capturedQuad = currentDetectedBounds.map { PointF(it.x, it.y) }
        val activeRetakeIdx = retakeTargetIndex
        val isQueueRetake = activeRetakeIdx != null && retakeUpdatesCameraQueue && activeRetakeIdx in activePages.indices

        // Immediately display skeleton loader in the queue so user gets instant visual feedback
        if (isQueueRetake && activeRetakeIdx != null) {
            thumbnailAdapter.setPageLoading(activeRetakeIdx, true)
            updateSessionUI()
            thumbnailRv.smoothScrollToPosition(activeRetakeIdx)
        } else {
            thumbnailAdapter.addPendingSkeleton()
            updateSessionUI()
            val lastPos = (thumbnailAdapter.itemCount - 1).coerceAtLeast(0)
            thumbnailRv.smoothScrollToPosition(lastPos)
        }

        try {
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = image.toBitmap()
                            val rotation = image.imageInfo.rotationDegrees
                            image.close()

                            val croppedBmp: Bitmap = when (currentRatioString) {
                                "1:1" -> {
                                    val size = minOf(bitmap.width, bitmap.height)
                                    val x = (bitmap.width - size) / 2
                                    val y = (bitmap.height - size) / 2
                                    val sq = Bitmap.createBitmap(bitmap, x, y, size, size)
                                    if (sq != bitmap) bitmap.recycle()
                                    sq
                                }
                                "16:9" -> {
                                    val isPortrait = bitmap.height > bitmap.width
                                    if (isPortrait) {
                                        val targetH = (bitmap.width * 16) / 9
                                        if (targetH < bitmap.height) {
                                            val y = (bitmap.height - targetH) / 2
                                            val cr = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, targetH)
                                            if (cr != bitmap) bitmap.recycle()
                                            cr
                                        } else bitmap
                                    } else {
                                        val targetW = (bitmap.height * 16) / 9
                                        if (targetW < bitmap.width) {
                                            val x = (bitmap.width - targetW) / 2
                                            val cr = Bitmap.createBitmap(bitmap, x, 0, targetW, bitmap.height)
                                            if (cr != bitmap) bitmap.recycle()
                                            cr
                                        } else bitmap
                                    }
                                }
                                "FULL" -> {
                                    val isPortrait = bitmap.height > bitmap.width
                                    val vH = height.toFloat().coerceAtLeast(1f)
                                    val vW = width.toFloat().coerceAtLeast(1f)
                                    val screenAspect = if (isPortrait) vH / vW else vW / vH
                                    if (isPortrait) {
                                        val targetH = (bitmap.width * screenAspect).toInt()
                                        if (targetH < bitmap.height) {
                                            val y = (bitmap.height - targetH) / 2
                                            val cr = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, targetH)
                                            if (cr != bitmap) bitmap.recycle()
                                            cr
                                        } else bitmap
                                    } else {
                                        val targetW = (bitmap.height * screenAspect).toInt()
                                        if (targetW < bitmap.width) {
                                            val x = (bitmap.width - targetW) / 2
                                            val cr = Bitmap.createBitmap(bitmap, x, 0, targetW, bitmap.height)
                                            if (cr != bitmap) bitmap.recycle()
                                            cr
                                        } else bitmap
                                    }
                                }
                                else -> bitmap // 4:3 default
                            }

                            val defaultFilter = com.clickem.scanner.util.settings.SettingsManager(context).defaultFilterMode
                            val page = storageManager.saveCapturedImage(croppedBmp, rotation).apply {
                                cropQuad = capturedQuad
                                filterMode = defaultFilter
                            }
                            storageManager.regeneratePageThumbnail(page)
                            croppedBmp.recycle()

                            post {
                                if (activeRetakeIdx != null) {
                                    if (isQueueRetake) {
                                        thumbnailAdapter.setPageLoading(activeRetakeIdx, false)
                                        replaceScannedPageAt(activeRetakeIdx, page)
                                    } else {
                                        thumbnailAdapter.removePendingSkeleton()
                                        updateSessionUI()
                                    }
                                    val cb = onRetakeCapturedCallback
                                    cancelRetakeMode()
                                    cb?.invoke(page, activeRetakeIdx)
                                } else {
                                    thumbnailAdapter.removePendingSkeleton()
                                    addScannedPage(page)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            post {
                                if (isQueueRetake && activeRetakeIdx != null) {
                                    thumbnailAdapter.setPageLoading(activeRetakeIdx, false)
                                } else {
                                    thumbnailAdapter.removePendingSkeleton()
                                }
                                updateSessionUI()
                            }
                        } finally {
                            isCapturing = false
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                        exception.printStackTrace()
                        post {
                            if (isQueueRetake && activeRetakeIdx != null) {
                                thumbnailAdapter.setPageLoading(activeRetakeIdx, false)
                            } else {
                                thumbnailAdapter.removePendingSkeleton()
                            }
                            updateSessionUI()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            isCapturing = false
            e.printStackTrace()
            if (isQueueRetake && activeRetakeIdx != null) {
                thumbnailAdapter.setPageLoading(activeRetakeIdx, false)
            } else {
                thumbnailAdapter.removePendingSkeleton()
            }
            updateSessionUI()
        }
    }

    private var hideZoomRunnable = Runnable {
        zoomIndicatorView.animate().alpha(0.75f).setDuration(220).start()
    }

    private fun showZoomIndicator(zoom: Float) {
        removeCallbacks(hideZoomRunnable)
        zoomIndicatorView.animate().cancel()
        zoomIndicatorView.alpha = 1f
        zoomIndicatorView.visibility = View.VISIBLE
        zoomIndicatorView.text = String.format(Locale.US, "%.1fx", zoom)
        postDelayed(hideZoomRunnable, 1400L)
    }

    fun adjustZoom(delta: Float) {
        lastManualZoomTimestamp = System.currentTimeMillis()
        camera?.cameraInfo?.zoomState?.value?.let { state ->
            minZoomRatio = state.minZoomRatio
            maxZoomRatio = state.maxZoomRatio
        }

        // 1. Calculate clamped zoom ratio using policy (+0.4f for Volume Up, -0.4f for Volume Down)
        activeZoomRatio = VolumeKeyPolicy.calculateClampedZoom(activeZoomRatio, delta, minZoomRatio, maxZoomRatio)

        // 2. Map to CameraX hyperbolic linear zoom [0.0f .. 1.0f] as fallback
        activeLinearZoom = VolumeKeyPolicy.calculateLinearZoomFromRatio(activeZoomRatio, minZoomRatio, maxZoomRatio)

        // 3. Apply CameraX zoomRatio directly so Volume Up (+) always zooms IN and Volume Down (-) always zooms OUT
        val ctrl = camera?.cameraControl
        if (ctrl != null) {
            try {
                ctrl.setZoomRatio(activeZoomRatio)
                ctrl.setLinearZoom(activeLinearZoom)
            } catch (_: Exception) {
                try {
                    ctrl.setLinearZoom(activeLinearZoom)
                } catch (_: Exception) {}
            }
        }

        // 4. Tactile feedback & visual HUD indicator
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}
        showZoomIndicator(activeZoomRatio)
    }

    /**
     * Delegates hardware volume key events to camera controls (shutter trigger or zoom adjustment).
     *
     * @param event The hardware KeyEvent dispatched from MainActivity
     * @param action The active VolumeKeyAction preference (SHUTTER, ZOOM, or OFF)
     * @return true if the event was consumed and should not propagate to system volume controls
     */
    fun onVolumeKeyEvent(event: KeyEvent, action: VolumeKeyAction): Boolean {
        if (!VolumeKeyPolicy.shouldConsumeKey(event.keyCode, action)) {
            return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (action) {
                    VolumeKeyAction.ZOOM -> {
                        val delta = VolumeKeyPolicy.calculateZoomDelta(event.keyCode)
                        adjustZoom(delta)
                    }
                    VolumeKeyAction.SHUTTER -> {
                        if (VolumeKeyPolicy.shouldTriggerShutter(event.repeatCount, isCapturing)) {
                            triggerShutterCapture()
                        }
                    }
                    VolumeKeyAction.OFF -> return false
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                // Consume key-up event to suppress OEM release sounds or system volume popup
                true
            }
            else -> true
        }
    }

    fun startRetakeForPage(
        index: Int,
        updateCameraQueue: Boolean = true,
        onRetakeCaptured: ((ScannedPage, Int) -> Unit)? = null
    ) {
        isCoveredByOverlay = false
        retakeTargetIndex = index
        retakeUpdatesCameraQueue = updateCameraQueue
        onRetakeCapturedCallback = onRetakeCaptured
        tvRetakeBannerText.text = "P${index + 1}"
        retakeBanner.bringToFront()
        val hudBottom = topHud.bottom
        if (hudBottom > 0) {
            val lp = retakeBanner.layoutParams as? FrameLayout.LayoutParams
            lp?.topMargin = hudBottom + (12 * density).toInt()
            retakeBanner.layoutParams = lp
        }
        retakeBanner.visibility = View.VISIBLE
        if (updateCameraQueue && index in activePages.indices) {
            thumbnailRv.smoothScrollToPosition(index)
        }
    }

    fun cancelRetakeMode() {
        val prevIdx = retakeTargetIndex
        retakeTargetIndex = null
        retakeUpdatesCameraQueue = true
        onRetakeCapturedCallback = null
        onRetakeCancelledCallback = null
        retakeBanner.visibility = View.GONE
        if (prevIdx != null) {
            thumbnailAdapter.setPageLoading(prevIdx, false)
        }
    }

    fun isRetakeModeActive(): Boolean = retakeTargetIndex != null

    fun completeExternalRetake(newPage: ScannedPage): Boolean {
        val targetIdx = retakeTargetIndex ?: return false
        if (retakeUpdatesCameraQueue && targetIdx in activePages.indices) {
            replaceScannedPageAt(targetIdx, newPage)
        }
        val cb = onRetakeCapturedCallback
        cancelRetakeMode()
        cb?.invoke(newPage, targetIdx)
        return true
    }

    fun replaceScannedPageAt(index: Int, newPage: ScannedPage) {
        if (index in activePages.indices) {
            activePages[index] = newPage
            thumbnailAdapter.notifyItemChanged(index)
            thumbnailRv.smoothScrollToPosition(index)
            updateSessionUI()
        }
    }

    fun addScannedPage(page: ScannedPage) {
        val insertIndex = activePages.size
        activePages.add(page)
        thumbnailAdapter.notifyItemInserted(insertIndex)
        thumbnailRv.smoothScrollToPosition(insertIndex)
        updateSessionUI()
    }

    private fun deletePageAt(index: Int) {
        if (index !in activePages.indices) return
        if (retakeUpdatesCameraQueue && retakeTargetIndex == index) {
            cancelRetakeMode()
        }
        activePages.removeAt(index)
        thumbnailAdapter.onPageRemovedAt(index)
        thumbnailAdapter.notifyItemRemoved(index)
        if (index < activePages.size) {
            thumbnailAdapter.notifyItemRangeChanged(index, activePages.size - index)
        }
        updateSessionUI()
    }

    fun resetFocusedQueueItem() {
        try {
            camera?.cameraControl?.cancelFocusAndMetering()
        } catch (_: Exception) {}
        if (activePages.isEmpty()) return
        val targetIndex = (activePages.size - 1).coerceAtLeast(0)
        val target = activePages[targetIndex]
        target.cropQuad = mutableListOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))
        target.rotationDegrees = 0
        target.filterMode = com.clickem.scanner.model.FilterMode.ORIGINAL
        storageManager.regeneratePageThumbnail(target)
        thumbnailAdapter.notifyItemChanged(targetIndex)
        Toast.makeText(context, "Reset page ${targetIndex + 1} to original", Toast.LENGTH_SHORT).show()
    }

    fun updateEditedPage(updatedPage: ScannedPage, index: Int) {
        if (index in activePages.indices) {
            activePages[index] = updatedPage.copyPage()
            thumbnailAdapter.notifyItemChanged(index)
            updateSessionUI()
        }
    }

    fun refreshQueue() {
        thumbnailAdapter.notifyDataSetChanged()
        updateSessionUI()
    }

    fun clearActivePages() {
        if (retakeUpdatesCameraQueue && isRetakeModeActive()) {
            cancelRetakeMode()
        }
        activePages.clear()
        thumbnailAdapter.clearPendingSkeletons()
        thumbnailAdapter.notifyDataSetChanged()
        updateSessionUI()
    }

    private fun updatePreviewLayout() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val r = viewfinderOverlay.getActiveViewportRect(w.toFloat(), h.toFloat())
        val lp = previewView.layoutParams as? LayoutParams ?: LayoutParams(w, h)
        if (currentRatioString == "FULL") {
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = LayoutParams.MATCH_PARENT
            lp.topMargin = 0
            lp.leftMargin = 0
        } else {
            lp.width = r.width().toInt()
            lp.height = r.height().toInt()
            lp.topMargin = r.top.toInt()
            lp.leftMargin = r.left.toInt()
        }
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.layoutParams = lp
    }

    fun setAspectRatio(ratioStr: String) {
        currentRatioString = ratioStr
        topHud.setRatioDisplay(ratioStr)
        viewfinderOverlay.activeAspectRatio = ratioStr
        updatePreviewLayout()
        cameraTargetRatio = if (ratioStr == "4:3") AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        startCamera()
    }

    private fun updateSessionUI() {
        val count = activePages.size
        val totalQueueItems = count + thumbnailAdapter.pendingSkeletonCount
        thumbnailRv.visibility = if (totalQueueItems > 0) View.VISIBLE else View.GONE

        if (count > 0) {
            btnDone.visibility = View.VISIBLE
            btnQuickLoadout.visibility = View.GONE
            tvDoneCount.text = "$count"
            btnDone.contentDescription = "Finish scanning, $count ${if (count == 1) "page" else "pages"} captured"
        } else {
            btnDone.visibility = View.INVISIBLE
            btnQuickLoadout.visibility = View.VISIBLE
        }

        btnResetFocus.visibility = View.GONE
        btnClearQueue.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun animateShutterFeedback() {
        btnShutterRing.animate().cancel()
        shutterCore.animate().cancel()
        btnShutterRing.scaleX = 1f
        btnShutterRing.scaleY = 1f
        btnShutterRing.alpha = 1f
        shutterCore.scaleX = 1f
        shutterCore.scaleY = 1f

        // Tactile snap: Core compresses, outer frosted ring pulses smoothly without clipping
        shutterCore.animate()
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(70)
            .withEndAction {
                shutterCore.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
            }
            .start()

        btnShutterRing.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .alpha(0.65f)
            .setDuration(90)
            .withEndAction {
                btnShutterRing.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(130)
                    .start()
            }
            .start()

        // Subtle viewfinder snap flash
        flashOverlay.visibility = View.VISIBLE
        flashOverlay.alpha = 0.5f
        flashOverlay.animate().alpha(0f).setDuration(110).withEndAction {
            flashOverlay.visibility = View.GONE
        }.start()
    }

    fun resumeSensor() {
        if (!isSensorRegistered) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                isSensorRegistered = true
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    fun pauseSensor() {
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
        orientationEventListener.disable()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeSensor()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pauseSensor()
        isCapturing = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            topHud.updateHorizon(ax, ay)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
