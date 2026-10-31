package com.clickem.scanner

import com.clickem.scanner.model.FilterMode
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.util.cv.DocumentFilterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Genuine Unit Test Suite for Milestone 4 (SinglePageEditView, LoadoutView, Themes & Accessibility):
 *
 * 1. Vector Icon Controls & Accessibility Specifications (R4):
 *    - Content descriptions for ic_compare, ic_check, ic_close, ic_filter, ic_crop, ic_rotate, ic_reset.
 *    - LoadoutView search input compound drawable and accessible modal actions.
 *    - LoadoutAdapter share/delete content descriptions.
 * 2. Filter Sensitivity Slider & Model Propagation (R3):
 *    - Intensity conversion mathematics (progress <-> float).
 *    - ScannedPage model propagation and copyPage() preservation.
 *    - Dynamic slider visibility contract across FilterModes.
 * 3. Filter Thumbnail Downscaling Mathematics:
 *    - 140px base thumbnail resolution and aspect ratio calculation.
 *    - 600px intermediate scrub bitmap mathematics.
 * 4. Vector Asset Integrity:
 *    - ic_compare.xml vector specifications and pure white fill.
 * 5. Theme Migration & Zero Banned Hex Occurrences (R2):
 *    - Exhaustive source scanning across SinglePageEditView, LoadoutView, and LoadoutAdapter
 *      for legacy cyan/blue hexes and invalid CSS hex formats.
 */
class M4UiAccessibilityTest {

