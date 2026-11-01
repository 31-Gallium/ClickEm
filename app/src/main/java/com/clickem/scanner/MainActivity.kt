package com.clickem.scanner

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import android.view.KeyEvent
import com.clickem.scanner.model.DocumentProject
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.ui.batch.BatchReviewView
import com.clickem.scanner.ui.camera.CameraView
import com.clickem.scanner.ui.edit.SinglePageEditView
import com.clickem.scanner.ui.loadout.LoadoutView
import com.clickem.scanner.ui.settings.SettingsView
import com.clickem.scanner.util.cv.TfliteDocumentDetector
import com.clickem.scanner.util.settings.SettingsManager
import com.clickem.scanner.util.settings.VolumeKeyAction
import com.clickem.scanner.util.storage.ScanStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var storageManager: ScanStorageManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var loadingProgressBar: ProgressBar

    private var cameraView: CameraView? = null
    // True LIFO backstack for overlay navigation
    private val overlayBackStack = ArrayDeque<View>()
    private var lastKnownWindowInsets: androidx.core.view.WindowInsetsCompat? = null

    @Suppress("DEPRECATION")
    private var fingerprintCancellationSignal: androidx.core.os.CancellationSignal? = null

    // Multiple file picker for both Images and PDFs
    private val galleryAndPdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            importFilesFromUris(uris)
        }
    }

    // Camera permission request launcher
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupDefaultCameraScreen()
        } else {
            showPermissionDeniedFallback()
        }
    }

    private var currentSettingsView: SettingsView? = null

    // Storage Access Framework folder picker launcher
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val displayPath = formatTreeUriDisplayPath(uri)
            settingsManager.exportFolderUri = uri.toString()
            settingsManager.exportFolderPath = displayPath
            currentSettingsView?.updateExportLocation(displayPath)
            Toast.makeText(this, "Export location set to: $displayPath", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTreeUriDisplayPath(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val sub = docId.removePrefix("primary:")
                if (sub.isEmpty()) "/Internal Storage/" else "/$sub/"
            } else if (docId.contains(":")) {
                val parts = docId.split(":")
                if (parts.size == 2) "/${parts[1]}/" else uri.path ?: "/Downloads/ClickEm/"
            } else {
                uri.path ?: "/Downloads/ClickEm/"
            }
        } catch (_: Exception) {
            "/Downloads/ClickEm/"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        storageManager = (application as ClickEmApplication).storageManager
        settingsManager = SettingsManager(this)

        // Asynchronously initialize on-device deep learning boundary detection model
        CoroutineScope(Dispatchers.IO).launch {
            TfliteDocumentDetector.init(applicationContext)
        }

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            requestFocus()
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            lastKnownWindowInsets = insets
            for (i in 0 until rootContainer.childCount) {
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(rootContainer.getChildAt(i), insets)
            }
            insets
        }
        setContentView(rootContainer)

        loadingProgressBar = ProgressBar(this).apply {
            visibility = View.GONE
            val sz = (50 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER
            }
        }
        rootContainer.addView(loadingProgressBar)

        setupBackPressHandling()

        if (hasCameraPermission()) {
            setupDefaultCameraScreen()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        rootContainer.postDelayed({
            com.clickem.scanner.util.update.UpdateManager.checkForUpdate(this) { updateInfo ->
                if (updateInfo?.hasUpdate == true) {
                    android.widget.Toast.makeText(
                        this,
                        "Update available: ${updateInfo.latestVersion}. Check Settings to update.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, 3000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            window.decorView.requestApplyInsets()
        }
    }

    override fun onResume() {
        super.onResume()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        cameraView?.resumeSensor()
        rootContainer.requestFocus()
        cameraView?.requestFocus()
        startFingerprintListener()
    }

    override fun onPause() {
        super.onPause()
        cameraView?.pauseSensor()
        stopFingerprintListener()
    }

    @Suppress("DEPRECATION")
    private fun startFingerprintListener() {
        if (!settingsManager.isFingerprintShutterEnabled) return
        try {
            fingerprintCancellationSignal?.cancel()
            val compatSignal = androidx.core.os.CancellationSignal()
            fingerprintCancellationSignal = compatSignal

            val fpCompat = androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(this)

            fpCompat.authenticate(null, 0, compatSignal, object : androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationResult) {
                    if (overlayBackStack.isEmpty() || cameraView?.isRetakeModeActive() == true) {
                        cameraView?.triggerShutterCapture()
                    }
                    rootContainer.postDelayed({ startFingerprintListener() }, 1000L)
                }
                override fun onAuthenticationFailed() {
                    if (overlayBackStack.isEmpty() || cameraView?.isRetakeModeActive() == true) {
                        cameraView?.triggerShutterCapture()
                    }
                    rootContainer.postDelayed({ startFingerprintListener() }, 1000L)
                }
            }, null)
        } catch (_: Exception) {}
    }

    private fun stopFingerprintListener() {
        try {
            fingerprintCancellationSignal?.cancel()
            fingerprintCancellationSignal = null
        } catch (_: Exception) {}
    }

    private fun isCameraInteractive(): Boolean {
        return overlayBackStack.isEmpty() || cameraView?.isRetakeModeActive() == true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && isCameraInteractive()) {
            val handled = cameraView?.onVolumeKeyEvent(event, settingsManager.volumeKeyAction) ?: false
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && isCameraInteractive()) {
            val keyEvent = event ?: KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val handled = cameraView?.onVolumeKeyEvent(keyEvent, settingsManager.volumeKeyAction) ?: false
            if (handled) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && isCameraInteractive()) {
            val keyEvent = event ?: KeyEvent(KeyEvent.ACTION_UP, keyCode)
            val handled = cameraView?.onVolumeKeyEvent(keyEvent, settingsManager.volumeKeyAction) ?: false
            if (handled) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDeniedFallback() {
        val fallbackLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (32 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val tvMsg = TextView(this).apply {
            text = "Camera access is needed to scan documents.\nYou can also import existing photos or PDFs."
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        fallbackLayout.addView(tvMsg)

        val btnRetry = TextView(this).apply {
            text = "Grant Permission"
            textSize = 14f
            setTextColor(Color.parseColor("#090b10"))
            paint.isFakeBoldText = true
            val hP = (24 * resources.displayMetrics.density).toInt()
            val vP = (12 * resources.displayMetrics.density).toInt()
            setPadding(hP, vP, hP, vP)
            val bg = GradientDrawable().apply {
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(Color.parseColor("#FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            setOnClickListener {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        fallbackLayout.addView(btnRetry)

        val btnGalleryOnly = TextView(this).apply {
            text = "Open Device Files"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
            setOnClickListener {
                galleryAndPdfPickerLauncher.launch(arrayOf("image/*", "application/pdf"))
            }
        }
        fallbackLayout.addView(btnGalleryOnly)

        rootContainer.addView(fallbackLayout, 0)
    }

    private fun setupDefaultCameraScreen() {
        if (cameraView != null) return

        cameraView = CameraView(
            context = this,
            lifecycleOwner = this,
            storageManager = storageManager,
            onGalleryPickRequested = {
                galleryAndPdfPickerLauncher.launch(arrayOf("image/*", "application/pdf"))
            },
            onEditPageRequested = { page, index ->
                if (cameraView?.isRetakeModeActive() == true) {
                    cameraView?.cancelRetakeMode()
                }
                showSinglePageEditor(page, index)
            },
            onDoneRequested = { pages ->
                if (cameraView?.isRetakeModeActive() == true) {
                    cameraView?.cancelRetakeMode()
                }
                showBatchReview(pages)
            },
            onLoadoutRequested = {
                if (cameraView?.isRetakeModeActive() == true) {
                    cameraView?.cancelRetakeMode()
                }
                showLoadout()
            },
            onSettingsRequested = {
                showSettings()
            }
        ).apply {
            onQrDetected = { qrText ->
                showQrResultDialog(qrText)
            }
            setAspectRatio(settingsManager.cameraAspectRatio)
        }

        rootContainer.addView(cameraView, 0)
        cameraView?.setIsCoveredByOverlay(false)
        lastKnownWindowInsets?.let {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(cameraView!!, it)
        }
        cameraView?.requestApplyInsets()
    }

    /**
     * Imports Images & PDFs asynchronously on background IO thread with EXIF orientation handling.
     */
    private fun importFilesFromUris(uris: List<Uri>) {
        loadingProgressBar.visibility = View.VISIBLE
        val cam = cameraView
        val wasRetakeSession = cam?.isRetakeModeActive() == true

        CoroutineScope(Dispatchers.IO).launch {
            var importedCount = 0
            var retakeFulfilled = false

            for (uri in uris) {
                if (retakeFulfilled) break
                val mimeType = contentResolver.getType(uri) ?: ""
                val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                        uri.toString().contains(".pdf", ignoreCase = true)

                if (isPdf) {
                    // Extract pages from PDF using Android's native PdfRenderer
                    try {
                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            val renderer = PdfRenderer(pfd)
                            for (pageIdx in 0 until renderer.pageCount) {
                                if (retakeFulfilled) break
                                val page = renderer.openPage(pageIdx)
                                val w = (page.width * 2).coerceAtMost(2400)
                                val h = (page.height * 2).coerceAtMost(3200)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                canvas.drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                page.close()

                                val scannedPage = storageManager.saveCapturedImage(bmp)
                                bmp.recycle()

                                withContext(Dispatchers.Main) {
                                    if (cam?.isRetakeModeActive() == true) {
                                        cam.completeExternalRetake(scannedPage)
                                        retakeFulfilled = true
                                    } else {
                                        cam?.addScannedPage(scannedPage)
                                    }
                                }
                                importedCount++
                            }
                            renderer.close()
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                } else {
                    // Import and orient image
                    try {
                        val rotation = readExifOrientation(uri)
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            val options = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val bmp = BitmapFactory.decodeStream(inputStream, null, options)
                            if (bmp != null) {
                                val scannedPage = storageManager.saveCapturedImage(bmp, rotation)
                                bmp.recycle()
                                withContext(Dispatchers.Main) {
                                    if (cam?.isRetakeModeActive() == true) {
                                        cam.completeExternalRetake(scannedPage)
                                        retakeFulfilled = true
                                    } else {
                                        cam?.addScannedPage(scannedPage)
                                    }
                                }
                                importedCount++
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                loadingProgressBar.visibility = View.GONE
                if (!wasRetakeSession) {
                    Toast.makeText(this@MainActivity, "Imported $importedCount page(s) to queue", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun readExifOrientation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun showSinglePageEditor(page: ScannedPage, index: Int) {
        val pagesList = cameraView?.activePages ?: mutableListOf(page)
        showMultiPageEditor(pagesList, index) {
            cameraView?.refreshQueue()
        }
    }

    private fun showMultiPageEditor(
        pagesList: MutableList<ScannedPage>,
        initialIndex: Int,
        onUpdated: (() -> Unit)? = null
    ) {
        val editView = SinglePageEditView(
            context = this,
            pages = pagesList,
            initialIndex = initialIndex,
            storageManager = storageManager,
            onPagesSaved = { updatedPages ->
                for (i in updatedPages.indices) {
                    if (i < pagesList.size) {
                        pagesList[i].cropQuad = updatedPages[i].cropQuad
                        pagesList[i].rotationDegrees = updatedPages[i].rotationDegrees
                        pagesList[i].filterMode = updatedPages[i].filterMode
                        pagesList[i].filterIntensity = updatedPages[i].filterIntensity
                        pagesList[i].thumbnailPath = updatedPages[i].thumbnailPath
                    }
                }
                cameraView?.refreshQueue()
                onUpdated?.invoke()
                popOverlay()
                Toast.makeText(this, "Saved changes", Toast.LENGTH_SHORT).show()
            },
            onBackToCamera = {
                popOverlay()
            },
            onRetakeRequested = { retakeIdx ->
                popOverlay()
                cameraView?.setIsCoveredByOverlay(false)
                val underlyingOverlay = overlayBackStack.peek()
                underlyingOverlay?.visibility = View.GONE
                startFingerprintListener()
                val isCameraQueue = (pagesList === cameraView?.activePages)

                cameraView?.onRetakeCancelledCallback = {
                    underlyingOverlay?.visibility = View.VISIBLE
                    showMultiPageEditor(pagesList, retakeIdx, onUpdated)
                }

                cameraView?.startRetakeForPage(retakeIdx, updateCameraQueue = isCameraQueue) { newPage, replacedIdx ->
                    if (replacedIdx in pagesList.indices) {
                        pagesList[replacedIdx] = newPage
                    }
                    cameraView?.refreshQueue()
                    onUpdated?.invoke()
                    underlyingOverlay?.visibility = View.VISIBLE
                    Toast.makeText(this, "Retook page ${replacedIdx + 1} in place", Toast.LENGTH_SHORT).show()
                    showMultiPageEditor(pagesList, replacedIdx, onUpdated)
                }
            }
        )
        pushOverlay(editView)
    }

    private fun showBatchReview(pages: List<ScannedPage>) {
        val activeQueue = cameraView?.activePages ?: mutableListOf()
        val pagesToReview = if (activeQueue.isNotEmpty()) activeQueue else pages.toMutableList()
        lateinit var batchView: BatchReviewView
        batchView = BatchReviewView(
            context = this,
            pages = pagesToReview,
            storageManager = storageManager,
            onEditPageRequested = { allPages, idx ->
                showMultiPageEditor(allPages, idx) {
                    batchView.notifyPagesUpdated()
                }
            },
            onResumeCameraRequested = {
                cameraView?.refreshQueue()
                popOverlay()
            },
            onLoadoutRequested = {
                cameraView?.clearActivePages()
                popOverlay()
                showLoadout()
            },
            existingProject = null
        )
        pushOverlay(batchView)
    }

    private fun showLoadout() {
        val loadoutView = LoadoutView(
            context = this,
            storageManager = storageManager,
            onOpenProject = { project ->
                openProjectFromLoadout(project)
            },
            onOpenProjectAtPage = { project, pageIdx ->
                openProjectFromLoadout(project, pageIdx)
            },
            onReturnToCamera = {
                popOverlay()
            }
        )
        pushOverlay(loadoutView)
    }

    private fun openProjectFromLoadout(project: DocumentProject, initialPageIndex: Int = -1) {
        if (initialPageIndex >= 0 && initialPageIndex < project.pages.size) {
            showMultiPageEditor(project.pages, initialPageIndex) {
                project.updatedAt = System.currentTimeMillis()
                storageManager.saveProject(project)
            }
            return
        }

        lateinit var batchView: BatchReviewView
        batchView = BatchReviewView(
            context = this,
            pages = project.pages,
            storageManager = storageManager,
            onEditPageRequested = { allPages, idx ->
                showMultiPageEditor(allPages, idx) {
                    project.updatedAt = System.currentTimeMillis()
                    storageManager.saveProject(project)
                    batchView.notifyPagesUpdated()
                }
            },
            onResumeCameraRequested = {
                popOverlay()
            },
            onLoadoutRequested = {
                popOverlay()
            },
            existingProject = project
        )
        pushOverlay(batchView)
    }

    private fun showSettings() {
        val settingsView = SettingsView(
            context = this,
            settingsManager = settingsManager,
            onClose = {
                currentSettingsView = null
                cameraView?.setAspectRatio(settingsManager.cameraAspectRatio)
                popOverlay()
            },
            onPickExportFolder = {
                val initialUri = settingsManager.exportFolderUri?.let { Uri.parse(it) }
                folderPickerLauncher.launch(initialUri)
            }
        )
        currentSettingsView = settingsView
        pushOverlay(settingsView)
    }

    /**
     * Push overlay onto backstack with smooth entry animation.
     */
    private fun pushOverlay(view: View) {
        stopFingerprintListener()
        val currentTop = overlayBackStack.peek()
        if (currentTop != null) {
            currentTop.visibility = View.GONE
        }

        // Prevent click/touch pass-through to underlying screens
        view.isClickable = true
        view.isFocusable = true
        cameraView?.setIsCoveredByOverlay(true)

        overlayBackStack.push(view)
        view.alpha = 0f
        view.translationY = 40f
        rootContainer.addView(view)
        lastKnownWindowInsets?.let {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view, it)
        }
        view.requestApplyInsets()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    /**
     * Pops overlay from backstack, smoothly restoring previous view.
     */
    private fun popOverlay(): Boolean {
        if (overlayBackStack.isEmpty()) return false

        val topView = overlayBackStack.pop()
        if (topView is SettingsView) {
            currentSettingsView = null
        }
        topView.animate()
            .alpha(0f)
            .translationY(40f)
            .setDuration(160)
            .withEndAction {
                rootContainer.removeView(topView)
                val previousView = overlayBackStack.peek()
                if (previousView != null && cameraView?.isRetakeModeActive() != true) {
                    previousView.visibility = View.VISIBLE
                    previousView.alpha = 1f
                    previousView.translationY = 0f
                    if (previousView is LoadoutView) {
                        previousView.refreshProjects()
                    }
                } else {
                    previousView?.visibility = View.GONE
                    cameraView?.setIsCoveredByOverlay(false)
                    rootContainer.requestFocus()
                    cameraView?.requestFocus()
                    startFingerprintListener()
                }
            }
            .start()
        return true
    }

    private fun showQrResultDialog(qrContent: String) {
        val density = resources.displayMetrics.density
        var alert: AlertDialog? = null

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * density).toInt()
            setPadding(p, p, p, p)
            val bg = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(Color.parseColor("#12151D"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
        }

        val tvTitle = TextView(this).apply {
            text = "QR Code Scanned"
            textSize = 17f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val btnCloseIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close QR dialog"
            val sz = (40 * density).toInt()
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#8C96A5"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            setOnClickListener { alert?.dismiss() }
        }
        headerRow.addView(btnCloseIcon)
        dialogView.addView(headerRow)

        val tvContent = TextView(this).apply {
            text = qrContent
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            val bg = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#090B10"))
                setStroke((1 * density).toInt(), Color.parseColor("#25FFFFFF"))
            }
            background = bg
            val pX = (14 * density).toInt()
            val pY = (12 * density).toInt()
            setPadding(pX, pY, pX, pY)
        }
        dialogView.addView(tvContent)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, (18 * density).toInt(), 0, 0)
        }

        val isUrl = qrContent.startsWith("http://") || qrContent.startsWith("https://")

        val btnCopy = TextView(this).apply {
            text = "Copy"
            textSize = 13f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            val hP = (16 * density).toInt()
            val vP = (10 * density).toInt()
            setPadding(hP, vP, hP, vP)
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (isUrl) marginEnd = (10 * density).toInt()
            }
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Scanned QR", qrContent)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                alert?.dismiss()
            }
        }
        actionsRow.addView(btnCopy)

        if (isUrl) {
            val btnOpen = TextView(this).apply {
                text = "Open Link"
                textSize = 13f
                setTextColor(Color.parseColor("#090B10"))
                paint.isFakeBoldText = true
                val hP = (18 * density).toInt()
                val vP = (10 * density).toInt()
                setPadding(hP, vP, hP, vP)
                background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor(Color.parseColor("#FFFFFF"))
                }
                setOnClickListener {
                    try {
                        startActivity(Intent.parseUri(qrContent, Intent.URI_INTENT_SCHEME))
                    } catch (_: Exception) {}
                    alert?.dismiss()
                }
            }
            actionsRow.addView(btnOpen)
        }

        dialogView.addView(actionsRow)

        alert = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        alert.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alert.show()
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val topOverlay = overlayBackStack.peek()
                if (topOverlay != null && topOverlay.visibility == View.VISIBLE) {
                    popOverlay()
                    return
                }
                val cam = cameraView
                if (cam != null && cam.isRetakeModeActive()) {
                    val cancelCb = cam.onRetakeCancelledCallback
                    cam.cancelRetakeMode()
                    cancelCb?.invoke()
                    return
                }
                if (!popOverlay()) {
                    finish()
                }
            }
        })
    }
}
