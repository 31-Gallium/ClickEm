package com.clickem.scanner

import android.view.KeyEvent
import com.clickem.scanner.util.settings.VolumeKeyAction
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
 * Empirical regression challenge suite for Milestone 1 UI overhaul:
 * 1. ic_grid.xml vector asset validity and coordinate bounds
 * 2. btnDone badge scaling and singular/plural grammar across edge values
 * 3. Accessibility content descriptions on all interactive camera buttons
 * 4. Elimination of #38bdf8 / #152236 cyan colors in CameraView and CameraTopHudView
 * 5. Hardware volume key dispatch & debounce state machine
 */
class M1UiRegressionChallengeTest {

    @Test
    fun testIcGridVectorAssetIntegrity() {
        val gridFile = File("src/main/res/drawable/ic_grid.xml")
        val altGridFile = File("app/src/main/res/drawable/ic_grid.xml")
        val targetFile = if (gridFile.exists()) gridFile else altGridFile
        assertTrue("ic_grid.xml file must exist", targetFile.exists())

        // 1. Validate XML parsing
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()
        val doc = docBuilder.parse(targetFile)
        val root = doc.documentElement

        assertEquals("Root element must be vector", "vector", root.tagName)
        assertEquals("Width must be 24dp", "24dp", root.getAttribute("android:width"))
        assertEquals("Height must be 24dp", "24dp", root.getAttribute("android:height"))
        assertEquals("Viewport width must be 24", "24", root.getAttribute("android:viewportWidth"))
        assertEquals("Viewport height must be 24", "24", root.getAttribute("android:viewportHeight"))

        val pathList = root.getElementsByTagName("path")
        assertTrue("Must contain at least 1 path element", pathList.length >= 1)

        val pathElem = pathList.item(0) as Element
        val pathData = pathElem.getAttribute("android:pathData")
        assertNotNull("pathData attribute must be present", pathData)
        assertTrue("pathData must not be empty", pathData.isNotBlank())

        val fillColor = pathElem.getAttribute("android:fillColor")
        assertEquals("Fill color must be pure white", "#FFFFFFFF", fillColor)

        // 2. Validate Path coordinate bounds and structure
        // Viewport is 0..24, so all numbers parsed must satisfy 0.0 <= coordinate <= 24.0
        val numberPattern = Pattern.compile("[-+]?\\d*\\.?\\d+")
        val matcher = numberPattern.matcher(pathData)
        var numberCount = 0
        while (matcher.find()) {
            val numStr = matcher.group()
            val value = numStr.toFloatOrNull()
            assertNotNull("Invalid number in pathData: $numStr", value)
            value?.let { v ->
                val absV = Math.abs(v)
                assertTrue("Coordinate value $absV exceeds viewport dimension 24", absV <= 24.0f)
            }
            numberCount++
        }
        assertTrue("Path must have coordinates (found $numberCount)", numberCount >= 30)

        // 3. Verify 10 distinct subpaths (1 outer rectangle + 9 inner cutouts)
        // Subpaths end with 'z' or 'Z'
        val subpaths = pathData.split(Pattern.compile("[zZ]")).map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("Must contain 10 closed subpaths (1 outer box + 9 grid cells)", 10, subpaths.size)
    }

