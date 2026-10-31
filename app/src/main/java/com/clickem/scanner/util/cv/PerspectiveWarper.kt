package com.clickem.scanner.util.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max

object PerspectiveWarper {

    /**
     * Warps a quadrilateral region defined by 4 normalized points [TL, TR, BR, BL]
     * into a flattened, deskewed rectangular Bitmap.
     * Optionally rotates the source bitmap by [rotationDegrees] beforehand if specified.
     */
    fun warpQuad(
        source: Bitmap,
        quad: List<PointF>,
        rotationDegrees: Int = 0
    ): Bitmap {
        // 1. Apply orientation rotation if needed
        val rotatedSource = if (rotationDegrees % 360 != 0) {
            val rotMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotBmp = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotMatrix, true)
            rotBmp
        } else {
            source
        }

        if (quad.size != 4) return rotatedSource

        val bw = rotatedSource.width.toFloat()
        val bh = rotatedSource.height.toFloat()

        val srcTL = PointF(quad[0].x * bw, quad[0].y * bh)
        val srcTR = PointF(quad[1].x * bw, quad[1].y * bh)
        val srcBR = PointF(quad[2].x * bw, quad[2].y * bh)
        val srcBL = PointF(quad[3].x * bw, quad[3].y * bh)

        // Calculate output dimensions using Euclidean distance between opposing corners
        val widthTop = hypot((srcTR.x - srcTL.x).toDouble(), (srcTR.y - srcTL.y).toDouble())
        val widthBottom = hypot((srcBR.x - srcBL.x).toDouble(), (srcBR.y - srcBL.y).toDouble())
        val targetWidth = max(widthTop, widthBottom).toInt().coerceIn(200, 8192)

        val heightLeft = hypot((srcBL.x - srcTL.x).toDouble(), (srcBL.y - srcTL.y).toDouble())
        val heightRight = hypot((srcBR.x - srcTR.x).toDouble(), (srcBR.y - srcTR.y).toDouble())
        val targetHeight = max(heightLeft, heightRight).toInt().coerceIn(200, 8192)

        val srcPoints = floatArrayOf(
            srcTL.x, srcTL.y,
            srcTR.x, srcTR.y,
            srcBR.x, srcBR.y,
            srcBL.x, srcBL.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        val polySuccess = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        if (!polySuccess) {
            // If transformation is degenerate, return copy of rotated source
            if (rotatedSource != source) {
                return rotatedSource
            }
            return Bitmap.createBitmap(source)
        }

        return try {
            val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            canvas.drawBitmap(rotatedSource, matrix, paint)

            if (rotatedSource != source) {
                rotatedSource.recycle()
            }
            resultBitmap
        } catch (e: Throwable) {
            e.printStackTrace()
            if (rotatedSource != source) rotatedSource else Bitmap.createBitmap(source)
        }
    }

    private fun point(px: Float, py: Float): PointF {
        return PointF(px, py).apply {
            x = px
            y = py
        }
    }

    /**
     * Rotates a 4-point normalized crop quad `[TL, TR, BR, BL]` by 90 degrees clockwise:
     * `(x, y) -> (1 - y, x)` with corner index cyclic shift `[BL, TL, TR, BR]`.
     */
    fun rotateQuad90CW(quad: List<PointF>): MutableList<PointF> {
        if (quad.size != 4) return quad.map { point(it.x, it.y) }.toMutableList()
        return mutableListOf(
            point((1f - quad[3].y).coerceIn(0f, 1f), quad[3].x.coerceIn(0f, 1f)),
            point((1f - quad[0].y).coerceIn(0f, 1f), quad[0].x.coerceIn(0f, 1f)),
            point((1f - quad[1].y).coerceIn(0f, 1f), quad[1].x.coerceIn(0f, 1f)),
            point((1f - quad[2].y).coerceIn(0f, 1f), quad[2].x.coerceIn(0f, 1f))
        )
    }

    /**
     * Rotates a 4-point normalized crop quad `[TL, TR, BR, BL]` by 90 degrees counter-clockwise:
     * `(x, y) -> (y, 1 - x)` with corner index cyclic shift `[TR, BR, BL, TL]`.
     */
    fun rotateQuad90CCW(quad: List<PointF>): MutableList<PointF> {
        if (quad.size != 4) return quad.map { point(it.x, it.y) }.toMutableList()
        return mutableListOf(
            point(quad[1].y.coerceIn(0f, 1f), (1f - quad[1].x).coerceIn(0f, 1f)),
            point(quad[2].y.coerceIn(0f, 1f), (1f - quad[2].x).coerceIn(0f, 1f)),
            point(quad[3].y.coerceIn(0f, 1f), (1f - quad[3].x).coerceIn(0f, 1f)),
            point(quad[0].y.coerceIn(0f, 1f), (1f - quad[0].x).coerceIn(0f, 1f))
        )
    }
}
