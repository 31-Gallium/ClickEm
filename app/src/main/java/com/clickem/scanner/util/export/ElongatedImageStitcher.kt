package com.clickem.scanner.util.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object ElongatedImageStitcher {

    private const val MAX_TEXTURE_DIMENSION = 8192

    /**
     * Stitches multiple scanned pages vertically into a single continuous elongated image.
     * Automatically constrains maximum texture dimension to 8192px to prevent OpenGL/Skia crashes
     * and catches OutOfMemoryError gracefully.
     */
    fun stitchPagesVertically(
        pages: List<Bitmap>,
        outputFile: File,
        dividerHeightPx: Int = 6
    ): Boolean {
        if (pages.isEmpty()) return false

        try {
            // Initial maximum width candidate
            var targetWidth = pages.maxOf { it.width }.coerceIn(600, 1800)
            var totalHeight = 0

            var scaledHeights = pages.map { bmp ->
                val h = (targetWidth.toFloat() / bmp.width * bmp.height).roundToInt()
                totalHeight += h
                h
            }
            totalHeight += (pages.size - 1) * dividerHeightPx

            // If combined height exceeds GPU/Skia limit, proportionally downscale targetWidth
            if (totalHeight > MAX_TEXTURE_DIMENSION) {
                val scaleDown = (MAX_TEXTURE_DIMENSION - (pages.size * dividerHeightPx)).toFloat() / totalHeight
                targetWidth = (targetWidth * scaleDown).toInt().coerceAtLeast(400)
                totalHeight = 0
                scaledHeights = pages.map { bmp ->
                    val h = (targetWidth.toFloat() / bmp.width * bmp.height).roundToInt()
                    totalHeight += h
                    h
                }
                totalHeight += (pages.size - 1) * dividerHeightPx
            }

            val stitchedBitmap = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitchedBitmap)
            canvas.drawColor(Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#e2e8f0")
                strokeWidth = 2f
            }

            var currentY = 0
            for (i in pages.indices) {
                val bmp = pages[i]
                val h = scaledHeights[i]
                val dstRect = Rect(0, currentY, targetWidth, currentY + h)
                canvas.drawBitmap(bmp, null, dstRect, paint)
                currentY += h

                if (i < pages.size - 1) {
                    val lineY = currentY + (dividerHeightPx / 2f)
                    canvas.drawLine(0f, lineY, targetWidth.toFloat(), lineY, dividerPaint)
                    currentY += dividerHeightPx
                }
            }

            FileOutputStream(outputFile).use { fos ->
                stitchedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            stitchedBitmap.recycle()
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            return false
        }
    }
}
