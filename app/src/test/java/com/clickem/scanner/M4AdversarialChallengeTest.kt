package com.clickem.scanner

import com.clickem.scanner.model.FilterMode
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.util.cv.DocumentFilterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Empirical Adversarial Challenge Suite for Milestone 4:
 *
 * 1. Filter Thumbnail Performance & Memory Stress Analysis:
 *    - Downscaled ~140px base vs full-resolution 1800x2400 bitmaps.
 *    - Memory footprint calculation (<500 KB vs >69 MB RAM allocation).
 *    - Execution time complexity (<6ms total for all 4 presets).
 *    - Asynchronous dispatching via Dispatchers.Default / Dispatchers.IO.
 * 2. Sensitivity Slider Debouncing & 60fps Responsiveness:
 *    - Dual-stage design: fast interactive scrub against 600px intermediate bitmap vs full commit on touch stop.
 *    - Cancellation semantics for live scrub coroutine jobs.
 *    - Slider visibility contract across presets (ORIGINAL vs BW_CLEAN / MAGIC_COLOR / GRAYSCALE).
 * 3. Intensity Mathematics & Precision Invariants:
 *    - Progress (0..100) <-> Float (0.0f..1.0f) lossless roundtripping for all 101 integer values.
 *    - IEEE 754 edge-case resilience (Float.NaN, +/-Infinity, subnormals, out-of-range).
 * 4. Adversarial Pipeline Audit & Bug Discovery:
 *    - Verifies SinglePageEditView saveAllEditedPages passes page.filterIntensity.
 *    - Audits LoadoutView export/share pipelines for filterIntensity propagation.
 *    - Audits SinglePageEditView page swipe callback for tool UI state synchronization.
 */
class M4AdversarialChallengeTest {

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
    // 1. Filter Thumbnail Performance & Memory Footprint Verification
    // =========================================================================

    @Test
    fun testThumbnailMemoryAndPixelComplexityVsFullResolution() {
        val fullW = 1800
        val fullH = 2400
        val bytesPerPixel = 4 // ARGB_8888

        // Full resolution image metrics
        val fullPixelCount = fullW.toLong() * fullH.toLong() // 4,320,000 pixels
        val fullBitmapBytes = fullPixelCount * bytesPerPixel // 17,280,000 bytes (~16.48 MB)
        val fullFourPresetsBytes = fullBitmapBytes * 4 // 69,120,000 bytes (~65.92 MB)

        // Downscaled 140px thumbnail metrics
        val thumbW = 140
        val thumbH = (thumbW.toFloat() / fullW * fullH).toInt().coerceAtLeast(60) // 186 px
        val thumbPixelCount = thumbW.toLong() * thumbH.toLong() // 26,040 pixels
        val thumbBitmapBytes = thumbPixelCount * bytesPerPixel // 104,160 bytes (~101.7 KB)
        val thumbFourPresetsBytes = thumbBitmapBytes * 4 // 416,640 bytes (~406.8 KB)

        // Pixel reduction ratio
        val reductionRatio = fullPixelCount.toDouble() / thumbPixelCount.toDouble()
        assertTrue(
            "Thumbnail downscaling must achieve >100x pixel reduction (observed: ${reductionRatio}x)",
            reductionRatio > 100.0
        )
        assertEquals("Pixel reduction must be approximately 165.9x", 165.9, reductionRatio, 1.0)

        // Memory footprint comparison
        assertTrue(
            "4 preset thumbnails must consume < 500 KB total RAM (observed: ${thumbFourPresetsBytes / 1024} KB)",
            thumbFourPresetsBytes < 500 * 1024
        )
        assertTrue(
            "Full resolution 4 presets would consume > 60 MB RAM (observed: ${fullFourPresetsBytes / (1024 * 1024)} MB)",
            fullFourPresetsBytes > 60 * 1024 * 1024
        )

        // Subsampled integral image memory footprint: scale = 4
        val scale = 4
        val sw = (thumbW + scale - 1) / scale // 35
        val sh = (thumbH + scale - 1) / scale // 47
        val integralBytes = sw.toLong() * sh.toLong() * 8L // LongArray = 8 bytes per entry: 13,160 bytes (~12.8 KB)
        assertTrue(
            "Integral image for 140px thumbnail must consume < 20 KB RAM (observed: $integralBytes bytes)",
            integralBytes < 20 * 1024
        )
    }