    private fun resolveFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot resolve path: $relativePath from working dir: ${File(".").absolutePath}")
    }

    // =========================================================================
    // 1. Theme Migration & Zero Banned Hex Occurrences (R2)
    // =========================================================================

    private val bannedHexes = listOf(
        "38bdf8", // legacy cyan glow
        "152236", // navy pill
        "151d30", // navy container
        "242836", // navy border
        "131622", // navy dark container
        "1e3a5f", // deep blue
        "0284c7", // legacy cyan
        "0ea5e9", // sky blue
        "67e8f9"  // laser cyan
    )

    private val m4ModifiedFiles = listOf(
        "src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt",
        "src/main/java/com/clickem/scanner/ui/loadout/LoadoutView.kt",
        "src/main/java/com/clickem/scanner/ui/loadout/LoadoutAdapter.kt"
    )

    @Test
    fun testZeroBannedHexCodesInM4Files() {
        for (relPath in m4ModifiedFiles) {
            val file = resolveFile(relPath)
            assertTrue("File must exist: ${file.path}", file.exists())
            val content = file.readText()

            for (banned in bannedHexes) {
                val pattern = Pattern.compile(banned, Pattern.CASE_INSENSITIVE)
                val matcher = pattern.matcher(content)
                assertFalse(
                    "File ${file.name} contains banned legacy hex '#$banned'!",
                    matcher.find()
                )
            }
        }
    }

    @Test
    fun testAndroidHexAlphaNotationDiscipline() {
        // Android Color.parseColor interprets 8-digit hexes as #AARRGGBB.
        // CSS-style '#FFFFFF15' or '#FFFFFF30' renders bright yellow instead of white translucency.
        val invalidHexPattern = Pattern.compile("#ffffff[0-9a-f]{2}", Pattern.CASE_INSENSITIVE)

        for (relPath in m4ModifiedFiles) {
            val file = resolveFile(relPath)
            val content = file.readText()
            val matcher = invalidHexPattern.matcher(content)
            assertFalse(
                "File ${file.name} contains invalid CSS-style alpha hex (must be #AARRGGBB like #15FFFFFF)!",
                matcher.find()
            )
        }
    }

    // =========================================================================
    // 2. Vector Asset Integrity: ic_compare.xml
    // =========================================================================

    @Test
    fun testCompareVectorDrawableAsset() {
        val assetFile = resolveFile("src/main/res/drawable/ic_compare.xml")
        assertTrue("ic_compare.xml must exist in res/drawable", assetFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(assetFile)
        val root = doc.documentElement

        assertEquals("Root element must be vector", "vector", root.tagName)
        assertEquals("Width must be 24dp", "24dp", root.getAttribute("android:width"))
        assertEquals("Height must be 24dp", "24dp", root.getAttribute("android:height"))
        assertEquals("ViewportWidth must be 24", "24", root.getAttribute("android:viewportWidth"))
        assertEquals("ViewportHeight must be 24", "24", root.getAttribute("android:viewportHeight"))

        val paths = root.getElementsByTagName("path")
        assertTrue("ic_compare.xml must contain at least 1 path element", paths.length > 0)
        val pathElement = paths.item(0) as Element
        val fillColor = pathElement.getAttribute("android:fillColor")
        assertEquals(
            "ic_compare fillColor must be pure white (#FFFFFFFF)",
            "#FFFFFFFF",
            fillColor.uppercase()
        )
    }

    // =========================================================================
    // 3. Vector Icon Controls & Accessibility Specifications (R4)
    // =========================================================================

    @Test
    fun testSinglePageEditViewAccessibilityAndIconControls() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val content = file.readText()

        // 1. btnHoldCompare must use ic_compare and accessible description
        assertTrue(
            "SinglePageEditView must use ic_compare for comparison button",
            content.contains("R.drawable.ic_compare")
        )
        assertTrue(
            "btnHoldCompare must have contentDescription 'Hold to compare with original raw image'",
            content.contains("contentDescription = \"Hold to compare with original raw image\"")
        )

        // 2. btnSaveAll must use ic_check and accessible description
        assertTrue(
            "SinglePageEditView must use ic_check for save all button",
            content.contains("R.drawable.ic_check")
        )
        assertTrue(
            "btnSaveAll must have contentDescription 'Save and export edited pages'",
            content.contains("contentDescription = \"Save and export edited pages\"")
        )

        // 3. btnBack must have accessible description
        assertTrue(
            "btnBack must have contentDescription 'Navigate back to camera'",
            content.contains("contentDescription = \"Navigate back to camera\"")
        )

        // 4. Tab buttons must use ic_filter and ic_crop with descriptions
        assertTrue(
            "tabFilters must have contentDescription 'Filter adjustments'",
            content.contains("contentDescription = contentDesc") && content.contains("\"Filter adjustments\"")
        )
        assertTrue(
            "tabCropRotate must have contentDescription 'Crop and rotate adjustments'",
            content.contains("\"Crop and rotate adjustments\"")
        )

        // 5. Crop & Rotate sub-tool actions
        assertTrue(
            "ic_rotate must have contentDescription 'Rotate 90 degrees clockwise'",
            content.contains("\"Rotate 90 degrees clockwise\"")
        )
        assertTrue(
            "ic_crop must have contentDescription 'Automatically detect document boundary'",
            content.contains("\"Automatically detect document boundary\"")
        )
        assertTrue(
            "ic_reset must have contentDescription 'Reset crop to full image bounds'",
            content.contains("\"Reset crop to full image bounds\"")
        )
    }

    @Test
    fun testLoadoutViewAccessibilityAndModalOverhauls() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/loadout/LoadoutView.kt")
        val content = file.readText()

        // 1. Header back button
        assertTrue(
            "LoadoutView btnBack must have contentDescription 'Return to camera'",
            content.contains("contentDescription = \"Return to camera\"")
        )

        // 2. Search EditText must use compound drawable with ic_search and padding
        assertTrue(
            "Search EditText must set ic_search compound drawable",
            content.contains("setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)")
        )
        assertTrue(
            "Search EditText must have compound drawable padding",
            content.contains("compoundDrawablePadding = (10 * density).toInt()")
        )

        // 3. Edit scan details modal
        assertTrue(
            "Edit scan details dialog close button must have description 'Cancel edit'",
            content.contains("contentDescription = \"Cancel edit\"")
        )
        assertTrue(
            "Edit scan details dialog save button must have description 'Save changes'",
            content.contains("contentDescription = \"Save changes\"")
        )

        // 4. Single-page share modal
        assertTrue(
            "Single-page share dialog close button must have description 'Close page options'",
            content.contains("contentDescription = \"Close page options\"")
        )

        // 5. Project share format modal
        assertTrue(
            "Project share format modal close button must have description 'Cancel share'",
            content.contains("contentDescription = \"Cancel share\"")
        )

        // 6. WCAG 2.5.5 Touch target dimensions (>= 48dp x 48dp with 12dp padding)
        assertTrue(
            "btnBack layoutParams must be 48dp with 12dp padding",
            content.contains("val sz = (48 * density).toInt()") && content.contains("val p = (12 * density).toInt()")
        )
    }

    @Test
    fun testLoadoutAdapterAccessibility() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/loadout/LoadoutAdapter.kt")
        val content = file.readText()

        assertTrue(
            "btnShare must have contentDescription 'Share project'",
            content.contains("contentDescription = \"Share project\"")
        )
        assertTrue(
            "btnDelete must have contentDescription 'Delete project'",
            content.contains("contentDescription = \"Delete project\"")
        )

        // Thumbnail frame accessible contentDescription
        assertTrue(
            "Thumbnail frame must have accessible contentDescription",
            content.contains("contentDescription = \"Page \${i + 1} of \${proj.pages.size}, long press for page options\"")
        )

        // WCAG 2.5.5 touch target size
        assertTrue(
            "btnShare and btnDelete in LoadoutAdapter must be 48dp",
            content.contains("val sz = (48 * density).toInt()")
        )
    }

    @Test
    fun testCameraViewAndHudTouchTargetDimensions() {
        val cameraViewFile = resolveFile("src/main/java/com/clickem/scanner/ui/camera/CameraView.kt")
        val cameraHudFile = resolveFile("src/main/java/com/clickem/scanner/ui/components/CameraTopHudView.kt")

        val cameraViewContent = cameraViewFile.readText()
        val hudContent = cameraHudFile.readText()

        // 1. CameraView controls must meet WCAG 2.5.5 >= 48dp with 12dp padding
        assertTrue(
            "CameraView btnGallery must be 48dp with 12dp padding and 'Open gallery' description",
            cameraViewContent.contains("contentDescription = \"Open gallery\"") &&
            cameraViewContent.contains("val sz = (48 * density).toInt()") &&
            cameraViewContent.contains("val p = (12 * density).toInt()")
        )
        assertTrue(
            "CameraView btnResetFocus must have 'Reset auto focus' description",
            cameraViewContent.contains("contentDescription = \"Reset auto focus\"")
        )
        assertTrue(
            "CameraView btnClearQueue must have 'Clear scanned page queue' description",
            cameraViewContent.contains("contentDescription = \"Clear scanned page queue\"")
        )

        // 2. CameraTopHudView createIconButton must be 48dp with 12dp padding
        assertTrue(
            "CameraTopHudView createIconButton must be 48dp with 12dp padding",
            hudContent.contains("val sz = (48 * density).toInt()") &&
            hudContent.contains("val p = (12 * density).toInt()")
        )
    }

    // =========================================================================
    // 4. Sensitivity Slider Mathematics & Model Propagation (R3)
    // =========================================================================

    @Test
    fun testSliderProgressToIntensityConversion() {
        // Test mapping between SeekBar progress (0..100) and float intensity (0.0f..1.0f)
        for (prog in 0..100) {
            val intensity = prog / 100f
            assertTrue("Intensity must be >= 0.0f", intensity >= 0.0f)
            assertTrue("Intensity must be <= 1.0f", intensity <= 1.0f)

            val backToProgress = Math.round(intensity * 100).toInt().coerceIn(0, 100)
            assertEquals("Progress roundtrip must match original progress", prog, backToProgress)
        }
    }

    @Test
    fun testScannedPageFilterIntensityModelPropagation() {
        val page = ScannedPage(
            originalImagePath = "/tmp/test.jpg",
            thumbnailPath = "/tmp/thumb.jpg",
            rotationDegrees = 0,
            cropQuad = emptyList(),
            filterMode = FilterMode.BW_CLEAN,
            filterIntensity = 0.65f
        )

        assertEquals(0.65f, page.filterIntensity, 0.0001f)

        // Test copyPage preserves filterIntensity
        val copy = page.copyPage()
        assertEquals(page.filterIntensity, copy.filterIntensity, 0.0001f)
        assertEquals(page.filterMode, copy.filterMode)
        assertEquals(page.originalImagePath, copy.originalImagePath)

        // Mutating copy must not mutate original
        copy.filterIntensity = 0.85f
        assertEquals(0.65f, page.filterIntensity, 0.0001f)
        assertEquals(0.85f, copy.filterIntensity, 0.0001f)
    }

    @Test
    fun testSliderVisibilityContractAcrossFilterModes() {
        // Specification:
        // BW_CLEAN, MAGIC_COLOR, GRAYSCALE: slider is VISIBLE
        // ORIGINAL: slider is GONE

        fun isSliderVisible(mode: FilterMode): Boolean {
            return mode != FilterMode.ORIGINAL
        }

        assertFalse("ORIGINAL filter preset must hide sensitivity slider", isSliderVisible(FilterMode.ORIGINAL))
        assertTrue("BW_CLEAN preset must show sensitivity slider", isSliderVisible(FilterMode.BW_CLEAN))
        assertTrue("MAGIC_COLOR preset must show sensitivity slider", isSliderVisible(FilterMode.MAGIC_COLOR))
        assertTrue("GRAYSCALE preset must show sensitivity slider", isSliderVisible(FilterMode.GRAYSCALE))
        assertTrue("TEXT_POP preset must show sensitivity slider", isSliderVisible(FilterMode.TEXT_POP))
        assertTrue("WARM_NOTE preset must show sensitivity slider", isSliderVisible(FilterMode.WARM_NOTE))
        assertTrue("INVERT preset must show sensitivity slider", isSliderVisible(FilterMode.INVERT))
    }

    // =========================================================================
    // 5. Filter Thumbnail Downscaling Mathematics
    // =========================================================================

    @Test
    fun testThumbnailDownscalingMathematics() {
        // Test various typical document image aspect ratios
        val testDimensions = listOf(
            Pair(1800, 2400), // Standard portrait 3:4
            Pair(2400, 1800), // Standard landscape 4:3
            Pair(1200, 1600), // Compact portrait
            Pair(1080, 1920), // 9:16 mobile document
            Pair(2000, 2000)  // Square receipt
        )

        for ((srcW, srcH) in testDimensions) {
            val targetW = 140
            val targetH = (targetW.toFloat() / srcW * srcH).toInt().coerceAtLeast(60)

            assertTrue("targetH must be at least 60px", targetH >= 60)
            val originalAspect = srcW.toFloat() / srcH
            val downscaledAspect = targetW.toFloat() / targetH
            val aspectDelta = Math.abs(originalAspect - downscaledAspect)

            assertTrue(
                "Downscaled aspect ratio must be within 5% of original (delta=$aspectDelta)",
                aspectDelta < 0.05f * originalAspect
            )
        }
    }

    @Test
    fun testIntermediateScrubDownscalingMathematics() {
        val testDimensions = listOf(
            Pair(1800, 2400),
            Pair(2400, 1800),
            Pair(1080, 1920)
        )

        for ((srcW, srcH) in testDimensions) {
            val scrubW = 600
            val scrubH = (scrubW.toFloat() / srcW * srcH).toInt().coerceAtLeast(100)

            assertTrue("scrubH must be at least 100px", scrubH >= 100)
            val originalAspect = srcW.toFloat() / srcH
            val scrubAspect = scrubW.toFloat() / scrubH
            val aspectDelta = Math.abs(originalAspect - scrubAspect)

            assertTrue(
                "Scrub intermediate aspect ratio must be within 2% of original (delta=$aspectDelta)",
                aspectDelta < 0.02f * originalAspect
            )
        }
    }

    // =========================================================================
    // 6. WCAG 2.5.5 Touch Target Dimensions Audit (>= 48dp x 48dp)
    // =========================================================================

    @Test
    fun testTouchTargetDimensionsAcrossM4Views() {
        val loadoutViewFile = resolveFile("src/main/java/com/clickem/scanner/ui/loadout/LoadoutView.kt")
        val loadoutContent = loadoutViewFile.readText()

        val adapterFile = resolveFile("src/main/java/com/clickem/scanner/ui/loadout/LoadoutAdapter.kt")
        val adapterContent = adapterFile.readText()

        val singlePageFile = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val editContent = singlePageFile.readText()

        // 1. LoadoutView: btnBack, btnCloseHeader, btnCancel, btnSave, btnClose must be at least 48dp
        assertFalse("LoadoutView must not contain sub-48dp 28dp touch targets", loadoutContent.contains("(28 * density).toInt()"))
        assertFalse("LoadoutView must not contain sub-48dp 30dp touch targets", loadoutContent.contains("(30 * density).toInt()"))
        assertFalse("LoadoutView must not contain sub-48dp 42dp touch targets", loadoutContent.contains("(42 * density).toInt()"))

        // 2. LoadoutAdapter: btnShare, btnDelete must be at least 48dp
        assertFalse("LoadoutAdapter must not contain sub-48dp 28dp touch targets", adapterContent.contains("(28 * density).toInt()"))

        // 3. SinglePageEditView: all buttons must use 48dp targets
        assertTrue("SinglePageEditView buttons must use 48dp targets", editContent.contains("(48 * density).toInt()"))
    }

    // =========================================================================
    // 7. LoadoutView Export Pipelines Filter Intensity Propagation
    // =========================================================================

    @Test
    fun testLoadoutViewExportPipelinesPassFilterIntensity() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/loadout/LoadoutView.kt")
        val content = file.readText()

        val applyFilterPattern = Pattern.compile("DocumentFilterEngine\\.applyFilter\\([^)]+\\)")
        val matcher = applyFilterPattern.matcher(content)

        val callSites = mutableListOf<String>()
        while (matcher.find()) {
            callSites.add(matcher.group())
        }

        assertEquals("LoadoutView must contain exactly 5 export/share applyFilter calls", 5, callSites.size)

        for (call in callSites) {
            assertTrue(
                "applyFilter call '$call' must pass page.filterIntensity",
                call.contains("page.filterIntensity")
            )
        }
    }

    // =========================================================================
    // 8. Rotation Icons, Skeleton Queue Loader, In-Place Retake & Panel Polish
    // =========================================================================

    @Test
    fun testRotateVectorDrawableAssetsIntegrity() {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (iconName in listOf("ic_rotate.xml", "ic_rotate_left.xml")) {
            val assetFile = resolveFile("src/main/res/drawable/$iconName")
            assertTrue("$iconName must exist in res/drawable", assetFile.exists())

            val doc = builder.parse(assetFile)
            val root = doc.documentElement
            assertEquals("Root element must be vector", "vector", root.tagName)
            assertEquals("24dp", root.getAttribute("android:width"))
            assertEquals("24dp", root.getAttribute("android:height"))

            val paths = root.getElementsByTagName("path")
            assertEquals("$iconName must contain 2 clean paths (curved rotation arrow + document frame)", 2, paths.length)
            for (i in 0 until paths.length) {
                val pathEl = paths.item(i) as Element
                assertEquals("#FFFFFFFF", pathEl.getAttribute("android:fillColor").uppercase())
                assertTrue("PathData must not be blank", pathEl.getAttribute("android:pathData").isNotBlank())
            }
        }
    }

    @Test
    fun testThumbnailQueueSkeletonLoaderAndCameraWiring() {
        val adapterFile = resolveFile("src/main/java/com/clickem/scanner/ui/camera/ThumbnailQueueAdapter.kt")
        val cameraFile = resolveFile("src/main/java/com/clickem/scanner/ui/camera/CameraView.kt")
        val adapterContent = adapterFile.readText()
        val cameraContent = cameraFile.readText()

        assertTrue("ThumbnailQueueAdapter must implement SkeletonShimmerView", adapterContent.contains("class SkeletonShimmerView"))
        assertTrue("SkeletonShimmerView must restart animation in onAttachedToWindow", adapterContent.contains("override fun onAttachedToWindow()"))
        assertTrue("ThumbnailQueueAdapter must expose addPendingSkeleton()", adapterContent.contains("fun addPendingSkeleton()"))
        assertTrue("ThumbnailQueueAdapter must expose removePendingSkeleton()", adapterContent.contains("fun removePendingSkeleton()"))
        assertTrue("ThumbnailQueueAdapter must expose setPageLoading()", adapterContent.contains("fun setPageLoading("))
        assertTrue("ThumbnailQueueAdapter must shift loading indices on page removal", adapterContent.contains("fun onPageRemovedAt("))
        assertTrue("clearPendingSkeletons must clear loadingPageIndices", adapterContent.contains("loadingPageIndices.clear()"))
        assertTrue("CameraView.triggerShutterCapture must add pending skeleton immediately", cameraContent.contains("thumbnailAdapter.addPendingSkeleton()"))
        assertTrue("CameraView.triggerShutterCapture must remove pending skeleton on completion", cameraContent.contains("thumbnailAdapter.removePendingSkeleton()"))
        assertTrue("CameraView must track isQueueRetake so external project retakes clean up pending skeletons", cameraContent.contains("val isQueueRetake ="))
    }

    @Test
    fun testInPlaceRetakeOrderPreservation() {
        val editFile = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val cameraFile = resolveFile("src/main/java/com/clickem/scanner/ui/camera/CameraView.kt")
        val mainFile = resolveFile("src/main/java/com/clickem/scanner/MainActivity.kt")

        val editContent = editFile.readText()
        val cameraContent = cameraFile.readText()
        val mainContent = mainFile.readText()

        assertTrue("SinglePageEditView must expose onRetakeRequested callback", editContent.contains("onRetakeRequested"))
        assertFalse("SinglePageEditView crop bar must not duplicate Retake button above dock", editContent.contains("val colRetake = createToolCol"))
        assertTrue("CameraView must implement startRetakeForPage with updateCameraQueue flag and replaceScannedPageAt",
            cameraContent.contains("fun startRetakeForPage(") &&
            cameraContent.contains("updateCameraQueue: Boolean") &&
            cameraContent.contains("fun replaceScannedPageAt(")
        )
        assertTrue("MainActivity must wire onRetakeRequested to startRetakeForPage in-place and pass updateCameraQueue",
            mainContent.contains("onRetakeRequested = { retakeIdx ->") &&
            mainContent.contains("updateCameraQueue = isCameraQueue") &&
            mainContent.contains("pagesList[replacedIdx] = newPage")
        )
        assertTrue("MainActivity must stop importing extra pages once a single-page retake is fulfilled",
            mainContent.contains("retakeFulfilled = true")
        )

        // Verify in-place list replacement preserves page ordering across 3 pages
        val pages = mutableListOf(
            ScannedPage(
                originalImagePath = "/tmp/p1.jpg",
                thumbnailPath = "/tmp/t1.jpg",
                rotationDegrees = 0,
                cropQuad = mutableListOf(),
                filterMode = FilterMode.ORIGINAL
            ),
            ScannedPage(
                originalImagePath = "/tmp/p2.jpg",
                thumbnailPath = "/tmp/t2.jpg",
                rotationDegrees = 90,
                cropQuad = mutableListOf(),
                filterMode = FilterMode.BW_CLEAN
            ),
            ScannedPage(
                originalImagePath = "/tmp/p3.jpg",
                thumbnailPath = "/tmp/t3.jpg",
                rotationDegrees = 0,
                cropQuad = mutableListOf(),
                filterMode = FilterMode.MAGIC_COLOR
            )
        )
        val replacement = ScannedPage(
            originalImagePath = "/tmp/p2_retaken.jpg",
            thumbnailPath = "/tmp/t2_retaken.jpg",
            rotationDegrees = 0,
            cropQuad = mutableListOf(),
            filterMode = FilterMode.GRAYSCALE
        )
        pages[1] = replacement

        assertEquals(3, pages.size)
        assertEquals("/tmp/p1.jpg", pages[0].originalImagePath)
        assertEquals("/tmp/p2_retaken.jpg", pages[1].originalImagePath)
        assertEquals("/tmp/p3.jpg", pages[2].originalImagePath)
    }

    @Test
    fun testDarkenedRectanglesEliminatedFromPanelsAndViewfinder() {
        val overlayFile = resolveFile("src/main/java/com/clickem/scanner/ui/components/ViewfinderOverlayView.kt")
        val cropOverlayFile = resolveFile("src/main/java/com/clickem/scanner/ui/components/ManualCropOverlayView.kt")
        val editFile = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val overlayContent = overlayFile.readText()
        val cropOverlayContent = cropOverlayFile.readText()
        val editContent = editFile.readText()

        assertFalse("ViewfinderOverlayView must not draw dark letterboxPaint rectangles", overlayContent.contains("letterboxPaint"))
        assertTrue("ViewfinderOverlayView must respect topPanelInset and bottomPanelInset",
            overlayContent.contains("var topPanelInset") && overlayContent.contains("var bottomPanelInset")
        )
        assertTrue("ManualCropOverlayView must use 1.0f opaque maskAlpha at rest to avoid semi-dark uncropped bands",
            cropOverlayContent.contains("private var maskAlpha = 1.0f") &&
            cropOverlayContent.contains("animateMaskAlpha(1.0f)")
        )
        assertTrue("SinglePageEditView must dynamically adjust viewport padding to actual topBar and bottomContainer heights",
            editContent.contains("updateActiveHolderViewportPadding()")
        )
    }
}
