package com.clickem.scanner.util.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object PdfGenerator {

    /**
     * Generates a multi-page PDF where each page matches the exact aspect ratio
     * and boundary of the scanned image with ZERO white margins (100% borderless).
     */
    fun createPdf(
        pageBitmaps: List<Bitmap>,
        outputFile: File
    ): Boolean {
        if (pageBitmaps.isEmpty()) return false

        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            for (i in pageBitmaps.indices) {
                val bmp = pageBitmaps[i]
                // Use exact bitmap pixel dimensions so 100% of native sensor clarity is retained with zero downscaling
                val pageW = bmp.width
                val pageH = bmp.height

                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // Fill 100% edge-to-edge without any white borders or raster interpolation loss
                canvas.drawBitmap(bmp, 0f, 0f, paint)
                document.finishPage(page)
            }

            FileOutputStream(outputFile).use { fos ->
                document.writeTo(fos)
            }
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            return false
        } finally {
            try {
                document.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Generates a single-page borderless PDF from a single Bitmap.
     */
    fun createSinglePagePdf(
        bitmap: Bitmap,
        outputFile: File
    ): Boolean {
        return createPdf(listOf(bitmap), outputFile)
    }
}