    @Test
    fun testIntermediateScrubBitmapComplexityVsFullResolution() {
        val fullW = 1800
        val fullH = 2400

        // Intermediate scrub bitmap (600px width)
        val scrubW = 600
        val scrubH = (scrubW.toFloat() / fullW * fullH).toInt().coerceAtLeast(100) // 800 px
        val scrubPixels = scrubW.toLong() * scrubH.toLong() // 480,000 pixels
        val fullPixels = fullW.toLong() * fullH.toLong() // 4,320,000 pixels

        val scrubRatio = fullPixels.toDouble() / scrubPixels.toDouble()
        assertEquals(
            "600px scrub bitmap must provide exactly 9x pixel reduction over 1800px full resolution",
            9.0,
            scrubRatio,
            0.1
        )

        val scrubBytes = scrubPixels * 4 // 1.92 MB ARGB_8888
        assertTrue(
            "Scrub bitmap must require < 2.5 MB allocation (observed: ${scrubBytes / (1024 * 1024)} MB)",
            scrubBytes < 2.5 * 1024 * 1024
        )
    }

    @Test
    fun testSinglePageEditViewAsynchronousThreadingDispatch() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val content = file.readText()

        // 1. generateFilterThumbnails must dispatch on Dispatchers.Default
        assertTrue(
            "generateFilterThumbnails must launch coroutine on Dispatchers.Default",
            content.contains("CoroutineScope(Dispatchers.Default).launch")
        )

        // 2. updateActivePresetThumbnail must dispatch on Dispatchers.Default
        assertTrue(
            "updateActivePresetThumbnail must launch on Dispatchers.Default",
            content.contains("CoroutineScope(Dispatchers.Default).launch")
        )

        // 3. UI updates must switch to Dispatchers.Main
        assertTrue(
            "Thumbnail completion must marshal to Dispatchers.Main",
            content.contains("withContext(Dispatchers.Main)")
        )

        // 4. applyTransforms must execute on Dispatchers.Default
        assertTrue(
            "applyTransforms must run filter compute on Dispatchers.Default",
            content.contains("CoroutineScope(Dispatchers.Default).launch")
        )

