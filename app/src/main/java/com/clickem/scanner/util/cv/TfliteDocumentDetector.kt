package com.clickem.scanner.util.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * On-Device Deep Learning Document Boundary Detector using TensorFlow Lite.
 *
 * Runs a lightweight MobileNetV2 DeepLabV3+ segmentation model (256x256 float32)
 * directly on-device. Produces a pixel-level segmentation mask that distinguishes
 * paper documents from complex backgrounds, textured tables, and ambient shadows,
 * followed by sub-pixel radial boundary fitting and quadrilateral convex hull extraction.
 */
object TfliteDocumentDetector {

    private const val MODEL_ASSET_PATH = "models/document_segmentation.tflite"
    private const val INPUT_SIZE = 256

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var initError: String? = null

    // ImageNet RGB normalization constants (matching the training pipeline)
    private const val MEAN_R = 0.485f
    private const val MEAN_G = 0.456f
    private const val MEAN_B = 0.406f
    private const val STD_R = 0.229f
    private const val STD_G = 0.224f
    private const val STD_B = 0.225f

    // Reusable direct ByteBuffers for allocation-free high-speed inference
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private val pixelInts = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val outputMask = FloatArray(INPUT_SIZE * INPUT_SIZE)

    @Synchronized
    fun init(context: Context) {
        if (isInitialized && interpreter != null) return

        try {
            val assetFileDescriptor = context.assets.openFd(MODEL_ASSET_PATH)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelMappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Use default CPU delegate with XNNPACK acceleration for 100% universal stability
            }

            interpreter = Interpreter(modelMappedByteBuffer, options)

            // Pre-allocate buffers: [1, 256, 256, 3] float32 for input, [1, 256, 256, 1] float32 for output
            inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            outputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 1 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            isInitialized = true
            initError = null
        } catch (e: Exception) {
            e.printStackTrace()
            initError = e.message
            isInitialized = false
        }
    }

    fun isReady(): Boolean = isInitialized && interpreter != null

    /**
     * Executes neural inference on the given bitmap and extracts the 4-corner normalized quad [TL, TR, BR, BL].
     * Returns null if no document is detected, allowing fallback to classical heuristics.
     */
    @Synchronized
    fun detectDocumentBounds(bitmap: Bitmap): List<PointF>? {
        val tflite = interpreter ?: return null
        val inBuf = inputBuffer ?: return null
        val outBuf = outputBuffer ?: return null

        try {
            // 1. Resize input to model's native 256x256 resolution
            val scaledBmp = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            scaledBmp.getPixels(pixelInts, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            if (scaledBmp != bitmap) {
                scaledBmp.recycle()
            }

            // 2. Normalize RGB pixels using ImageNet mean/std into native direct float buffer
            inBuf.rewind()
            for (i in 0 until (INPUT_SIZE * INPUT_SIZE)) {
                val pixel = pixelInts[i]
                val r = (((pixel shr 16) and 0xFF) / 255.0f - MEAN_R) / STD_R
                val g = (((pixel shr 8) and 0xFF) / 255.0f - MEAN_G) / STD_G
                val b = ((pixel and 0xFF) / 255.0f - MEAN_B) / STD_B
                inBuf.putFloat(r)
                inBuf.putFloat(g)
                inBuf.putFloat(b)
            }

            // 3. Execute TFLite Inference
            outBuf.rewind()
            tflite.run(inBuf, outBuf)

            // 4. Extract segmentation probability mask
            outBuf.rewind()
            outBuf.asFloatBuffer().get(outputMask)

            // 5. Evaluate document presence & determine if model output is raw logits or sigmoid probabilities
            var minVal = Float.MAX_VALUE
            var maxVal = -Float.MAX_VALUE
            for (v in outputMask) {
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
            val isLogits = minVal < -0.05f

            var docPixelCount = 0
            var sumX = 0L
            var sumY = 0L

            for (y in 0 until INPUT_SIZE) {
                val row = y * INPUT_SIZE
                for (x in 0 until INPUT_SIZE) {
                    val raw = outputMask[row + x]
                    val isDoc = if (isLogits) raw > 0f else raw > 0.5f
                    if (isDoc) {
                        docPixelCount++
                        sumX += x
                        sumY += y
                    }
                }
            }

            val totalPixels = INPUT_SIZE * INPUT_SIZE
            val docRatio = docPixelCount.toFloat() / totalPixels

            // Reject if less than 4% of screen (nothing in view) or greater than 98% (blocked lens/full screen)
            if (docRatio < 0.04f || docRatio > 0.98f) {
                return null
            }

            val centroidX = sumX.toFloat() / docPixelCount
            val centroidY = sumY.toFloat() / docPixelCount

            // 6. Radial Ray Casting on the segmented document mask:
            // Cast 64 rays outward from the document centroid to find boundary transitions
            val rayHits = mutableListOf<PointF>()
            val numRays = 64
            val angleStep = (2.0 * Math.PI / numRays)

            for (i in 0 until numRays) {
                val angle = i * angleStep
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                var lastInsideX = centroidX
                var lastInsideY = centroidY
                var foundBoundary = false

                for (r in 1..180) {
                    val px = (centroidX + r * cosA).toInt()
                    val py = (centroidY + r * sinA).toInt()

                    if (px !in 0 until INPUT_SIZE || py !in 0 until INPUT_SIZE) {
                        foundBoundary = true
                        break
                    }

                    val raw = outputMask[py * INPUT_SIZE + px]
                    val isDoc = if (isLogits) raw > 0f else raw > 0.5f

                    if (isDoc) {
                        lastInsideX = centroidX + r * cosA
                        lastInsideY = centroidY + r * sinA
                    } else {
                        foundBoundary = true
                        break
                    }
                }

                if (foundBoundary) {
                    rayHits.add(PointF(lastInsideX, lastInsideY))
                }
            }

            if (rayHits.size < 12) {
                return null
            }

            // 7. Compute Convex Hull of boundary points (Andrew's Monotone Chain)
            val hull = computeConvexHull(rayHits)
            if (hull.size < 4) {
                return null
            }

            // 8. Extract the Maximum Area Quadrilateral that satisfies realistic document geometry
            val quad = findMaxAreaQuadWithValidation(hull) ?: return null

            // 9. Order corners clockwise: [Top-Left, Top-Right, Bottom-Right, Bottom-Left]
            val ordered = BoundaryDetector.orderCorners(quad)

            // 10. Normalize coordinates to [0.0..1.0] range
            return listOf(
                PointF((ordered[0].x / INPUT_SIZE).coerceIn(0.01f, 0.99f), (ordered[0].y / INPUT_SIZE).coerceIn(0.01f, 0.99f)),
                PointF((ordered[1].x / INPUT_SIZE).coerceIn(0.01f, 0.99f), (ordered[1].y / INPUT_SIZE).coerceIn(0.01f, 0.99f)),
                PointF((ordered[2].x / INPUT_SIZE).coerceIn(0.01f, 0.99f), (ordered[2].y / INPUT_SIZE).coerceIn(0.01f, 0.99f)),
                PointF((ordered[3].x / INPUT_SIZE).coerceIn(0.01f, 0.99f), (ordered[3].y / INPUT_SIZE).coerceIn(0.01f, 0.99f))
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun computeConvexHull(points: List<PointF>): List<PointF> {
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size <= 3) return sorted

        fun crossProduct(o: PointF, a: PointF, b: PointF): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && crossProduct(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        val upper = mutableListOf<PointF>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && crossProduct(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun findMaxAreaQuadWithValidation(hull: List<PointF>): List<PointF>? {
        val n = hull.size
        if (n < 4) return null

        val pts = if (n > 24) {
            val step = n.toDouble() / 20.0
            (0 until 20).map { i -> hull[(i * step).toInt().coerceIn(0, n - 1)] }
        } else {
            hull
        }
        val m = pts.size

        var maxArea = -1.0
        var bestQuad: List<PointF>? = null

        for (i in 0 until m - 3) {
            for (j in i + 1 until m - 2) {
                for (k in j + 1 until m - 1) {
                    for (l in k + 1 until m) {
                        val p0 = pts[i]; val p1 = pts[j]; val p2 = pts[k]; val p3 = pts[l]
                        val area = BoundaryDetector.computeQuadArea(p0, p1, p2, p3).toDouble()
                        if (area > maxArea && isValidQuadGeometry(p0, p1, p2, p3)) {
                            maxArea = area
                            bestQuad = listOf(p0, p1, p2, p3)
                        }
                    }
                }
            }
        }

        return bestQuad
    }

    private fun isValidQuadGeometry(p0: PointF, p1: PointF, p2: PointF, p3: PointF): Boolean {
        val pts = arrayOf(p0, p1, p2, p3)
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]

            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y

            val len1 = hypot(v1x.toDouble(), v1y.toDouble())
            val len2 = hypot(v2x.toDouble(), v2y.toDouble())
            if (len1 < 5.0 || len2 < 5.0) return false

            val cosTheta = abs((v1x * v2x + v1y * v2y) / (len1 * len2))
            if (cosTheta > 0.65) { // Angle outside 50°..130° range
                return false
            }
        }

        val w1 = hypot((p1.x - p0.x).toDouble(), (p1.y - p0.y).toDouble())
        val w2 = hypot((p2.x - p3.x).toDouble(), (p2.y - p3.y).toDouble())
        val h1 = hypot((p3.x - p0.x).toDouble(), (p3.y - p0.y).toDouble())
        val h2 = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
        val avgW = (w1 + w2) / 2.0
        val avgH = (h1 + h2) / 2.0
        if (avgH == 0.0) return false

        val ar = avgW / avgH
        return ar in 0.30..3.10
    }
}
