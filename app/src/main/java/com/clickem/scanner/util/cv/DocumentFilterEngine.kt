package com.clickem.scanner.util.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.clickem.scanner.model.FilterMode
import kotlin.math.max
import kotlin.math.min

object DocumentFilterEngine {

    /**
     * Clamps the filter intensity to [0.0f .. 1.0f], defaulting NaN to 1.0f.
     */
    fun clampIntensity(intensity: Float): Float {
        if (intensity.isNaN()) return 1.0f
        return intensity.coerceIn(0.0f, 1.0f)
    }

    /**
     * Mathematical policy helper calculating Bradley-Roth kFactor from intensity.
     */
    fun calculateBwCleanKFactor(intensity: Float): Float =
        0.70f + (clampIntensity(intensity) * 0.25f)

    /**
     * Mathematical policy helper calculating Magic Color bleach and ink deepen parameters.
     */
    fun calculateMagicColorParams(intensity: Float): Pair<Float, Float> {
        val clamped = clampIntensity(intensity)
        val bleachBoost = 0.4f + (clamped * 1.1f)
        val inkDeepen = 1.0f - (clamped * 0.45f)
        return Pair(bleachBoost, inkDeepen)
    }

    /**
     * Mathematical policy helper calculating Grayscale saturation and contrast parameters.
     */
    fun calculateGrayscaleParams(intensity: Float): Pair<Float, Float> {
        val clamped = clampIntensity(intensity)
        val saturation = 1.0f - clamped
        val contrast = 1.0f + (clamped * 0.35f)
        return Pair(saturation, contrast)
    }

    // Direct named property helpers for test verification and callers
    fun calculateKFactor(intensity: Float): Float = calculateBwCleanKFactor(intensity)
    fun calculateBleachBoost(intensity: Float): Float = calculateMagicColorParams(intensity).first
    fun calculateInkDeepen(intensity: Float): Float = calculateMagicColorParams(intensity).second
    fun calculateGrayscaleSaturation(intensity: Float): Float = calculateGrayscaleParams(intensity).first
    fun calculateGrayscaleContrast(intensity: Float): Float = calculateGrayscaleParams(intensity).second
    fun calculateGrayscaleShift(intensity: Float): Float = -20f * clampIntensity(intensity)

    /**
     * Applies the selected document enhancement filter to the input Bitmap.
     * Engineered for low-memory allocation and high-throughput execution.
     *
     * @param source The input uncompressed/processed Bitmap.
     * @param filterMode The target preset mode (ORIGINAL, BW_CLEAN, MAGIC_COLOR, GRAYSCALE).
     * @param intensity Filter intensity/sensitivity in range [0.0f, 1.0f], defaulting to 1.0f.
     */
    fun applyFilter(source: Bitmap, filterMode: FilterMode, intensity: Float = 1.0f): Bitmap {
        val clampedIntensity = clampIntensity(intensity)
        return when (filterMode) {
            FilterMode.ORIGINAL -> source
            FilterMode.BW_CLEAN -> applyFastAdaptiveBinarization(source, clampedIntensity)
            FilterMode.MAGIC_COLOR -> applyMagicColorEnhance(source, clampedIntensity)
            FilterMode.TEXT_POP -> applyTextPopEnhance(source, clampedIntensity)
            FilterMode.GRAYSCALE -> applyFastGrayscale(source, clampedIntensity)
            FilterMode.WARM_NOTE -> applyWarmNote(source, clampedIntensity)
            FilterMode.INVERT -> applyInvert(source, clampedIntensity)
        }
    }

    /**
     * Fast Bradley-Roth / Sauvola Adaptive Local Thresholding with Subsampled Integral Image.
     * Uses a 4x subsampled integral image (consuming <6MB RAM instead of 96MB)
     * to eliminate shadows, yellowing, and wrinkles, producing pure crisp document scans.
     */
    private fun applyFastAdaptiveBinarization(source: Bitmap, intensity: Float = 1.0f): Bitmap {
        val w = source.width
        val h = source.height

        // Downsample factor for integral image computation
        val scale = 4
        val sw = (w + scale - 1) / scale
        val sh = (h + scale - 1) / scale

        val subIntegral = LongArray(sw * sh)

        // 1. Build compact integral image from subsampled luminance
        val rowPixels = IntArray(w)
        for (sy in 0 until sh) {
            val srcY = min(sy * scale, h - 1)
            source.getPixels(rowPixels, 0, w, 0, srcY, w, 1)

            var rowSum = 0L
            val subRowOffset = sy * sw
            val prevSubRowOffset = (sy - 1) * sw

            for (sx in 0 until sw) {
                val srcX = min(sx * scale, w - 1)
                val p = rowPixels[srcX]
                val lum = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000).toLong()
                rowSum += lum
                subIntegral[subRowOffset + sx] = rowSum + (if (sy > 0) subIntegral[prevSubRowOffset + sx] else 0L)
            }
        }

        // 2. Adaptive thresholding per line to keep memory footprint minimal
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outLine = IntArray(w)

        val windowSize = max(16, sw / 12)
        val halfW = windowSize / 2
        val kFactor = calculateBwCleanKFactor(intensity)

