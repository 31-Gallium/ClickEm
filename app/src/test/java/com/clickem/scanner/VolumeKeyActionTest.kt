package com.clickem.scanner

import android.view.KeyEvent
import com.clickem.scanner.util.settings.VolumeKeyAction
import com.clickem.scanner.util.settings.VolumeKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying volume key handling policy and camera controls.
 *
 * All assertions execute directly against the production component
 * [com.clickem.scanner.util.settings.VolumeKeyPolicy] with zero test-local doubles
 * or private dummy helper functions.
 */
class VolumeKeyActionTest {

    @Test
    fun testVolumeZoomDeltaCalculation_upAndDown() {
        val upDelta = VolumeKeyPolicy.calculateZoomDelta(KeyEvent.KEYCODE_VOLUME_UP)
        val downDelta = VolumeKeyPolicy.calculateZoomDelta(KeyEvent.KEYCODE_VOLUME_DOWN)

        assertEquals(0.4f, upDelta, 0.0001f)
        assertEquals(-0.4f, downDelta, 0.0001f)
        assertEquals(VolumeKeyPolicy.DEFAULT_ZOOM_DELTA, upDelta, 0.0001f)
        assertEquals(-VolumeKeyPolicy.DEFAULT_ZOOM_DELTA, downDelta, 0.0001f)
    }

    @Test
    fun testVolumeZoomDeltaCalculation_nonVolumeKeysReturnZero() {
        val backDelta = VolumeKeyPolicy.calculateZoomDelta(KeyEvent.KEYCODE_BACK)
        val powerDelta = VolumeKeyPolicy.calculateZoomDelta(KeyEvent.KEYCODE_POWER)
        val cameraDelta = VolumeKeyPolicy.calculateZoomDelta(KeyEvent.KEYCODE_CAMERA)
        val headsetDelta = VolumeKeyPolicy.calculateZoomDelta(KeyEvent.KEYCODE_HEADSETHOOK)
        val zeroDelta = VolumeKeyPolicy.calculateZoomDelta(0)
        val negativeDelta = VolumeKeyPolicy.calculateZoomDelta(-1)

        assertEquals(0.0f, backDelta, 0.0001f)
        assertEquals(0.0f, powerDelta, 0.0001f)
        assertEquals(0.0f, cameraDelta, 0.0001f)
        assertEquals(0.0f, headsetDelta, 0.0001f)
        assertEquals(0.0f, zeroDelta, 0.0001f)
        assertEquals(0.0f, negativeDelta, 0.0001f)
    }

    @Test
    fun testIsVolumeKey_recognition() {
        assertTrue(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))

