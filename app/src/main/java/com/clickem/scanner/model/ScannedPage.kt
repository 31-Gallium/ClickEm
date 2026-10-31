package com.clickem.scanner.model

import android.graphics.PointF
import java.io.Serializable
import java.util.UUID

data class ScannedPage(
    val id: String = UUID.randomUUID().toString(),
    val originalImagePath: String,
    var thumbnailPath: String = originalImagePath,
    // Normalized 4-point convex quad in [0.0 .. 1.0] relative coordinates: Top-Left, Top-Right, Bottom-Right, Bottom-Left
    var cropQuad: List<PointF> = listOf(
        PointF(0f, 0f),
        PointF(1f, 0f),
        PointF(1f, 1f),
        PointF(0f, 1f)
    ),
    var rotationDegrees: Int = 0,
    var filterMode: FilterMode = FilterMode.ORIGINAL,
    var filterIntensity: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {

    fun copyPage(): ScannedPage {
        val quadCopy = cropQuad.map { PointF(it.x, it.y) }
        return ScannedPage(
            id = id,
            originalImagePath = originalImagePath,
            thumbnailPath = thumbnailPath,
            cropQuad = quadCopy,
            rotationDegrees = rotationDegrees,
            filterMode = filterMode,
            filterIntensity = filterIntensity,
            timestamp = timestamp
        )
    }
}
