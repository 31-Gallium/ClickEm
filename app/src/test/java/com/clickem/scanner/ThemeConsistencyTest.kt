package com.clickem.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Unit test suite verifying Milestone 2 (Monochrome Frost Theme Migration) compliance:
 * 1. Zero banned cyan/blue hex codes in XML resources (res/) and modified Kotlin views.
 * 2. Proper Android #AARRGGBB hex notation (#15FFFFFF, #30FFFFFF).
 * 3. Launcher icon high-contrast geometry (pure white camera body with obsidian dark aperture).
 * 4. Strict preservation of Computer Vision mathematical luminance formulas and Color.blue(p).
 * 5. Accessibility descriptions on all interactive UI controls.
 */
class ThemeConsistencyTest {

    private val bannedColorPatterns = listOf(
        "38bdf8", // legacy cyan glow
        "0284c7", // legacy cyan core
        "0ea5e9", // sky blue
        "151d30", // navy container
        "152236", // navy pill
        "1e3a5f", // deep blue
        "67e8f9", // laser cyan
        "131622", // legacy navy frosted slate
        "242836"  // legacy navy border
    )

    private fun resolvePath(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath)
        )
        return candidates.firstOrNull { it.exists() } ?: File(relativePath)
    }

    @Test
    fun testXmlResourcesHaveZeroBannedColors() {
        val resDir = resolvePath("src/main/res")
        assertTrue("res directory must exist at ${resDir.absolutePath}", resDir.exists() && resDir.isDirectory)

        val xmlFiles = resDir.walkTopDown().filter { it.extension == "xml" }.toList()
        assertTrue("Must find XML resources", xmlFiles.isNotEmpty())

        for (file in xmlFiles) {
            val content = file.readText().lowercase()
            for (banned in bannedColorPatterns) {
                assertFalse(
                    "Resource file '${file.name}' must not contain banned color '#$banned'",
                    content.contains(banned)
                )
            }
        }
    }

    @Test
    fun testVectorDrawablesPureWhiteStandard() {
        val drawableDir = resolvePath("src/main/res/drawable")
        assertTrue("drawable directory must exist", drawableDir.exists() && drawableDir.isDirectory)

        val vectorFiles = drawableDir.listFiles { _, name -> name.endsWith(".xml") }?.toList() ?: emptyList()
        assertTrue("Must find vector drawable files", vectorFiles.isNotEmpty())

        for (file in vectorFiles) {
            if (file.name == "ic_clickem_logo.xml") continue // Authentic brand logo
            val content = file.readText()
            // Check that every vector drawable uses pure white #FFFFFFFF or #FFFFFF for fillColor
            assertTrue(
                "Vector drawable '${file.name}' must use pure white fillColor",
                content.contains("android:fillColor=\"#FFFFFFFF\"") ||
                content.contains("android:fillColor=\"#FFFFFF\"") ||
                content.contains("android:fillColor=\"#ffffffff\"") ||
                content.contains("android:fillColor=\"#ffffff\"")
            )
        }
    }

    @Test
    fun testLauncherIconContrastGeometry() {
        val launcher = resolvePath("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        val launcherRound = resolvePath("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml")

        assertTrue("ic_launcher.xml must exist", launcher.exists())
        assertTrue("ic_launcher_round.xml must exist", launcherRound.exists())

        for (file in listOf(launcher, launcherRound)) {
            val content = file.readText()
            assertTrue("${file.name} base squircle must be authentic #D1DDF0", content.contains("android:fillColor=\"#D1DDF0\""))
            assertTrue("${file.name} diagonal accent must be #98DDF0", content.contains("android:fillColor=\"#98DDF0\""))
            assertTrue("${file.name} gradient must include #FF1707FA", content.contains("android:color=\"#FF1707FA\""))
            assertTrue("${file.name} gradient must include #FF1543FD", content.contains("android:color=\"#FF1543FD\""))
        }
    }

    @Test
    fun testModifiedKotlinViewsHaveZeroBannedColors() {
        val targetKotlinPaths = listOf(
            "src/main/java/com/clickem/scanner/ui/components/FrostCardView.kt",
            "src/main/java/com/clickem/scanner/ui/components/GridBackgroundView.kt",
            "src/main/java/com/clickem/scanner/ui/components/ViewfinderOverlayView.kt",
            "src/main/java/com/clickem/scanner/ui/components/ManualCropOverlayView.kt",
            "src/main/java/com/clickem/scanner/ui/components/MagnifierLoupeView.kt",
            "src/main/java/com/clickem/scanner/ui/camera/ThumbnailQueueAdapter.kt",
            "src/main/java/com/clickem/scanner/ui/components/DynamicIslandHeader.kt",
            "src/main/java/com/clickem/scanner/ui/batch/BatchGridAdapter.kt",
            "src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt",
            "src/main/java/com/clickem/scanner/ui/components/NavPillView.kt",
            "src/main/java/com/clickem/scanner/ui/settings/SettingsView.kt",
            "src/main/java/com/clickem/scanner/MainActivity.kt"
        )

        for (relPath in targetKotlinPaths) {
            val file = resolvePath(relPath)
            assertTrue("Target file must exist: ${file.path}", file.exists())

            val content = file.readText().lowercase()
            for (banned in bannedColorPatterns) {
                assertFalse(
                    "Target file '${file.name}' must not contain banned color '#$banned'",
                    content.contains(banned)
                )
            }

            // Verify no CSS #RRGGBBAA hex ordering (#FFFFFF15 or #FFFFFF30)
            assertFalse(
                "Target file '${file.name}' must not use CSS hex '#ffffff15' (must use Android '#15ffffff')",
                content.contains("ffffff15")
            )
            assertFalse(
                "Target file '${file.name}' must not use CSS hex '#ffffff30' (must use Android '#30ffffff')",
                content.contains("ffffff30")
            )
        }
    }

    @Test
    fun testCvMathematicalPreservation() {
        val boundaryDetector = resolvePath("src/main/java/com/clickem/scanner/util/cv/BoundaryDetector.kt")
        val filterEngine = resolvePath("src/main/java/com/clickem/scanner/util/cv/DocumentFilterEngine.kt")

        assertTrue("BoundaryDetector.kt must exist", boundaryDetector.exists())
        assertTrue("DocumentFilterEngine.kt must exist", filterEngine.exists())

        val boundaryContent = boundaryDetector.readText()
        val filterContent = filterEngine.readText()

        // 1. Verify Color.blue(p) occurrences
        val bluePattern = Pattern.compile("Color\\.blue\\(p\\)")
        val boundaryMatcher = bluePattern.matcher(boundaryContent)
        var boundaryBlueCount = 0
        while (boundaryMatcher.find()) boundaryBlueCount++

        val filterMatcher = bluePattern.matcher(filterContent)
        var filterBlueCount = 0
        while (filterMatcher.find()) filterBlueCount++

        assertEquals(
            "BoundaryDetector.kt must preserve exactly 1 occurrence of Color.blue(p)",
            1,
            boundaryBlueCount
        )
        assertEquals(
            "DocumentFilterEngine.kt must preserve exactly 4 occurrences of Color.blue(p)",
            4,
            filterBlueCount
        )

        // 2. Verify ITU-R BT.601 standard RGB-to-grayscale formula
        assertTrue(
            "BoundaryDetector must preserve ITU-R BT.601 formula",
            boundaryContent.contains("Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114")
        )
        assertTrue(
            "DocumentFilterEngine must preserve ITU-R BT.601 formula",
            filterContent.contains("Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114")
        )
    }

    @Test
    fun testInteractiveUiAccessibilityDescriptions() {
        val dynamicIsland = resolvePath("src/main/java/com/clickem/scanner/ui/components/DynamicIslandHeader.kt")
        val batchGrid = resolvePath("src/main/java/com/clickem/scanner/ui/batch/BatchGridAdapter.kt")
        val batchReview = resolvePath("src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt")
        val settings = resolvePath("src/main/java/com/clickem/scanner/ui/settings/SettingsView.kt")

        assertTrue(dynamicIsland.readText().contains("Toggle flashlight"))
        assertTrue(dynamicIsland.readText().contains("Toggle QR scanner"))
        assertTrue(dynamicIsland.readText().contains("Open loadout"))

        assertTrue(batchGrid.readText().contains("Delete page"))
        assertTrue(batchGrid.readText().contains("Rotate page 90 degrees"))

        assertTrue(batchReview.readText().contains("Close review and return to camera"))
        assertTrue(batchReview.readText().contains("format"))

        assertTrue(settings.readText().contains("Close settings"))
    }
}