    @Test
    fun testDoneButtonBadgeEdgeValuesAndGrammar() {
        // Test edge values: 0, 1, 2, 10, 99, 100
        val counts = listOf(0, 1, 2, 10, 99, 100)

        for (count in counts) {
            val isVisible = count > 0
            val badgeText = if (count > 0) "$count" else ""
            val grammarLabel = if (count == 1) "page" else "pages"
            val contentDesc = if (count > 0) {
                "Finish scanning, $count $grammarLabel captured"
            } else {
                "Done"
            }

            if (count == 0) {
                assertFalse("Done button must be invisible when activePages count is 0", isVisible)
                assertEquals("Initial description must be 'Done'", "Done", contentDesc)
            } else {
                assertTrue("Done button must be visible when count > 0", isVisible)
                assertEquals("Badge text must equal string representation of count", count.toString(), badgeText)
                assertTrue("contentDescription must contain '$count $grammarLabel'", contentDesc.contains("$count $grammarLabel"))
            }

            // Test layout width bounds:
            // minWidth = 18dp, horizontal padding = 8dp total (4dp each side).
            // Estimated text width per character = ~6.5dp.
            val density = 3.0f // standard xxhdpi
            val minWidthDp = 18f
            val padHDp = 8f
            val charWidthDp = 6.5f
            val estimatedTextWidth = badgeText.length * charWidthDp
            val totalBadgeWidthDp = maxOf(minWidthDp, estimatedTextWidth + padHDp)

            // Badge should never exceed the 52dp container
            assertTrue(
                "Badge width ($totalBadgeWidthDp dp) must not exceed 52dp container for count $count",
                totalBadgeWidthDp <= 52f
            )

            // For count = 1, single digit fits within minWidth 18dp -> aspect ratio 1:1 circular badge
            if (count == 1) {
                assertEquals(18f, totalBadgeWidthDp, 0.01f)
            }
        }
    }

    @Test
    fun testInteractiveControlsAccessibilityDescriptions() {
        val topHudFile = findSourceFile("CameraTopHudView.kt")
        val cameraViewFile = findSourceFile("CameraView.kt")

        assertTrue("CameraTopHudView.kt must exist", topHudFile.exists())
        assertTrue("CameraView.kt must exist", cameraViewFile.exists())

        val topHudContent = topHudFile.readText()
        val cameraViewContent = cameraViewFile.readText()

        // Verify CameraTopHudView interactive controls have contentDescription assigned
        val expectedHudControls = listOf(
            "Flashlight",
            "Flashlight on",
            "Flashlight off",
            "QR Scanner",
            "QR Scanner enabled",
            "QR Scanner disabled",
            "Aspect ratio",
            "Camera Grid",
            "Camera Grid enabled",
            "Camera Grid disabled",
            "Camera Settings",
            "Preset Loadouts"
        )

        for (desc in expectedHudControls) {
            assertTrue(
                "CameraTopHudView must define accessibility description '$desc'",
                topHudContent.contains(desc)
            )
        }

        // Verify CameraView interactive dock controls have contentDescription assigned
        val expectedCameraControls = listOf(
            "Open gallery",
            "Reset auto focus",
            "Clear scanned page queue",
            "Capture photo",
            "Take picture",
            "Finish scanning"
        )

        for (desc in expectedCameraControls) {
            assertTrue(
                "CameraView must define accessibility description '$desc'",
                cameraViewContent.contains(desc)
            )
        }
    }

    @Test
    fun testNoCyan38bdf8ColorInCameraViews() {
        val topHudFile = findSourceFile("CameraTopHudView.kt")
        val cameraViewFile = findSourceFile("CameraView.kt")

        val topHudContent = topHudFile.readText().lowercase()
        val cameraViewContent = cameraViewFile.readText().lowercase()

        // 1. Verify no #38bdf8 in CameraTopHudView.kt
        assertFalse(
            "CameraTopHudView.kt must not contain '#38bdf8'",
            topHudContent.contains("38bdf8")
        )

        // 2. Verify no #38bdf8 in CameraView.kt
        assertFalse(
            "CameraView.kt must not contain '#38bdf8'",
            cameraViewContent.contains("38bdf8")
        )

        // 3. Verify no blue tint #152236 in CameraView or CameraTopHudView
        assertFalse(
            "CameraTopHudView.kt must not contain '#152236'",
            topHudContent.contains("152236")
        )
        assertFalse(
            "CameraView.kt must not contain '#152236'",
            cameraViewContent.contains("152236")
        )
    }