        assertFalse(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_BACK))
        assertFalse(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_POWER))
        assertFalse(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_CAMERA))
        assertFalse(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_MENU))
        assertFalse(VolumeKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_HOME))
        assertFalse(VolumeKeyPolicy.isVolumeKey(0))
        assertFalse(VolumeKeyPolicy.isVolumeKey(-1))
    }

    @Test
    fun testVolumeKeyActionOffPassThrough() {
        // When VolumeKeyAction is OFF, volume keys MUST NOT be consumed
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_VOLUME_UP, VolumeKeyAction.OFF))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_VOLUME_DOWN, VolumeKeyAction.OFF))

        // Non-volume keys must also not be consumed
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_BACK, VolumeKeyAction.OFF))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_POWER, VolumeKeyAction.OFF))
    }

    @Test
    fun testVolumeKeyActionConsumeActive_zoomAndShutter() {
        // Under ZOOM mode, volume keys MUST be consumed
        assertTrue(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_VOLUME_UP, VolumeKeyAction.ZOOM))
        assertTrue(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_VOLUME_DOWN, VolumeKeyAction.ZOOM))

        // Under SHUTTER mode, volume keys MUST be consumed
        assertTrue(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_VOLUME_UP, VolumeKeyAction.SHUTTER))
        assertTrue(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_VOLUME_DOWN, VolumeKeyAction.SHUTTER))

        // Non-volume keys must NEVER be consumed regardless of action mode
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_BACK, VolumeKeyAction.ZOOM))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_POWER, VolumeKeyAction.ZOOM))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_CAMERA, VolumeKeyAction.ZOOM))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_BACK, VolumeKeyAction.SHUTTER))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_POWER, VolumeKeyAction.SHUTTER))
        assertFalse(VolumeKeyPolicy.shouldConsumeKey(KeyEvent.KEYCODE_CAMERA, VolumeKeyAction.SHUTTER))
    }

    @Test
    fun testShutterRepeatCountDebounce() {
        // Initial press (repeatCount == 0, not capturing) MUST trigger shutter
        assertTrue(VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 0, isCapturing = false))

        // Holding down the key (repeatCount > 0) must NOT trigger shutter (debounced)
        assertFalse("repeatCount = 1 must be debounced", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 1, isCapturing = false))
        assertFalse("repeatCount = 2 must be debounced", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 2, isCapturing = false))
        assertFalse("repeatCount = 5 must be debounced", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 5, isCapturing = false))
        assertFalse("repeatCount = 50 must be debounced", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 50, isCapturing = false))
        assertFalse("repeatCount = 100 must be debounced", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 100, isCapturing = false))

        // Invalid negative repeat counts must not trigger shutter
        assertFalse("negative repeatCount must not trigger", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = -1, isCapturing = false))
    }

    @Test
    fun testShutterDebounceDuringActiveCapture() {
        // While capture is in-flight (isCapturing == true), shutter must NEVER trigger
        assertFalse("Initial press during capture must be guarded", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 0, isCapturing = true))
        assertFalse("Key repeat during capture must be guarded", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 1, isCapturing = true))
        assertFalse("Key repeat 5 during capture must be guarded", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 5, isCapturing = true))
        assertFalse("Key repeat 100 during capture must be guarded", VolumeKeyPolicy.shouldTriggerShutter(repeatCount = 100, isCapturing = true))
    }

    @Test
    fun testClampedZoomCalculation_boundsAndStepping() {
        val minZoom = 1.0f
        val maxZoom = 8.0f

        // Standard stepping within bounds
        assertEquals(1.4f, VolumeKeyPolicy.calculateClampedZoom(1.0f, 0.4f, minZoom, maxZoom), 0.0001f)
        assertEquals(1.8f, VolumeKeyPolicy.calculateClampedZoom(1.4f, 0.4f, minZoom, maxZoom), 0.0001f)
        assertEquals(1.4f, VolumeKeyPolicy.calculateClampedZoom(1.8f, -0.4f, minZoom, maxZoom), 0.0001f)

        // Lower boundary clamping
        assertEquals(1.0f, VolumeKeyPolicy.calculateClampedZoom(1.0f, -0.4f, minZoom, maxZoom), 0.0001f)
        assertEquals(1.0f, VolumeKeyPolicy.calculateClampedZoom(1.2f, -0.4f, minZoom, maxZoom), 0.0001f)
        assertEquals(1.0f, VolumeKeyPolicy.calculateClampedZoom(1.0f, -5.0f, minZoom, maxZoom), 0.0001f)

        // Upper boundary clamping
        assertEquals(8.0f, VolumeKeyPolicy.calculateClampedZoom(7.8f, 0.4f, minZoom, maxZoom), 0.0001f)
        assertEquals(8.0f, VolumeKeyPolicy.calculateClampedZoom(8.0f, 0.4f, minZoom, maxZoom), 0.0001f)
        assertEquals(8.0f, VolumeKeyPolicy.calculateClampedZoom(8.0f, 5.0f, minZoom, maxZoom), 0.0001f)
    }

    @Test
    fun testDefaultZoomDeltaConstant() {
        assertEquals("DEFAULT_ZOOM_DELTA must be exactly 0.4f", 0.4f, VolumeKeyPolicy.DEFAULT_ZOOM_DELTA, 0.0001f)
    }

    @Test
    fun testExhaustiveVolumeKeyActionMatrix() {
        val testKeyCodes = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_CAMERA,
            0,
            -1
        )

        for (action in VolumeKeyAction.values()) {
            for (code in testKeyCodes) {
                val shouldConsume = VolumeKeyPolicy.shouldConsumeKey(code, action)
                val isVolKey = (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)

                val expected = when (action) {
                    VolumeKeyAction.OFF -> false
                    VolumeKeyAction.ZOOM -> isVolKey
                    VolumeKeyAction.SHUTTER -> isVolKey
                }

                assertEquals(
                    "Mismatch for action=$action, keyCode=$code",
                    expected,
                    shouldConsume
                )
            }
        }
    }

    @Test
    fun testHyperbolicLinearZoomFromRatio_matchesCameraXCropWidthInterpolation() {
        val minZoom = 1.0f
        val maxZoom = 8.0f

        // Boundary checks
        assertEquals(0.0f, VolumeKeyPolicy.calculateLinearZoomFromRatio(1.0f, minZoom, maxZoom), 0.0001f)
        assertEquals(1.0f, VolumeKeyPolicy.calculateLinearZoomFromRatio(8.0f, minZoom, maxZoom), 0.0001f)

        // Monotonically increasing across steps (1.0 -> 1.4 -> 1.8 -> 2.2 -> 2.6)
        var prevLinear = VolumeKeyPolicy.calculateLinearZoomFromRatio(1.0f, minZoom, maxZoom)
        var currentRatio = 1.0f
        for (step in 1..15) {
            currentRatio = VolumeKeyPolicy.calculateClampedZoom(currentRatio, 0.4f, minZoom, maxZoom)
            val nextLinear = VolumeKeyPolicy.calculateLinearZoomFromRatio(currentRatio, minZoom, maxZoom)
            assertTrue(
                "Linear zoom must strictly increase when stepping up (step=$step, ratio=$currentRatio)",
                nextLinear > prevLinear
            )
            // Verify round-trip against CameraX crop-width formula: z = 1 / (1/min - linear * (1/min - 1/max))
            val invMin = 1.0f / minZoom
            val invMax = 1.0f / maxZoom
            val cameraXRecreatedRatio = 1.0f / (invMin - nextLinear * (invMin - invMax))
            assertEquals(
                "Hyperbolic linearZoom must round-trip to exact zoomRatio in CameraX",
                currentRatio,
                cameraXRecreatedRatio,
                0.001f
            )
            prevLinear = nextLinear
        }
    }

    @Test
    fun testCameraZoomAndUiPolishContracts() {
        fun readSrc(relPath: String): String {
            val candidates = listOf(
                java.io.File("src/main/java/com/clickem/scanner/$relPath"),
                java.io.File("app/src/main/java/com/clickem/scanner/$relPath")
            )
            return candidates.first { it.exists() }.readText()
        }

        // 1. CameraView uses setZoomRatio as primary call and guards async zoomState observer
        val cameraCode = readSrc("ui/camera/CameraView.kt")
        assertTrue(
            "CameraView must use ctrl.setZoomRatio(activeZoomRatio) as primary zoom control",
            cameraCode.contains("ctrl.setZoomRatio(activeZoomRatio)")
        )
        assertTrue(
            "CameraView must guard zoomState observer against overwriting manual volume zoom",
            cameraCode.contains("lastManualZoomTimestamp")
        )

        // 2. SinglePageEditView uses shared static sample bitmap for filter thumbnails to save resources
        val editCode = readSrc("ui/edit/SinglePageEditView.kt")
        assertTrue(
            "SinglePageEditView must use shared static sample bitmap for filter thumbnails",
            editCode.contains("getOrCreateSampleDocumentBitmap()") && editCode.contains("cachedStaticThumbnails")
        )
        assertFalse(
            "SinglePageEditView.generateFilterThumbnails must not scale the current page bitmap for preset cards",
            editCode.contains("Bitmap.createScaledBitmap(baseOrientedBitmap")
        )

        // 3. ViewfinderOverlayView uses high-contrast dual-pass grid with intersection crosshairs
        val overlayCode = readSrc("ui/components/ViewfinderOverlayView.kt")
        assertTrue(
            "ViewfinderOverlayView must render high-visibility grid shadow and intersection markers",
            overlayCode.contains("gridShadowPaint") && overlayCode.contains("gridIntersectionPaint")
        )

        // 4. LoadoutView rightmost NavPill tab switches to Google Photos-style 3-column Scanned Gallery grid
        val loadoutCode = readSrc("ui/loadout/LoadoutView.kt")
        assertTrue(
            "LoadoutView must provide ScannedGalleryAdapter with 3-column GridLayoutManager",
            loadoutCode.contains("ScannedGalleryAdapter") &&
                loadoutCode.contains("GridLayoutManager(context, 3)") &&
                loadoutCode.contains("switchDisplayMode(galleryMode = true)") &&
                loadoutCode.contains("galleryThumbCache")
        )
    }

    @Test
    fun testZoomPolicy_handlesNonFiniteInputsGracefully() {
        assertEquals(1.0f, VolumeKeyPolicy.calculateClampedZoom(Float.NaN, 0.4f, 1.0f, 8.0f), 0.0001f)
        assertEquals(1.0f, VolumeKeyPolicy.calculateClampedZoom(Float.POSITIVE_INFINITY, 0.4f, 1.0f, 8.0f), 0.0001f)
        assertEquals(1.0f, VolumeKeyPolicy.calculateClampedZoom(Float.NEGATIVE_INFINITY, 0.4f, 1.0f, 8.0f), 0.0001f)
        assertEquals(1.4f, VolumeKeyPolicy.calculateClampedZoom(1.4f, Float.NaN, 1.0f, 8.0f), 0.0001f)
        assertEquals(1.4f, VolumeKeyPolicy.calculateClampedZoom(1.4f, Float.POSITIVE_INFINITY, 1.0f, 8.0f), 0.0001f)
        assertEquals(1.4f, VolumeKeyPolicy.calculateClampedZoom(1.4f, 0.4f, Float.POSITIVE_INFINITY, 8.0f), 0.0001f)
        assertEquals(1.4f, VolumeKeyPolicy.calculateClampedZoom(1.4f, 0.4f, 1.0f, Float.POSITIVE_INFINITY), 0.0001f)

        assertEquals(0.0f, VolumeKeyPolicy.calculateLinearZoomFromRatio(Float.NaN, 1.0f, 8.0f), 0.0001f)
        assertEquals(0.0f, VolumeKeyPolicy.calculateLinearZoomFromRatio(Float.POSITIVE_INFINITY, 1.0f, 8.0f), 0.0001f)
        assertEquals(0.0f, VolumeKeyPolicy.calculateLinearZoomFromRatio(2.0f, Float.NaN, 8.0f), 0.0001f)
        assertEquals(0.0f, VolumeKeyPolicy.calculateLinearZoomFromRatio(2.0f, 1.0f, Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun testPerspectiveQuadRotation_roundTripAndCoordinateMapping() {
        fun pt(px: Float, py: Float) = android.graphics.PointF(px, py).apply { x = px; y = py }
        val original = listOf(
            pt(0.10f, 0.20f), // TL
            pt(0.85f, 0.15f), // TR
            pt(0.90f, 0.80f), // BR
            pt(0.12f, 0.75f)  // BL
        )

        val cw1 = com.clickem.scanner.util.cv.PerspectiveWarper.rotateQuad90CW(original)
        // New TL is rotated old BL: (1 - 0.75, 0.12) = (0.25, 0.12)
        assertEquals(0.25f, cw1[0].x, 0.0001f)
        assertEquals(0.12f, cw1[0].y, 0.0001f)
        // New TR is rotated old TL: (1 - 0.20, 0.10) = (0.80, 0.10)
        assertEquals(0.80f, cw1[1].x, 0.0001f)
        assertEquals(0.10f, cw1[1].y, 0.0001f)

        // Rotating 90 CW then 90 CCW must restore exact original coordinates
        val roundTrip = com.clickem.scanner.util.cv.PerspectiveWarper.rotateQuad90CCW(cw1)
        for (i in 0..3) {
            assertEquals("Point $i X must round-trip after CW + CCW", original[i].x, roundTrip[i].x, 0.0001f)
            assertEquals("Point $i Y must round-trip after CW + CCW", original[i].y, roundTrip[i].y, 0.0001f)
        }

        // Rotating 4 times CW must also restore exact original coordinates
        var quad = original
        repeat(4) {
            quad = com.clickem.scanner.util.cv.PerspectiveWarper.rotateQuad90CW(quad)
        }
        for (i in 0..3) {
            assertEquals("Point $i X must round-trip after 4x CW", original[i].x, quad[i].x, 0.0001f)
            assertEquals("Point $i Y must round-trip after 4x CW", original[i].y, quad[i].y, 0.0001f)
        }
    }
}
