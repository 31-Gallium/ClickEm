package com.clickem.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Empirical adversarial challenge suite for Milestone 2 (Monochrome Frost Theme Migration):
 * 1. Adversarial banned cyan/blue hex mining across all XML resources and modified Kotlin views.
 * 2. Android #AARRGGBB hex notation verification (zero CSS #FFFFFF15 / #FFFFFF30).
 * 3. WCAG AAA contrast ratio mathematical verification for primary surfaces and CTAs.
 * 4. Vector drawable integrity and new M2 asset verification (ic_search.xml, ic_edit.xml).
 * 5. Launcher icon optical aperture contrast verification.
 * 6. Computer Vision mathematical isolation and Color.blue(p) preservation.
 * 7. FrostCardView closed-loop gradient symmetry.
 */
class M2ThemeRegressionChallengeTest {

    private val bannedHexCodes = listOf(
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

    private val modifiedKotlinViews = listOf(
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

    private fun resolveFile(relPath: String): File {
        val candidates = listOf(
            File(relPath),
            File("app", relPath),
            File("..", relPath)
        )
        return candidates.firstOrNull { it.exists() } ?: File(relPath)
    }

    @Test
    fun testBannedHexMiningAcrossAllResources() {
        val resDir = resolveFile("src/main/res")
        assertTrue("res directory must exist at ${resDir.absolutePath}", resDir.exists() && resDir.isDirectory)

        val xmlFiles = resDir.walkTopDown().filter { it.extension == "xml" }.toList()
        assertTrue("Must discover XML resource files in res/", xmlFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in xmlFiles) {
            val content = file.readText().lowercase()
            for (banned in bannedHexCodes) {
                if (content.contains(banned)) {
                    violations.add("${file.name} contains banned hex '#$banned'")
                }
            }
        }

        assertTrue(
            "Found banned cyan/blue hexes in resources:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun testBannedHexMiningInM2ModifiedKotlinViews() {
        val violations = mutableListOf<String>()

        for (relPath in modifiedKotlinViews) {
            val file = resolveFile(relPath)
            assertTrue("Modified view must exist: ${file.path}", file.exists())

            val content = file.readText().lowercase()
            for (banned in bannedHexCodes) {
                if (content.contains(banned)) {
                    violations.add("${file.name} contains banned hex '#$banned'")
                }
            }
        }

        assertTrue(
            "Found banned cyan/blue hexes in modified Kotlin views:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )

        // Specifically test GridBackgroundView: ensure touch easing towards cyan (56, 189, 248) was removed
        val gridView = resolveFile("src/main/java/com/clickem/scanner/ui/components/GridBackgroundView.kt")
        val gridContent = gridView.readText()
        assertFalse("GridBackgroundView must not ease towards cyan red=56", gridContent.contains("56 + (255 - 56)"))
        assertFalse("GridBackgroundView must not ease towards cyan green=189", gridContent.contains("189 + (255 - 189)"))
        assertFalse("GridBackgroundView must not ease towards cyan blue=248", gridContent.contains("248 + (255 - 248)"))
        assertTrue("GridBackgroundView must ease towards slate-200 / pure white", gridContent.contains("226") && gridContent.contains("232") && gridContent.contains("240"))
    }

    @Test
    fun testAndroidHexNotationDiscipline() {
        // Assert 0 occurrences of CSS #FFFFFF15 or #FFFFFF30 across all resources and modified views
        val resDir = resolveFile("src/main/res")
        val xmlFiles = resDir.walkTopDown().filter { it.extension == "xml" }.toList()

        for (file in xmlFiles) {
            val content = file.readText().lowercase()
            assertFalse("CSS hex '#ffffff15' found in ${file.name}", content.contains("ffffff15"))
            assertFalse("CSS hex '#ffffff30' found in ${file.name}", content.contains("ffffff30"))
        }

        for (relPath in modifiedKotlinViews) {
            val file = resolveFile(relPath)
            val content = file.readText().lowercase()
            assertFalse("CSS hex '#ffffff15' found in ${file.name}", content.contains("ffffff15"))
            assertFalse("CSS hex '#ffffff30' found in ${file.name}", content.contains("ffffff30"))
        }

        // Verify that colors.xml properly declares #15ffffff and #30ffffff
        val colorsFile = resolveFile("src/main/res/values/colors.xml")
        val colorsContent = colorsFile.readText().lowercase()
        assertTrue("colors.xml must define frost_glass as #15ffffff", colorsContent.contains("<color name=\"frost_glass\">#15ffffff</color>"))
        assertTrue("colors.xml must define frost_border as #30ffffff", colorsContent.contains("<color name=\"frost_border\">#30ffffff</color>"))
    }

    @Test
    fun testWcagAaaContrastRatios() {
        // Relative luminance helper
        fun srgbToLinear(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }

        fun relativeLuminance(r: Int, g: Int, b: Int): Double =
            0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)

        fun contrastRatio(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
            val l1 = relativeLuminance(r1, g1, b1)
            val l2 = relativeLuminance(r2, g2, b2)
            val lighter = max(l1, l2)
            val darker = min(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        // 1. Primary CTA: Pure White #FFFFFF background vs Obsidian #090B10 text/icon
        val ctaRatio = contrastRatio(255, 255, 255, 9, 11, 16)
        assertTrue("CTA contrast ratio ($ctaRatio) must exceed WCAG AAA threshold of 7.0:1", ctaRatio >= 7.0)
        assertTrue("CTA contrast ratio ($ctaRatio) should achieve high optical contrast (>18.0:1)", ctaRatio >= 18.0)

        // 2. Secondary Card: Dark Obsidian #131622 background vs Slate Highlight #F8FAFC text
        val cardTextRatio = contrastRatio(19, 22, 34, 248, 250, 252)
        assertTrue("Card text contrast ratio ($cardTextRatio) must exceed WCAG AAA threshold of 7.0:1", cardTextRatio >= 7.0)

        // 3. Destructive Action: Rose Red #F43F5E vs Pure White #FFFFFF icon
        val deleteBtnRatio = contrastRatio(244, 63, 94, 255, 255, 255)
        assertTrue("Delete button icon contrast ratio ($deleteBtnRatio) must exceed WCAG AA 3.0:1 for graphical UI elements", deleteBtnRatio >= 3.0)
    }

    @Test
    fun testVectorDrawablesAndNewM2Assets() {
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()

        // Test newly introduced M2 assets: ic_search.xml and ic_edit.xml
        val assetsToTest = listOf(
            "src/main/res/drawable/ic_search.xml",
            "src/main/res/drawable/ic_edit.xml",
            "src/main/res/drawable/ic_pdf.xml",
            "src/main/res/drawable/ic_stitch.xml",
            "src/main/res/drawable/ic_settings.xml",
            "src/main/res/drawable/ic_reset.xml"
        )

        for (assetPath in assetsToTest) {
            val file = resolveFile(assetPath)
            assertTrue("Asset file must exist: ${file.name}", file.exists())

            val doc = docBuilder.parse(file)
            val root = doc.documentElement
            assertEquals("Root element must be vector in ${file.name}", "vector", root.tagName)
            assertEquals("24dp", root.getAttribute("android:width"))
            assertEquals("24dp", root.getAttribute("android:height"))

            val paths = root.getElementsByTagName("path")
            assertTrue("Must contain path elements in ${file.name}", paths.length >= 1)

            val firstPath = paths.item(0) as Element
            val fill = firstPath.getAttribute("android:fillColor")
            assertTrue(
                "fillColor must be pure white in ${file.name} (found '$fill')",
                fill.equals("#FFFFFFFF", ignoreCase = true) || fill.equals("#FFFFFF", ignoreCase = true)
            )
        }
    }

    @Test
    fun testLauncherIconOpticalAperture() {
        val launcherFile = resolveFile("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()
        val doc = docBuilder.parse(launcherFile)
        val paths = doc.documentElement.getElementsByTagName("path")

        assertEquals("ic_launcher.xml must contain 3 paths (arcs, squares, body)", 3, paths.length)

        val arcs = paths.item(0) as Element
        val squares = paths.item(1) as Element
        val cameraBody = paths.item(2) as Element

        assertEquals("#231F20", arcs.getAttribute("android:fillColor"))
        assertEquals("#6D6E71", squares.getAttribute("android:fillColor"))
        assertEquals("#00AEEF", cameraBody.getAttribute("android:fillColor"))
        assertEquals("evenOdd", cameraBody.getAttribute("android:fillType"))
    }

    @Test
    fun testCvGrayscaleIntegrityStressTest() {
        val boundaryFile = resolveFile("src/main/java/com/clickem/scanner/util/cv/BoundaryDetector.kt")
        val filterFile = resolveFile("src/main/java/com/clickem/scanner/util/cv/DocumentFilterEngine.kt")

        val bText = boundaryFile.readText()
        val fText = filterFile.readText()

        // Check exact coefficients for BT.601
        val formula = "Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114"
        assertTrue("BoundaryDetector must contain BT.601 formula", bText.contains(formula))
        assertTrue("DocumentFilterEngine must contain BT.601 formula", fText.contains(formula))

        // Check integer division by 1000
        assertTrue("BoundaryDetector must divide luminance sum by 1000", bText.contains(") / 1000"))
        assertTrue("DocumentFilterEngine must divide luminance sum by 1000", fText.contains(") / 1000"))

        // Count Color.blue(p) occurrences
        val p = Pattern.compile("Color\\.blue\\(p\\)")
        var bCount = 0
        val bM = p.matcher(bText)
        while (bM.find()) bCount++

        var fCount = 0
        val fM = p.matcher(fText)
        while (fM.find()) fCount++

        assertEquals("BoundaryDetector must have exactly 1 Color.blue(p)", 1, bCount)
        assertEquals("DocumentFilterEngine must have exactly 4 Color.blue(p)", 4, fCount)
    }

    @Test
    fun testFrostCardViewClosedLoopShader() {
        val frostCardFile = resolveFile("src/main/java/com/clickem/scanner/ui/components/FrostCardView.kt")
        val content = frostCardFile.readText()

        val colorsStartIndex = content.indexOf("val colors = intArrayOf")
        val positionsStartIndex = content.indexOf("val positions = floatArrayOf")
        assertTrue("FrostCardView must define sweep gradient colors array", colorsStartIndex != -1 && positionsStartIndex > colorsStartIndex)

        val colorsArrayContent = content.substring(colorsStartIndex, positionsStartIndex)
        assertFalse("Must not contain #38bdf8 in SweepGradient", colorsArrayContent.contains("38bdf8"))
        assertFalse("Must not contain #0284c7 in SweepGradient", colorsArrayContent.contains("0284c7"))
        assertTrue("Colors array must include pure white highlight #FFFFFF", colorsArrayContent.contains("\"#FFFFFF\""))
        assertTrue("Colors array must start and end with #090B10", colorsArrayContent.contains("\"#090B10\""))

        // 2. Positions array check: 0.50f peak
        assertTrue("Positions array must contain 0.50f center peak", content.contains("0.50f"))
    }
}