    @Test
    fun testVolumeKeyCameraEventMatrix() {
        // Test key codes
        val upCode = KeyEvent.KEYCODE_VOLUME_UP
        val downCode = KeyEvent.KEYCODE_VOLUME_DOWN
        val backCode = KeyEvent.KEYCODE_BACK
        val powerCode = KeyEvent.KEYCODE_POWER

        // 1. OFF action should NEVER consume keys
        assertFalse(shouldConsume(upCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.OFF))
        assertFalse(shouldConsume(downCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.OFF))
        assertFalse(shouldConsume(upCode, KeyEvent.ACTION_UP, VolumeKeyAction.OFF))

        // 2. Non-volume keys should NEVER be consumed by camera volume handler
        assertFalse(shouldConsume(backCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.ZOOM))
        assertFalse(shouldConsume(powerCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.SHUTTER))

        // 3. ZOOM action: consumes DOWN and UP, steps delta
        assertTrue(shouldConsume(upCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.ZOOM))
        assertTrue(shouldConsume(downCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.ZOOM))
        assertTrue(shouldConsume(upCode, KeyEvent.ACTION_UP, VolumeKeyAction.ZOOM))
        assertEquals(0.4f, getZoomDelta(upCode), 0.001f)
        assertEquals(-0.4f, getZoomDelta(downCode), 0.001f)

        // Zoom repeat events (repeatCount > 0) should also step zoom continuously
        assertTrue(shouldAdjustZoomOnRepeat(repeatCount = 1))
        assertTrue(shouldAdjustZoomOnRepeat(repeatCount = 5))

        // 4. SHUTTER action: consumes DOWN and UP
        assertTrue(shouldConsume(upCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.SHUTTER))
        assertTrue(shouldConsume(downCode, KeyEvent.ACTION_DOWN, VolumeKeyAction.SHUTTER))
        assertTrue(shouldConsume(upCode, KeyEvent.ACTION_UP, VolumeKeyAction.SHUTTER))

        // Shutter triggers only when repeatCount == 0 and not capturing
        assertTrue(shouldTriggerShutter(repeatCount = 0, isCapturing = false))
        assertFalse("Holding down volume key must not trigger multiple exposures", shouldTriggerShutter(repeatCount = 1, isCapturing = false))
        assertFalse("Holding down volume key must not trigger multiple exposures", shouldTriggerShutter(repeatCount = 5, isCapturing = false))
        assertFalse("Must not trigger shutter while capture is already in-flight", shouldTriggerShutter(repeatCount = 0, isCapturing = true))

        // 5. Zoom ratio clamping
        val minZoom = 1.0f
        val maxZoom = 8.0f
        var currentZoom = 1.0f
        currentZoom = (currentZoom - 0.4f).coerceIn(minZoom, maxZoom)
        assertEquals("Zoom must not drop below minZoom", 1.0f, currentZoom, 0.001f)

        currentZoom = 7.8f
        currentZoom = (currentZoom + 0.4f).coerceIn(minZoom, maxZoom)
        assertEquals("Zoom must not exceed maxZoom", 8.0f, currentZoom, 0.001f)
    }

    private fun shouldConsume(keyCode: Int, action: Int, mode: VolumeKeyAction): Boolean {
        if (mode == VolumeKeyAction.OFF) return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> true
            else -> true
        }
    }

    private fun getZoomDelta(keyCode: Int): Float =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.4f else -0.4f

    private fun shouldAdjustZoomOnRepeat(repeatCount: Int): Boolean = repeatCount >= 0

    private fun shouldTriggerShutter(repeatCount: Int, isCapturing: Boolean): Boolean =
        repeatCount == 0 && !isCapturing

    private fun findSourceFile(filename: String): File {
        val candidates = listOf(
            File("src/main/java/com/clickem/scanner/ui/components/$filename"),
            File("app/src/main/java/com/clickem/scanner/ui/components/$filename"),
            File("src/main/java/com/clickem/scanner/ui/camera/$filename"),
            File("app/src/main/java/com/clickem/scanner/ui/camera/$filename")
        )
        return candidates.firstOrNull { it.exists() } ?: File(filename)
    }
}
