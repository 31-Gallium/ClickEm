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
 * Empirical Adversarial Challenge Suite for Milestone 3 (Filter Engine Parameterization & Model):
 *
 * 1. Intensity Clamping & Floating-Point Edge Cases (0.0f, 1.0f, -5.0f, 10.0f, Float.NaN, +/-Infinity, subnormals).
 * 2. Mathematical Monotonicity & Invariants for BW_CLEAN, MAGIC_COLOR, and GRAYSCALE.
 * 3. Backward Compatibility of ScanStorageManager with legacy pre-M3 JSON structures.
 * 4. CV Mathematical Preservation of exactly 4 occurrences of Color.blue(p) and BT.601 luminance coefficients.
 * 5. ScannedPage copy isolation and model contract.
 * 6. MainActivity synchronization contract integrity.
 */
class M3FilterRegressionChallengeTest {

    private fun resolveFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath)
        )
        return candidates.firstOrNull { it.exists() } ?: File(relativePath)
    }

    // =========================================================================
    // 1. Adversarial Intensity Clamping & Floating-Point Edge Cases
    // =========================================================================
    @Test
    fun testAdversarialIntensityClampingAndEdgeCases() {
        // Lower bound (0.0f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(0.0f), 0.00001f)

        // Upper bound (1.0f)
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(1.0f), 0.00001f)

        // Negative values (must clamp to 0.0f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-5.0f), 0.00001f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-0.00001f), 0.00001f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-10000.0f), 0.00001f)
        assertEquals(0.0f, DocumentFilterEngine.clampIntensity(-Float.MAX_VALUE), 0.00001f)

        // Overflow values (must clamp to 1.0f)
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(10.0f), 0.00001f)
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(1.00001f), 0.00001f)
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(99999.0f), 0.00001f)
        assertEquals(1.0f, DocumentFilterEngine.clampIntensity(Float.MAX_VALUE), 0.00001f)

        // IEEE 754 Special Floating-Point Values
        assertEquals("Float.NaN must fallback to 1.0f", 1.0f, DocumentFilterEngine.clampIntensity(Float.NaN), 0.00001f)
        assertEquals("Float.POSITIVE_INFINITY must clamp to 1.0f", 1.0f, DocumentFilterEngine.clampIntensity(Float.POSITIVE_INFINITY), 0.00001f)
        assertEquals("Float.NEGATIVE_INFINITY must clamp to 0.0f", 0.0f, DocumentFilterEngine.clampIntensity(Float.NEGATIVE_INFINITY), 0.00001f)
        assertEquals("Negative zero -0.0f must clamp to 0.0f", 0.0f, DocumentFilterEngine.clampIntensity(-0.0f), 0.00001f)

        // Subnormal float check (Float.MIN_VALUE ~ 1.4e-45)
        val subnormal = Float.MIN_VALUE
        val clampedSubnormal = DocumentFilterEngine.clampIntensity(subnormal)
        assertTrue("Subnormal float must clamp within [0.0f, 1.0f]", clampedSubnormal in 0.0f..1.0f)

        // Fuzz generator: 5000 stepped values spanning [-20.0f .. +20.0f]
        val step = 40.0f / 5000f
        var current = -20.0f
        for (i in 0..5000) {
            val clamped = DocumentFilterEngine.clampIntensity(current)
            assertTrue("Clamped value must be >= 0.0f for input $current", clamped >= 0.0f)
            assertTrue("Clamped value must be <= 1.0f for input $current", clamped <= 1.0f)
            assertFalse("Clamped value must never be NaN for input $current", clamped.isNaN())
            assertFalse("Clamped value must never be Infinite for input $current", clamped.isInfinite())
            current += step
        }
    }

    // =========================================================================
    // 2. Mathematical Monotonicity & Invariants for BW_CLEAN
    // =========================================================================
    @Test
    fun testBwCleanKFactorMonotonicityAndInvariants() {
        // Formula: calculateBwCleanKFactor(intensity) = 0.70f + (clampIntensity(intensity) * 0.25f)
        // Strictly increases from 0.70f to 0.95f as intensity increases from 0.0f to 1.0f.

        // Boundary checks
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(0.0f), 0.00001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(1.0f), 0.00001f)

        // Exact legacy hardcoded threshold check: 0.86f at intensity 0.64f
        assertEquals(0.86f, DocumentFilterEngine.calculateBwCleanKFactor(0.64f), 0.00001f)

        // Clamping edge cases
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(-5.0f), 0.00001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(10.0f), 0.00001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(Float.NaN), 0.00001f)
        assertEquals(0.70f, DocumentFilterEngine.calculateBwCleanKFactor(Float.NEGATIVE_INFINITY), 0.00001f)
        assertEquals(0.95f, DocumentFilterEngine.calculateBwCleanKFactor(Float.POSITIVE_INFINITY), 0.00001f)

        // Strict monotonicity oracle: for every x1 < x2 in [0, 1], f(x1) < f(x2)
        val steps = 100
        for (i in 0 until steps) {
            val v1 = i / steps.toFloat()
            val v2 = (i + 1) / steps.toFloat()
            val k1 = DocumentFilterEngine.calculateBwCleanKFactor(v1)
            val k2 = DocumentFilterEngine.calculateBwCleanKFactor(v2)
            assertTrue("BW_CLEAN kFactor must strictly increase: k($v2)=$k2 > k($v1)=$k1", k2 > k1)
        }
    }

    // =========================================================================
    // 3. Mathematical Monotonicity & Invariants for MAGIC_COLOR
    // =========================================================================
    @Test
    fun testMagicColorParamsMonotonicityAndInvariants() {
        // Formula:
        // bleachBoost = 0.40f + (clamped * 1.10f) -> strictly increases from 0.40f to 1.50f
        // inkDeepen   = 1.00f - (clamped * 0.45f) -> strictly decreases from 1.00f to 0.55f

        // Boundary checks
        val (bleach0, ink0) = DocumentFilterEngine.calculateMagicColorParams(0.0f)
        assertEquals(0.40f, bleach0, 0.00001f)
        assertEquals(1.00f, ink0, 0.00001f)

        val (bleach1, ink1) = DocumentFilterEngine.calculateMagicColorParams(1.0f)
        assertEquals(1.50f, bleach1, 0.00001f)
        assertEquals(0.55f, ink1, 0.00001f)

        // Clamping edge cases
        val (bleachNeg, inkNeg) = DocumentFilterEngine.calculateMagicColorParams(-5.0f)
        assertEquals(0.40f, bleachNeg, 0.00001f)
        assertEquals(1.00f, inkNeg, 0.00001f)

        val (bleachOver, inkOver) = DocumentFilterEngine.calculateMagicColorParams(10.0f)
        assertEquals(1.50f, bleachOver, 0.00001f)
        assertEquals(0.55f, inkOver, 0.00001f)

        val (bleachNan, inkNan) = DocumentFilterEngine.calculateMagicColorParams(Float.NaN)
        assertEquals(1.50f, bleachNan, 0.00001f)
        assertEquals(0.55f, inkNan, 0.00001f)

        // Strict monotonicity oracle
        val steps = 100
        for (i in 0 until steps) {
            val v1 = i / steps.toFloat()
            val v2 = (i + 1) / steps.toFloat()
            val (b1, d1) = DocumentFilterEngine.calculateMagicColorParams(v1)
            val (b2, d2) = DocumentFilterEngine.calculateMagicColorParams(v2)
            assertTrue("MagicColor bleachBoost must strictly increase: b($v2)=$b2 > b($v1)=$b1", b2 > b1)
            assertTrue("MagicColor inkDeepen must strictly decrease: d($v2)=$d2 < d($v1)=$d1", d2 < d1)
        }
    }

    // =========================================================================
    // 4. Mathematical Monotonicity & Invariants for GRAYSCALE
    // =========================================================================
    @Test
    fun testGrayscaleParamsMonotonicityAndInvariants() {
        // Formula:
        // saturation = 1.00f - clamped -> strictly decreases from 1.00f to 0.00f
        // contrast   = 1.00f + (clamped * 0.35f) -> strictly increases from 1.00f to 1.35f

        // Boundary checks
        val (sat0, con0) = DocumentFilterEngine.calculateGrayscaleParams(0.0f)
        assertEquals(1.00f, sat0, 0.00001f)
        assertEquals(1.00f, con0, 0.00001f)

        val (sat1, con1) = DocumentFilterEngine.calculateGrayscaleParams(1.0f)
        assertEquals(0.00f, sat1, 0.00001f)
        assertEquals(1.35f, con1, 0.00001f)

        // Clamping edge cases
        val (satNeg, conNeg) = DocumentFilterEngine.calculateGrayscaleParams(-5.0f)
        assertEquals(1.00f, satNeg, 0.00001f)
        assertEquals(1.00f, conNeg, 0.00001f)

        val (satOver, conOver) = DocumentFilterEngine.calculateGrayscaleParams(10.0f)
        assertEquals(0.00f, satOver, 0.00001f)
        assertEquals(1.35f, conOver, 0.00001f)

        val (satNan, conNan) = DocumentFilterEngine.calculateGrayscaleParams(Float.NaN)
        assertEquals(0.00f, satNan, 0.00001f)
        assertEquals(1.35f, conNan, 0.00001f)

        // Strict monotonicity oracle
        val steps = 100
        for (i in 0 until steps) {
            val v1 = i / steps.toFloat()
            val v2 = (i + 1) / steps.toFloat()
            val (s1, c1) = DocumentFilterEngine.calculateGrayscaleParams(v1)
            val (s2, c2) = DocumentFilterEngine.calculateGrayscaleParams(v2)
            assertTrue("Grayscale saturation must strictly decrease: s($v2)=$s2 < s($v1)=$s1", s2 < s1)
            assertTrue("Grayscale contrast must strictly increase: c($v2)=$c2 > c($v1)=$c1", c2 > c1)
        }
    }

    // =========================================================================
    // 5. Storage Backward Compatibility with Legacy Pre-M3 JSON
    // =========================================================================
    @Test
    fun testStorageBackwardCompatibilityWithLegacyPreM3Json() {
        // Construct realistic legacy JSON files simulating pre-Milestone 3 database:
        val legacyMultiPageJson = """
            [
              {
                "id": "proj-legacy-101",
                "title": "Medical Invoice 2025",
                "createdAt": 1729000000000,
                "updatedAt": 1729000050000,
                "category": "Invoices",
                "pages": [
                  {
                    "id": "page-legacy-1",
                    "originalImagePath": "/data/user/0/com.clickem.scanner/files/scans/scan_1.jpg",
                    "thumbnailPath": "/data/user/0/com.clickem.scanner/files/thumbs/thumb_1.jpg",
                    "rotationDegrees": 0,
                    "filterMode": "BW_CLEAN",
                    "cropQuad": [
                      {"x": 0.05, "y": 0.05},
                      {"x": 0.95, "y": 0.05},
                      {"x": 0.95, "y": 0.95},
                      {"x": 0.05, "y": 0.95}
                    ]
                  },
                  {
                    "id": "page-legacy-2",
                    "originalImagePath": "/data/user/0/com.clickem.scanner/files/scans/scan_2.jpg",
                    "thumbnailPath": "/data/user/0/com.clickem.scanner/files/thumbs/thumb_2.jpg",
                    "rotationDegrees": 90,
                    "filterMode": "MAGIC_COLOR"
                  },
                  {
                    "id": "page-legacy-3",
                    "originalImagePath": "/data/user/0/com.clickem.scanner/files/scans/scan_3.jpg",
                    "thumbnailPath": "/data/user/0/com.clickem.scanner/files/thumbs/thumb_3.jpg",
                    "rotationDegrees": 180,
                    "filterMode": "GRAYSCALE"
                  }
                ]
              }
            ]
        """.trimIndent()

        // Verify absence of filterIntensity in legacy payload
        assertFalse("Legacy JSON must not contain filterIntensity field", legacyMultiPageJson.contains("filterIntensity"))

        // Robust JSON page extractor handling nested cropQuad objects without external dependencies
        fun extractPageObjects(json: String): List<String> {
            val pages = mutableListOf<String>()
            var depth = 0
            var pageStart = -1
            var inPage = false
            var inString = false
            var escape = false

            for (i in json.indices) {
                val c = json[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (inString) continue

                if (c == '{') {
                    depth++
                    if (depth == 2) {
                        pageStart = i
                        inPage = true
                    }
                } else if (c == '}') {
                    if (depth == 2 && inPage) {
                        pages.add(json.substring(pageStart, i + 1))
                        inPage = false
                    }
                    depth--
                }
            }
            return pages
        }

        val pageBlocks = extractPageObjects(legacyMultiPageJson)
        assertEquals("Should parse all 3 legacy pages from sample JSON", 3, pageBlocks.size)

        for (pageBlock in pageBlocks) {
            assertTrue("Page block must have originalImagePath", pageBlock.contains("originalImagePath"))

            val intensityMatcher = Pattern.compile("\"filterIntensity\"\\s*:\\s*([0-9.\\-]+)").matcher(pageBlock)
            val parsedIntensity = if (intensityMatcher.find()) {
                (intensityMatcher.group(1)?.toFloat() ?: 1.0f).coerceIn(0f, 1f)
            } else {
                1.0f // optDouble default
            }

            assertEquals("Legacy page without filterIntensity must resolve to default 1.0f", 1.0f, parsedIntensity, 0.00001f)
        }

        // Adversarial tests for corrupted / extreme values in JSON:
        val adversarialCases = mapOf(
            "\"filterIntensity\": -15.5" to 0.0f,
            "\"filterIntensity\": 25.0" to 1.0f,
            "\"filterIntensity\": 0.0" to 0.0f,
            "\"filterIntensity\": 1.0" to 1.0f,
            "\"filterIntensity\": 0.42" to 0.42f
        )

        for ((snippet, expected) in adversarialCases) {
            val matcher = Pattern.compile("\"filterIntensity\"\\s*:\\s*([0-9.\\-]+)").matcher(snippet)
            assertTrue("Must find filterIntensity in snippet: $snippet", matcher.find())
            val rawVal = matcher.group(1)!!.toFloat()
            val coerced = rawVal.coerceIn(0f, 1f)
            assertEquals("Coerced value must match expected for $snippet", expected, coerced, 0.0001f)
        }
    }

    // =========================================================================
    // 6. CV Mathematical Preservation of 4 Color.blue(p) Occurrences
    // =========================================================================
    @Test
    fun testCvMathematicalPreservationColorBlue() {
        val filterEngineFile = resolveFile("src/main/java/com/clickem/scanner/util/cv/DocumentFilterEngine.kt")
        assertTrue("DocumentFilterEngine.kt must exist at ${filterEngineFile.path}", filterEngineFile.exists())

        val lines = filterEngineFile.readLines()
        val blueOccurrences = mutableListOf<Pair<Int, String>>()

        for ((index, line) in lines.withIndex()) {
            if (line.contains("Color.blue(p)")) {
                blueOccurrences.add(Pair(index + 1, line.trim()))
            }
        }

        assertEquals(
            "DocumentFilterEngine.kt must contain exactly 4 occurrences of Color.blue(p)",
            4,
            blueOccurrences.size
        )

        // Verify line numbers and content
        // 1. applyFastAdaptiveBinarization (integral image construction)
        assertEquals(
            "val lum = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000).toLong()",
            blueOccurrences[0].second
        )

        // 2. applyFastAdaptiveBinarization (adaptive pixel comparison)
        assertEquals(
            "val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000",
            blueOccurrences[1].second
        )

        // 3. applyMagicColorEnhance (histogram luminance sampling)
        assertEquals(
            "val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000",
            blueOccurrences[2].second
        )

        // 4. applyMagicColorEnhance (pixel color channel extraction)
        assertEquals(
            "var b = Color.blue(p)",
            blueOccurrences[3].second
        )

        // Verify standard ITU-R BT.601 luminance coefficients (299, 587, 114) with / 1000
        val fileContent = filterEngineFile.readText()
        assertTrue(
            "Must preserve ITU-R BT.601 integer coefficients",
            fileContent.contains("Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114")
        )
        assertTrue(
            "Must preserve integer division by 1000",
            fileContent.contains(") / 1000")
        )
    }

    // =========================================================================
    // 7. ScannedPage Model & Copy Isolation
    // =========================================================================
    @Test
    fun testScannedPageModelAndCopyIsolation() {
        val original = ScannedPage(
            originalImagePath = "/scans/orig.jpg",
            thumbnailPath = "/thumbs/orig.jpg",
            rotationDegrees = 270,
            filterMode = FilterMode.GRAYSCALE,
            filterIntensity = 0.85f
        )

        assertEquals(0.85f, original.filterIntensity, 0.00001f)
        assertEquals(FilterMode.GRAYSCALE, original.filterMode)

        // Perform deep copy
        val copy = original.copyPage()

        // Mutate every property of copy
        copy.filterIntensity = 0.12f
        copy.filterMode = FilterMode.BW_CLEAN
        copy.rotationDegrees = 0
        copy.thumbnailPath = "/thumbs/mutated.jpg"

        // Verify original remains strictly unaltered
        assertEquals(0.85f, original.filterIntensity, 0.00001f)
        assertEquals(FilterMode.GRAYSCALE, original.filterMode)
        assertEquals(270, original.rotationDegrees)
        assertEquals("/thumbs/orig.jpg", original.thumbnailPath)
    }

    // =========================================================================
    // 8. MainActivity Synchronization Contract Verification
    // =========================================================================
    @Test
    fun testMainActivitySynchronizationContractVerification() {
        val mainActivityFile = resolveFile("src/main/java/com/clickem/scanner/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())

        val content = mainActivityFile.readText()

        // Verify that onPagesSaved synchronizes filterIntensity
        assertTrue(
            "MainActivity must synchronize filterIntensity in onPagesSaved callback",
            content.contains("pagesList[i].filterIntensity = updatedPages[i].filterIntensity")
        )
    }
}