        for (y in 0 until h) {
            source.getPixels(rowPixels, 0, w, 0, y, w, 1)
            val sy = (y / scale).coerceIn(0, sh - 1)
            val sy1 = (sy - halfW).coerceAtLeast(0)
            val sy2 = (sy + halfW).coerceAtMost(sh - 1)

            for (x in 0 until w) {
                val sx = (x / scale).coerceIn(0, sw - 1)
                val sx1 = (sx - halfW).coerceAtLeast(0)
                val sx2 = (sx + halfW).coerceAtMost(sw - 1)
                val count = (sx2 - sx1 + 1) * (sy2 - sy1 + 1)

                val a = if (sy1 > 0 && sx1 > 0) subIntegral[(sy1 - 1) * sw + (sx1 - 1)] else 0L
                val b = if (sy1 > 0) subIntegral[(sy1 - 1) * sw + sx2] else 0L
                val c = if (sx1 > 0) subIntegral[sy2 * sw + (sx1 - 1)] else 0L
                val d = subIntegral[sy2 * sw + sx2]

                val localMean = (d - b - c + a) / count
                val p = rowPixels[x]
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

                outLine[x] = if (lum < localMean * kFactor) Color.BLACK else Color.WHITE
            }
            result.setPixels(outLine, 0, w, 0, y, w, 1)
        }

        return result
    }

    /**
     * Magic Color: Dynamic auto-contrast stretching with white-point illumination compensation.
     * Retains signature ink and color graphics while bleaching out gray paper background.
     */
    private fun applyMagicColorEnhance(source: Bitmap, intensity: Float = 1.0f): Bitmap {
        val w = source.width
        val h = source.height

        // 1. Sample luminance histogram to estimate paper white point and ink black point
        var minLum = 255
        var maxLum = 0
        val sampleRow = IntArray(w)
        val step = 4

        for (y in 0 until h step step) {
            source.getPixels(sampleRow, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val p = sampleRow[x]
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }

        val range = (maxLum - minLum).coerceAtLeast(1)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val line = IntArray(w)

        val (bleachBoost, inkDeepen) = calculateMagicColorParams(intensity)

        for (y in 0 until h) {
            source.getPixels(line, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = line[x]
                var r = Color.red(p)
                var g = Color.green(p)
                var b = Color.blue(p)

                // Linear auto-contrast
                r = (((r - minLum).toFloat() / range) * 255f).toInt().coerceIn(0, 255)
                g = (((g - minLum).toFloat() / range) * 255f).toInt().coerceIn(0, 255)
                b = (((b - minLum).toFloat() / range) * 255f).toInt().coerceIn(0, 255)

                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum > 175) {
                    // Bleach background paper shadows to pure clean white
                    val boost = (lum - 175) * bleachBoost
                    r = (r + boost).toInt().coerceIn(0, 255)
                    g = (g + boost).toInt().coerceIn(0, 255)
                    b = (b + boost).toInt().coerceIn(0, 255)
                } else if (lum < 100) {
                    // Deepen text and stamp ink
                    r = (r * inkDeepen).toInt().coerceIn(0, 255)
                    g = (g * inkDeepen).toInt().coerceIn(0, 255)
                    b = (b * inkDeepen).toInt().coerceIn(0, 255)
                }

                line[x] = Color.rgb(r, g, b)
            }
            result.setPixels(line, 0, w, 0, y, w, 1)
        }

        return result
    }

    /**
     * Hardware-accelerated Grayscale conversion with S-curve contrast boost.
     *
     * @param source The input source Bitmap.
     * @param intensity Parameterizes saturation (1.0f - intensity) and contrast (1.0f + intensity * 0.35f).
     */
    private fun applyFastGrayscale(source: Bitmap, intensity: Float = 1.0f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val (saturation, contrast) = calculateGrayscaleParams(intensity)
        val cm = ColorMatrix().apply {
            setSaturation(saturation)
        }
        val shift = -25f * clampIntensity(intensity)
        // Contrast enhancement matrix
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, shift,
            0f, contrast, 0f, 0f, shift,
            0f, 0f, contrast, 0f, shift,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrastMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Text Pop: Enhances document text clarity and edge sharpness,
     * rescuing faint pencil and faded print while keeping colors distinct.
     */
    private fun applyTextPopEnhance(source: Bitmap, intensity: Float = 1.0f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val contrast = 1.0f + (0.55f * intensity)
        val brightness = -128f * (contrast - 1f) + (18f * intensity)

        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Warm Note: Soft vintage ivory / warm study note tone,
     * reducing glare and eye strain for comfortable document reading.
     */
    private fun applyWarmNote(source: Bitmap, intensity: Float = 1.0f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val contrast = 1.0f + (0.22f * intensity)
        val rScale = 1.08f * contrast
        val gScale = 1.00f * contrast
        val bScale = (0.86f - (0.10f * intensity)) * contrast

        val cm = ColorMatrix(floatArrayOf(
            rScale, 0f, 0f, 0f, 10f * intensity,
            0f, gScale, 0f, 0f, 6f * intensity,
            0f, 0f, bScale, 0f, -8f * intensity,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Invert / Dark: High-contrast dark document mode (inverted dark background with crisp white text).
     */
    private fun applyInvert(source: Bitmap, intensity: Float = 1.0f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Interpolate between normal and inverted based on intensity
        val inv = -1.0f * intensity
        val normal = 1.0f * (1.0f - intensity)
        val diag = inv + normal
        val shift = 255f * intensity

        val cm = ColorMatrix(floatArrayOf(
            diag, 0f, 0f, 0f, shift,
            0f, diag, 0f, 0f, shift,
            0f, 0f, diag, 0f, shift,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Real-time Brightness and Contrast adjustments (-100..+100 range).
     */
    fun applyBrightnessContrast(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // contrast scale: 0..2 (1 is neutral)
        val cScale = (contrast + 100f) / 100f
        // brightness translation: -255..+255
        val bShift = (brightness / 100f) * 128f

        val cm = ColorMatrix(floatArrayOf(
            cScale, 0f, 0f, 0f, bShift,
            0f, cScale, 0f, 0f, bShift,
            0f, 0f, cScale, 0f, bShift,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }
}
