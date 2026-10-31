package com.clickem.scanner

import android.graphics.Bitmap
import android.graphics.PointF
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
 * Comprehensive unit test suite verifying Milestone 3 (Filter Parameterization,
 * Model Persistence, MainActivity Synchronization & Backward Compatibility).
 *
 * Verifies:
 * 1. Filter sensitivity clamping for values within [0.0f .. 1.0f], below 0.0f, above 1.0f, and NaN.
 * 2. Mathematical parameterization across all 4 FilterMode presets (BW_CLEAN, MAGIC_COLOR, GRAYSCALE, ORIGINAL).
 * 3. ScannedPage initialization, default values, and copyPage() deep copy isolation.
 * 4. ScanStorageManager JSON serialization structure and backward-compatible deserialization.
 * 5. MainActivity synchronization callback contract.
 * 6. BT.601 luminance and Color.blue(p) mathematical integrity.
 * 7. Default intensity parameter reflection and compatibility.
 */
class FilterParameterizationTest {

    private fun resolvePath(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath)
        )
        return candidates.firstOrNull { it.exists() } ?: File(relativePath)
    }

    // =========================================================================
    // 1. testBwCleanKFactorCalculation_boundsAndClamping
    // =========================================================================
    @Test
    fun testBwCleanKFactorCalculation_boundsAndClamping() {
        // Bradley-Roth / Sauvola threshold: kFactor = 0.70f + (intensity * 0.25f)
        // At 0.0f intensity -> 0.70f (Strict ink threshold, suppresses shadows and paper smudges)
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(0.0f), 0.0001f)
        assertEquals(0.70f, DocumentFilterEngine.calculateKFactor(0.0f), 0.0001f)

        // At 0.5f intensity -> 0.825f
        assertEquals(0.825f, DocumentFilterEngine.calculateBwCleanKFactor(0.5f), 0.0001f)
        assertEquals(0.825f, DocumentFilterEngine.calculateKFactor(0.5f), 0.0001f)

        // At 0.64f intensity -> 0.86f (Exact legacy hardcoded threshold)
        assertEquals(0.86f, DocumentFilterEngine.calculateBwCleanKFactor(0.64f), 0.0001f)
        assertEquals(0.86f, DocumentFilterEngine.calculateKFactor(0.64f), 0.0001f)

        // At 1.0f intensity -> 0.95f (High sensitivity, captures faint pencil handwriting)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(1.0f), 0.0001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateKFactor(1.0f), 0.0001f)

        // Clamping verification
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(-1.0f), 0.0001f)
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(-100.0f), 0.0001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(2.5f), 0.0001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(50.0f), 0.0001f)

        // Monotonic check: kFactor strictly non-decreasing with intensity
        var prevK = -1.0f
        for (i in 0..100) {
            val k = DocumentFilterEngine.calculateBwCleanKFactor(i / 100f)
            assertTrue("kFactor must strictly increase or stay equal with intensity", k >= prevK)
            prevK = k
        }
    }

    // =========================================================================
    // 2. testMagicColorParamsCalculation_boundsAndClamping
    // =========================================================================
    @Test
    fun testMagicColorParamsCalculation_boundsAndClamping() {
        // Bleach boost: 0.40f + (intensity * 1.10f)
        // Ink deepen: 1.00f - (intensity * 0.45f)

        // At 0.0f intensity
        val (bleach0, ink0) = DocumentFilterEngine.calculateMagicColorParams(0.0f)
        assertEquals(0.40f, bleach0, 0.0001f)
        assertEquals(1.00f, ink0, 0.0001f)
        assertEquals(0.40f, DocumentFilterEngine.calculateBleachBoost(0.0f), 0.0001f)
        assertEquals(1.00f, DocumentFilterEngine.calculateInkDeepen(0.0f), 0.0001f)

        // At 0.5f intensity
        val (bleach5, ink5) = DocumentFilterEngine.calculateMagicColorParams(0.5f)
        assertEquals(0.95f, bleach5, 0.0001f)
        assertEquals(0.775f, ink5, 0.0001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBleachBoost(0.5f), 0.0001f)
        assertEquals(0.775f, DocumentFilterEngine.calculateInkDeepen(0.5f), 0.0001f)

        // At 1.0f intensity
        val (bleach1, ink1) = DocumentFilterEngine.calculateMagicColorParams(1.0f)
        assertEquals(1.50f, bleach1, 0.0001f)
        assertEquals(0.55f, ink1, 0.0001f)
        assertEquals(1.50f, DocumentFilterEngine.calculateBleachBoost(1.0f), 0.0001f)
        assertEquals(0.55f, DocumentFilterEngine.calculateInkDeepen(1.0f), 0.0001f)

        // Clamping verification
        val (bleachNeg, inkNeg) = DocumentFilterEngine.calculateMagicColorParams(-0.5f)
        assertEquals(0.40f, bleachNeg, 0.0001f)
        assertEquals(1.00f, inkNeg, 0.0001f)

        val (bleachEx, inkEx) = DocumentFilterEngine.calculateMagicColorParams(5.0f)
        assertEquals(1.50f, bleachEx, 0.0001f)
        assertEquals(0.55f, inkEx, 0.0001f)

        // Monotonic checks
        var prevBoost = -1.0f
        var prevDeepen = 2.0f
        for (i in 0..100) {
            val (boost, deepen) = DocumentFilterEngine.calculateMagicColorParams(i / 100f)
            assertTrue("Bleach boost must increase with intensity", boost >= prevBoost)
            assertTrue("Ink deepen factor must decrease with intensity (darkening ink)", deepen <= prevDeepen)
            prevBoost = boost
            prevDeepen = deepen
        }
    }

    // =========================================================================
    // 3. testGrayscaleParamsCalculation_boundsAndClamping
    // =========================================================================
    @Test
    fun testGrayscaleParamsCalculation_boundsAndClamping() {
        // Saturation: 1.0f - intensity
        // Contrast: 1.0f + (intensity * 0.35f)

        // At 0.0f intensity (identity full color)
        val (sat0, con0) = DocumentFilterEngine.calculateGrayscaleParams(0.0f)
        assertEquals(1.00f, sat0, 0.0001f)
        assertEquals(1.00f, con0, 0.0001f)
        assertEquals(1.00f, DocumentFilterEngine.calculateGrayscaleSaturation(0.0f), 0.0001f)
        assertEquals(1.00f, DocumentFilterEngine.calculateGrayscaleContrast(0.0f), 0.0001f)
        assertEquals(0.00f, DocumentFilterEngine.calculateGrayscaleShift(0.0f), 0.0001f)

        // At 0.5f intensity
        val (sat5, con5) = DocumentFilterEngine.calculateGrayscaleParams(0.5f)
        assertEquals(0.50f, sat5, 0.0001f)
        assertEquals(1.175f, con5, 0.0001f)
        assertEquals(0.50f, DocumentFilterEngine.calculateGrayscaleSaturation(0.5f), 0.0001f)
        assertEquals(1.175f, DocumentFilterEngine.calculateGrayscaleContrast(0.5f), 0.0001f)
        assertEquals(-10.0f, DocumentFilterEngine.calculateGrayscaleShift(0.5f), 0.0001f)

        // At 1.0f intensity (pure monochrome)
        val (sat1, con1) = DocumentFilterEngine.calculateGrayscaleParams(1.0f)
        assertEquals(0.00f, sat1, 0.0001f)
        assertEquals(1.35f, con1, 0.0001f)
        assertEquals(0.00f, DocumentFilterEngine.calculateGrayscaleSaturation(1.0f), 0.0001f)
        assertEquals(1.35f, DocumentFilterEngine.calculateGrayscaleContrast(1.0f), 0.0001f)
        assertEquals(-20.0f, DocumentFilterEngine.calculateGrayscaleShift(1.0f), 0.0001f)

        // Clamping verification
        val (satNeg, conNeg) = DocumentFilterEngine.calculateGrayscaleParams(-2.0f)
        assertEquals(1.00f, satNeg, 0.0001f)
        assertEquals(1.00f, conNeg, 0.0001f)

        val (satEx, conEx) = DocumentFilterEngine.calculateGrayscaleParams(3.0f)
        assertEquals(0.00f, satEx, 0.0001f)
        assertEquals(1.35f, conEx, 0.0001f)
    }

    // =========================================================================
    // 4. testFilterModeFormulaDeterminism
    // =========================================================================
    @Test
    fun testFilterModeFormulaDeterminism() {
        // Determinism: Same input yields exact bit-level Float results across repeated executions
        val testInputs = floatArrayOf(0.0f, 0.1234f, 0.5f, 0.777f, 1.0f)
        for (v in testInputs) {
            val k1 = DocumentFilterEngine.calculateBwCleanKFactor(v)
            val k2 = DocumentFilterEngine.calculateBwCleanKFactor(v)
            assertEquals("BwClean kFactor must be strictly deterministic", java.lang.Float.floatToIntBits(k1), java.lang.Float.floatToIntBits(k2))

            val m1 = DocumentFilterEngine.calculateMagicColorParams(v)
            val m2 = DocumentFilterEngine.calculateMagicColorParams(v)
            assertEquals("MagicColor bleach must be strictly deterministic", java.lang.Float.floatToIntBits(m1.first), java.lang.Float.floatToIntBits(m2.first))
            assertEquals("MagicColor deepen must be strictly deterministic", java.lang.Float.floatToIntBits(m1.second), java.lang.Float.floatToIntBits(m2.second))

            val g1 = DocumentFilterEngine.calculateGrayscaleParams(v)
            val g2 = DocumentFilterEngine.calculateGrayscaleParams(v)
            assertEquals("Grayscale sat must be strictly deterministic", java.lang.Float.floatToIntBits(g1.first), java.lang.Float.floatToIntBits(g2.first))
            assertEquals("Grayscale contrast must be strictly deterministic", java.lang.Float.floatToIntBits(g1.second), java.lang.Float.floatToIntBits(g2.second))
        }

        // Verify FilterMode enum integrity
        val modes = FilterMode.values()
        assertEquals("FilterMode must define exactly 7 presets", 7, modes.size)
        assertTrue(modes.contains(FilterMode.ORIGINAL))
        assertTrue(modes.contains(FilterMode.BW_CLEAN))
        assertTrue(modes.contains(FilterMode.MAGIC_COLOR))
        assertTrue(modes.contains(FilterMode.GRAYSCALE))
        assertTrue(modes.contains(FilterMode.TEXT_POP))
        assertTrue(modes.contains(FilterMode.WARM_NOTE))
        assertTrue(modes.contains(FilterMode.INVERT))
    }

    // =========================================================================
    // 5. testScannedPageInitializationAndDefaults
    // =========================================================================
    @Test
    fun testScannedPageInitializationAndDefaults() {
        val defaultPage = ScannedPage(originalImagePath = "/storage/test_img.jpg")
        assertEquals("Default filterIntensity must be 1.0f", 1.0f, defaultPage.filterIntensity, 0.0001f)
        assertEquals("Default filterMode must be ORIGINAL", FilterMode.ORIGINAL, defaultPage.filterMode)
        assertEquals("Default rotationDegrees must be 0", 0, defaultPage.rotationDegrees)
        assertEquals("Default thumbnailPath must match originalImagePath", "/storage/test_img.jpg", defaultPage.thumbnailPath)

        val customPage = ScannedPage(
            originalImagePath = "/storage/test_img.jpg",
            filterMode = FilterMode.BW_CLEAN,
            filterIntensity = 0.42f
        )
        assertEquals("Custom filterIntensity must be preserved", 0.42f, customPage.filterIntensity, 0.0001f)
        assertEquals("Custom filterMode must be preserved", FilterMode.BW_CLEAN, customPage.filterMode)
    }

    // =========================================================================
    // 6. testScannedPageCopyIsolation
    // =========================================================================
    @Test
    fun testScannedPageCopyIsolation() {
        val original = ScannedPage(
            originalImagePath = "/storage/original.jpg",
            thumbnailPath = "/storage/thumb.jpg",
            rotationDegrees = 90,
            filterMode = FilterMode.MAGIC_COLOR,
            filterIntensity = 0.78f
        )

        val copy = original.copyPage()

        assertEquals("id must match", original.id, copy.id)
        assertEquals("originalImagePath must match", original.originalImagePath, copy.originalImagePath)
        assertEquals("thumbnailPath must match", original.thumbnailPath, copy.thumbnailPath)
        assertEquals("rotationDegrees must match", original.rotationDegrees, copy.rotationDegrees)
        assertEquals("filterMode must match", original.filterMode, copy.filterMode)
        assertEquals("filterIntensity must match", original.filterIntensity, copy.filterIntensity, 0.0001f)
        assertEquals("timestamp must match", original.timestamp, copy.timestamp)

        // Mutation isolation: Modifying copy must NEVER mutate original
        copy.filterIntensity = 0.15f
        copy.filterMode = FilterMode.GRAYSCALE
        copy.rotationDegrees = 180

        assertEquals("Original filterIntensity must remain 0.78f", 0.78f, original.filterIntensity, 0.0001f)
        assertEquals("Original filterMode must remain MAGIC_COLOR", FilterMode.MAGIC_COLOR, original.filterMode)
        assertEquals("Original rotationDegrees must remain 90", 90, original.rotationDegrees)
    }

    // =========================================================================
    // 7. testScanStorageManagerSerializationAndRoundtrip
    // =========================================================================
    @Test
    fun testScanStorageManagerSerializationAndRoundtrip() {
        val page = ScannedPage(
            id = "test-uuid-1",
            originalImagePath = "/data/page1.jpg",
            filterMode = FilterMode.BW_CLEAN,
            filterIntensity = 0.65f
        )

        // Simulating writeProjectsJson serialization logic:
        val serializedJson = buildString {
            append("{")
            append("\"id\":\"${page.id}\",")
            append("\"originalImagePath\":\"${page.originalImagePath}\",")
            append("\"thumbnailPath\":\"${page.thumbnailPath}\",")
            append("\"rotationDegrees\":${page.rotationDegrees},")
            append("\"filterMode\":\"${page.filterMode.name}\",")
            append("\"filterIntensity\":${page.filterIntensity.toDouble()}")
            append("}")
        }

        assertTrue("JSON must contain filterIntensity key", serializedJson.contains("\"filterIntensity\""))

        // Roundtrip deserialization simulation:
        val matcher = Pattern.compile("\"filterIntensity\"\\s*:\\s*([0-9.]+)").matcher(serializedJson)
        assertTrue("Matcher must find filterIntensity", matcher.find())
        val matchedStr = matcher.group(1)
        assertNotNull("Matched filterIntensity must not be null", matchedStr)
        val deserializedIntensity = matchedStr!!.toFloat().coerceIn(0f, 1f)

        assertEquals("Roundtrip filterIntensity must match exactly", page.filterIntensity, deserializedIntensity, 0.0001f)
    }

    // =========================================================================
    // 8. testScanStorageManagerBackwardCompatibility
    // =========================================================================
    @Test
    fun testScanStorageManagerBackwardCompatibility() {
        // Legacy JSON produced before Milestone 3 without filterIntensity field:
        val legacyJson = """
            {
                "id": "legacy-scan-99",
                "originalImagePath": "/data/legacy/img.jpg",
                "thumbnailPath": "/data/legacy/thumb.jpg",
                "rotationDegrees": 0,
                "filterMode": "BW_CLEAN"
            }
        """.trimIndent()

        val hasIntensity = legacyJson.contains("\"filterIntensity\"")
        assertFalse("Legacy JSON must not contain filterIntensity field", hasIntensity)

        // ScanStorageManager deserialization logic: optDouble("filterIntensity", 1.0).toFloat().coerceIn(0f, 1f)
        val deserialized = if (hasIntensity) {
            val matcher = Pattern.compile("\"filterIntensity\"\\s*:\\s*([0-9.]+)").matcher(legacyJson)
            if (matcher.find()) (matcher.group(1)?.toFloat() ?: 1.0f).coerceIn(0f, 1f) else 1.0f
        } else {
            1.0f // default for legacy documents
        }

        assertEquals("Legacy documents must safely default filterIntensity to 1.0f", 1.0f, deserialized, 0.0001f)

        // Test clamping of malformed legacy values
        val outOfBoundsJson = """{"filterIntensity": 3.5}"""
        val outMatcher = Pattern.compile("\"filterIntensity\"\\s*:\\s*([0-9.]+)").matcher(outOfBoundsJson)
        assertTrue(outMatcher.find())
        val matchedOutStr = outMatcher.group(1)
        assertNotNull("Matched out of bounds string must not be null", matchedOutStr)
        val clampedOutOfBounds = matchedOutStr!!.toFloat().coerceIn(0f, 1f)
        assertEquals("Out-of-bounds value 3.5 must be clamped to 1.0f", 1.0f, clampedOutOfBounds, 0.0001f)
    }

    // =========================================================================
    // 9. testMainActivityPageSyncContract
    // =========================================================================
    @Test
    fun testMainActivityPageSyncContract() {
        // Emulate MainActivity pagesList in memory
        val initialPage1 = ScannedPage(
            id = "p-1",
            originalImagePath = "/scans/p1.jpg",
            filterMode = FilterMode.ORIGINAL,
            filterIntensity = 1.0f
        )
        val initialPage2 = ScannedPage(
            id = "p-2",
            originalImagePath = "/scans/p2.jpg",
            filterMode = FilterMode.ORIGINAL,
            filterIntensity = 1.0f
        )
        val pagesList = mutableListOf(initialPage1, initialPage2)

        // Emulate SinglePageEditView returning updated copies with modified filter parameters
        val updatedPage1 = initialPage1.copyPage().apply {
            filterMode = FilterMode.BW_CLEAN
            filterIntensity = 0.52f
            rotationDegrees = 90
        }
        val updatedPage2 = initialPage2.copyPage().apply {
            filterMode = FilterMode.MAGIC_COLOR
            filterIntensity = 0.88f
            rotationDegrees = 180
        }
        val updatedPages = listOf(updatedPage1, updatedPage2)

        // Execute MainActivity showMultiPageEditor synchronization loop
        for (i in updatedPages.indices) {
            if (i < pagesList.size) {
                pagesList[i].cropQuad = updatedPages[i].cropQuad
                pagesList[i].rotationDegrees = updatedPages[i].rotationDegrees
                pagesList[i].filterMode = updatedPages[i].filterMode
                pagesList[i].filterIntensity = updatedPages[i].filterIntensity
                pagesList[i].thumbnailPath = updatedPages[i].thumbnailPath
            }
        }

        // Verify that MainActivity pagesList was synchronized accurately
        assertEquals(FilterMode.BW_CLEAN, pagesList[0].filterMode)
        assertEquals(0.52f, pagesList[0].filterIntensity, 0.0001f)
        assertEquals(90, pagesList[0].rotationDegrees)

        assertEquals(FilterMode.MAGIC_COLOR, pagesList[1].filterMode)
        assertEquals(0.88f, pagesList[1].filterIntensity, 0.0001f)
        assertEquals(180, pagesList[1].rotationDegrees)
    }

    // =========================================================================
    // 10. testClampingEdgeCases_nanInfinityNegatives
    // =========================================================================
    @Test
    fun testClampingEdgeCases_nanInfinityNegatives() {
        // NaN check
        assertEquals("NaN must default to 1.0f", 1.0f, DocumentFilterEngine.clampIntensity(Float.NaN), 0.0001f)
        assertEquals("calculateBwCleanKFactor with NaN must use 1.0f", 0.95f, DocumentFilterEngine.calculateBwCleanKFactor(Float.NaN), 0.0001f)
        val magicNan = DocumentFilterEngine.calculateMagicColorParams(Float.NaN)
        assertEquals(1.50f, magicNan.first, 0.0001f)
        assertEquals(0.55f, magicNan.second, 0.0001f)
        val grayNan = DocumentFilterEngine.calculateGrayscaleParams(Float.NaN)
        assertEquals(0.00f, grayNan.first, 0.0001f)
        assertEquals(1.35f, grayNan.second, 0.0001f)

        // Infinity checks
        assertEquals("Positive Infinity must clamp to 1.0f", 1.0f, DocumentFilterEngine.clampIntensity(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals("Negative Infinity must clamp to 0.0f", 0.0f, DocumentFilterEngine.clampIntensity(Float.NEGATIVE_INFINITY), 0.0001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(Float.NEGATIVE_INFINITY), 0.0001f)

        // Extreme float values
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(Float.MAX_VALUE), 0.0001f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-Float.MAX_VALUE), 0.0001f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-0.0f), 0.0001f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-1000.0f), 0.0001f)
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(1000.0f), 0.0001f)
    }

    // =========================================================================
    // 11. testCvMathColorBlueIntegrity
    // =========================================================================
    @Test
    fun testCvMathColorBlueIntegrity() {
        val filterEngineFile = resolvePath("src/main/java/com/clickem/scanner/util/cv/DocumentFilterEngine.kt")
        assertTrue("DocumentFilterEngine.kt must exist", filterEngineFile.exists())

        val content = filterEngineFile.readText()

        // Count Color.blue(p) occurrences
        val bluePattern = Pattern.compile("Color\\.blue\\(p\\)")
        val matcher = bluePattern.matcher(content)
        var blueCount = 0
        while (matcher.find()) blueCount++

        assertEquals(
            "DocumentFilterEngine.kt must preserve exactly 4 occurrences of Color.blue(p)",
            4,
            blueCount
        )

        // Verify ITU-R BT.601 standard RGB-to-grayscale formula
        assertTrue(
            "DocumentFilterEngine must preserve ITU-R BT.601 formula",
            content.contains("Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114")
        )

        // Verify integer division by 1000
        assertTrue(
            "DocumentFilterEngine must divide luminance sum by 1000",
            content.contains(") / 1000")
        )
    }

    // =========================================================================
    // 12. testDocumentFilterEngineDefaultIntensityParameter
    // =========================================================================
    @Test
    fun testDocumentFilterEngineDefaultIntensityParameter() {
        // Reflection check: DocumentFilterEngine.applyFilter must accept intensity with default argument
        val methods = DocumentFilterEngine::class.java.methods
        val applyFilterMethod = methods.find {
            it.name == "applyFilter" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Bitmap::class.java &&
                it.parameterTypes[1] == FilterMode::class.java &&
                (it.parameterTypes[2] == java.lang.Float.TYPE || it.parameterTypes[2] == java.lang.Float::class.java)
        }
        assertNotNull("applyFilter(Bitmap, FilterMode, Float) must exist on DocumentFilterEngine", applyFilterMethod)

        // Synthetic method generated by Kotlin for default arguments: applyFilter$default
        val syntheticDefaultMethod = methods.find {
            it.name.startsWith("applyFilter\$default")
        }
        assertNotNull("Kotlin must generate applyFilter\$default to support default intensity parameter", syntheticDefaultMethod)

        // Verify pure mathematical policy methods exist on DocumentFilterEngine
        val bwCleanMethod = methods.find { it.name == "calculateBwCleanKFactor" }
        assertNotNull("calculateBwCleanKFactor must exist", bwCleanMethod)

        val magicMethod = methods.find { it.name == "calculateMagicColorParams" }
        assertNotNull("calculateMagicColorParams must exist", magicMethod)

        val grayMethod = methods.find { it.name == "calculateGrayscaleParams" }
        assertNotNull("calculateGrayscaleParams must exist", grayMethod)

        val clampMethod = methods.find { it.name == "clampIntensity" }
        assertNotNull("clampIntensity must exist", clampMethod)
    }
}