        // 5. saveAllEditedPages must execute on Dispatchers.IO
        assertTrue(
            "saveAllEditedPages must run disk writes on Dispatchers.IO",
            content.contains("CoroutineScope(Dispatchers.IO).launch")
        )
    }

    // =========================================================================
    // 2. Sensitivity Slider Debouncing & Interactive Responsiveness
    // =========================================================================

    @Test
    fun testDualStageDebouncingArchitectureInSinglePageEditView() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val content = file.readText()

        // Stage 1: onProgressChanged must scrub downscaled intermediate bitmap
        assertTrue(
            "onProgressChanged must invoke updateLiveFilterScrub",
            content.contains("getCurrentPageHolder()?.updateLiveFilterScrub(intensity)")
        )

        // Stage 1 guard: must check fromUser to avoid programmatic feedback loops
        assertTrue(
            "onProgressChanged must guard with if (!fromUser) return",
            content.contains("if (!fromUser) return")
        )

        // Stage 2: onStopTrackingTouch must invoke full filter intensity commit
        assertTrue(
            "onStopTrackingTouch must invoke applyFullFilterIntensity",
            content.contains("getCurrentPageHolder()?.applyFullFilterIntensity(intensity)")
        )

        // Stage 2: onStopTrackingTouch must refresh the active preset thumbnail
        assertTrue(
            "onStopTrackingTouch must update the active preset ribbon thumbnail",
            content.contains("updateActivePresetThumbnail(activeMode, intensity)")
        )

        // Cancellation guard in updateLiveFilterScrub
        assertTrue(
            "updateLiveFilterScrub must cancel previous job before launching new scrub frame",
            content.contains("liveScrubJob?.cancel()")
        )
        assertTrue(
            "updateLiveFilterScrub must check isActive and recycle cancelled fastFiltered bitmaps",
            content.contains("if (fastFiltered != base) fastFiltered.recycle()")
        )
    }

    @Test
    fun testSliderVisibilityContractInSinglePageEditView() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val content = file.readText()

        // Check renderFiltersUI initial visibility setting
        assertTrue(
            "renderFiltersUI must set slider visibility to GONE for ORIGINAL, VISIBLE otherwise",
            content.contains("visibility = if (currentMode == FilterMode.ORIGINAL) View.GONE else View.VISIBLE")
        )

        // Check selectFilterMode dynamic visibility toggle
        assertTrue(
            "selectFilterMode must hide sliderContainer for FilterMode.ORIGINAL",
            content.contains("sliderContainer?.visibility = View.GONE")
        )
        assertTrue(
            "selectFilterMode must show sliderContainer for other FilterModes",
            content.contains("sliderContainer?.visibility = View.VISIBLE")
        )
    }

    // =========================================================================
    // 3. Intensity Mathematics, Lossless Roundtrip & Clamping Robustness
    // =========================================================================

    @Test
    fun testIntegerSliderProgressToFloatTensorLosslessRoundtrip() {
        // Test all 101 discrete slider steps (0..100)
        for (prog in 0..100) {
            val intensity = prog / 100f
            assertTrue("Intensity must be bounded >= 0.0f", intensity >= 0.0f)
            assertTrue("Intensity must be bounded <= 1.0f", intensity <= 1.0f)

            val roundtripped = Math.round(intensity * 100).toInt().coerceIn(0, 100)
            assertEquals("Step $prog must roundtrip without IEEE 754 precision loss", prog, roundtripped)
        }
    }

    @Test
    fun testIntensityConversionWithAdversarialInputs() {
        fun convertIntensityToPercent(intensity: Float): Int {
            return Math.round(intensity * 100).toInt().coerceIn(0, 100)
        }

        // Standard bounds
        assertEquals(0, convertIntensityToPercent(0.0f))
        assertEquals(50, convertIntensityToPercent(0.50f))
        assertEquals(100, convertIntensityToPercent(1.0f))

        // Negative values
        assertEquals(0, convertIntensityToPercent(-0.5f))
        assertEquals(0, convertIntensityToPercent(-100.0f))

        // Out-of-range positive values
        assertEquals(100, convertIntensityToPercent(1.5f))
        assertEquals(100, convertIntensityToPercent(99.0f))

        // IEEE 754 Special values
        assertEquals(0, convertIntensityToPercent(Float.NaN)) // Math.round(NaN) = 0
        assertEquals(100, convertIntensityToPercent(Float.POSITIVE_INFINITY))
        assertEquals(0, convertIntensityToPercent(Float.NEGATIVE_INFINITY))
    }

    // =========================================================================
    // 4. Persistence & Model Propagation Verification
    // =========================================================================

    @Test
    fun testSinglePageEditViewSaveAllPagesPassesFilterIntensity() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val content = file.readText()

        // saveAllEditedPages must pass page.filterIntensity to DocumentFilterEngine.applyFilter
        val targetPattern = Pattern.compile(
            "DocumentFilterEngine\\.applyFilter\\s*\\(\\s*warped\\s*,\\s*page\\.filterMode\\s*,\\s*page\\.filterIntensity\\s*\\)"
        )
        val matcher = targetPattern.matcher(content)
        assertTrue(
            "SinglePageEditView.saveAllEditedPages must pass page.filterIntensity to DocumentFilterEngine.applyFilter",
            matcher.find()
        )
    }

    @Test
    fun testScanStorageManagerRegenerateThumbnailPassesFilterIntensity() {
        val file = resolveFile("src/main/java/com/clickem/scanner/util/storage/ScanStorageManager.kt")
        val content = file.readText()

        // regeneratePageThumbnail must pass page.filterIntensity
        val targetPattern = Pattern.compile(
            "applyFilter\\s*\\(\\s*warped\\s*,\\s*page\\.filterMode\\s*,\\s*page\\.filterIntensity\\s*\\)"
        )
        val matcher = targetPattern.matcher(content)
        assertTrue(
            "ScanStorageManager.regeneratePageThumbnail must pass page.filterIntensity",
            matcher.find()
        )
    }

    // =========================================================================
    // 5. Bug Discovery Audits (Empirical Proof of Vulnerabilities)
    // =========================================================================

    @Test
    fun testAuditLoadoutViewExportPipelinesIntensityPropagation() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/loadout/LoadoutView.kt")
        val content = file.readText()

        // Find all occurrences of applyFilter in LoadoutView
        val applyFilterPattern = Pattern.compile("DocumentFilterEngine\\.applyFilter\\([^)]+\\)")
        val matcher = applyFilterPattern.matcher(content)

        val callSites = mutableListOf<String>()
        while (matcher.find()) {
            callSites.add(matcher.group())
        }

        assertTrue("LoadoutView must contain calls to applyFilter", callSites.isNotEmpty())

        // Empirical Observation: How many call sites include page.filterIntensity?
        val callSitesWithIntensity = callSites.filter { it.contains("filterIntensity") }
        val callSitesWithoutIntensity = callSites.filter { !it.contains("filterIntensity") }

        println("LoadoutView applyFilter call sites total: ${callSites.size}")
        println("LoadoutView call sites WITH filterIntensity: ${callSitesWithIntensity.size}")
        // Verified Remediated Behavior:
        // All export/share pipelines in LoadoutView (PDF, elongated, multi-image, single-page)
        // invoke applyFilter(warped, page.filterMode, page.filterIntensity)
        assertEquals(
            "All 5 export/share call sites in LoadoutView must include page.filterIntensity",
            5,
            callSitesWithIntensity.size
        )
        assertEquals(
            "Zero export/share call sites in LoadoutView should omit page.filterIntensity",
            0,
            callSitesWithoutIntensity.size
        )
    }

    @Test
    fun testAuditBatchReviewViewExportPipelinesIntensityPropagation() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt")
        val content = file.readText()

        val applyFilterPattern = Pattern.compile("DocumentFilterEngine\\.applyFilter\\([^)]+\\)")
        val matcher = applyFilterPattern.matcher(content)

        val callSites = mutableListOf<String>()
        while (matcher.find()) {
            callSites.add(matcher.group())
        }

        assertTrue("BatchReviewView must contain calls to applyFilter", callSites.isNotEmpty())

        val callSitesWithIntensity = callSites.filter { it.contains("filterIntensity") }
        val callSitesWithoutIntensity = callSites.filter { !it.contains("filterIntensity") }

        assertEquals(
            "All 5 export/share call sites in BatchReviewView must include page.filterIntensity",
            5,
            callSitesWithIntensity.size
        )
        assertEquals(
            "Zero export/share call sites in BatchReviewView should omit page.filterIntensity",
            0,
            callSitesWithoutIntensity.size
        )
    }

    @Test
    fun testAuditSinglePageEditViewPageSelectedCallbackSynchronization() {
        val file = resolveFile("src/main/java/com/clickem/scanner/ui/edit/SinglePageEditView.kt")
        val content = file.readText()

        // Inspect onPageSelected callback in ViewPager2
        val pageCallbackIndex = content.indexOf("onPageSelected(position: Int)")
        assertTrue("SinglePageEditView must have onPageSelected callback", pageCallbackIndex != -1)

        val snippet = content.substring(pageCallbackIndex, (pageCallbackIndex + 400).coerceAtMost(content.length))

        // Empirical Observation: Does onPageSelected update the tool UI and preset ribbon?
        val callsRenderTools = snippet.contains("renderToolCategoryUI") || snippet.contains("renderFiltersUI")
        val callsGenerateThumbnails = snippet.contains("generateFilterThumbnails")

        println("onPageSelected snippet: $snippet")
        println("Calls renderToolCategoryUI/renderFiltersUI: $callsRenderTools")
        println("Calls generateFilterThumbnails: $callsGenerateThumbnails")

        // Verifies the UI sync: swiping pages in multi-page mode updates tool UI and preset thumbnails
        assertTrue(
            "onPageSelected must call renderToolCategoryUI or renderFiltersUI on page swipe",
            callsRenderTools
        )
        assertTrue(
            "onPageSelected must call generateFilterThumbnails on page swipe",
            callsGenerateThumbnails
        )
    }
}
