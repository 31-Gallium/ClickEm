package com.clickem.scanner.util.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.clickem.scanner.model.DocumentProject
import com.clickem.scanner.model.FilterMode
import com.clickem.scanner.model.ScannedPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class ScanStorageManager(private val context: Context) {

    private val projectsFile: File
        get() = File(context.filesDir, "clickem_projects.json")

    val scansDir: File
        get() = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }

    val thumbsDir: File
        get() = File(context.filesDir, "thumbs").apply { if (!exists()) mkdirs() }

    val exportsDir: File
        get() = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }

    /**
     * Saves a captured Bitmap to disk, generates a downscaled thumbnail proxy,
     * and returns a ScannedPage object.
     */
    fun saveCapturedImage(bitmap: Bitmap, rotationDegrees: Int = 0): ScannedPage {
        val pageId = UUID.randomUUID().toString()
        val originalFile = File(scansDir, "scan_$pageId.jpg")
        val thumbFile = File(thumbsDir, "thumb_$pageId.jpg")

        // Apply orientation matrix if rotation is present
        val orientedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // 1. Save full-resolution JPEG with native clarity
        FileOutputStream(originalFile).use { fos ->
            orientedBitmap.compress(Bitmap.CompressFormat.JPEG, 98, fos)
        }

        // 2. Generate and save downscaled thumbnail proxy (max 480px width)
        val thumbW = 480
        val thumbH = (thumbW.toFloat() / orientedBitmap.width * orientedBitmap.height).toInt().coerceAtLeast(100)
        val thumbBitmap = Bitmap.createScaledBitmap(orientedBitmap, thumbW, thumbH, true)

        FileOutputStream(thumbFile).use { fos ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
        thumbBitmap.recycle()

        if (orientedBitmap != bitmap) {
            orientedBitmap.recycle()
        }

        return ScannedPage(
            id = pageId,
            originalImagePath = originalFile.absolutePath,
            thumbnailPath = thumbFile.absolutePath,
            rotationDegrees = 0
        )
    }

    /**
     * Loads a Bitmap from file path safely with downsampling if required.
     */
    fun loadBitmap(path: String, maxDimension: Int = 4096): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)

        var sampleSize = 1
        while (options.outWidth / sampleSize > maxDimension || options.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, decodeOptions)
    }

    /**
     * Regenerates and overwrites the thumbnail image on disk with the user's
     * latest cropQuad, rotationDegrees, and filterMode applied.
     */
    fun regeneratePageThumbnail(page: ScannedPage) {
        try {
            val src = loadBitmap(page.originalImagePath, maxDimension = 1400) ?: return
            val warped = com.clickem.scanner.util.cv.PerspectiveWarper.warpQuad(src, page.cropQuad, page.rotationDegrees)
            val filtered = com.clickem.scanner.util.cv.DocumentFilterEngine.applyFilter(warped, page.filterMode, page.filterIntensity)

            val thumbFile = if (page.thumbnailPath.isNotBlank() && !page.thumbnailPath.equals(page.originalImagePath, ignoreCase = true)) {
                val f = File(page.thumbnailPath)
                f.parentFile?.mkdirs()
                f
            } else {
                val f = File(thumbsDir, "thumb_${page.id}.jpg")
                page.thumbnailPath = f.absolutePath
                f
            }
            val thumbW = 480
            val thumbH = (thumbW.toFloat() / filtered.width * filtered.height).toInt().coerceAtLeast(100)
            val thumbBmp = Bitmap.createScaledBitmap(filtered, thumbW, thumbH, true)
            FileOutputStream(thumbFile).use { fos ->
                thumbBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            thumbBmp.recycle()

            if (src != warped) src.recycle()
            if (warped != filtered) warped.recycle()
            filtered.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Saves or updates a DocumentProject to the Loadout database.
     */
    fun saveProject(project: DocumentProject) {
        for (page in project.pages) {
            regeneratePageThumbnail(page)
        }

        val allProjects = getAllProjects().toMutableList()
        val existingIndex = allProjects.indexOfFirst { it.id == project.id }
        project.updatedAt = System.currentTimeMillis()

        if (existingIndex >= 0) {
            allProjects[existingIndex] = project
        } else {
            allProjects.add(0, project)
        }
        writeProjectsJson(allProjects)
    }

    /**
     * Returns all saved DocumentProjects for the Loadout screen.
     */
    fun getAllProjects(): List<DocumentProject> {
        if (!projectsFile.exists()) return emptyList()
        try {
            val jsonStr = projectsFile.readText()
            val jsonArr = JSONArray(jsonStr)
            val list = mutableListOf<DocumentProject>()

            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val proj = DocumentProject(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    createdAt = obj.getLong("createdAt"),
                    updatedAt = obj.getLong("updatedAt"),
                    category = obj.optString("category", "Documents"),
                    exportedPdfPath = if (obj.has("exportedPdfPath") && !obj.isNull("exportedPdfPath")) obj.getString("exportedPdfPath") else null,
                    exportedElongatedPath = if (obj.has("exportedElongatedPath") && !obj.isNull("exportedElongatedPath")) obj.getString("exportedElongatedPath") else null
                )

                val pagesArr = obj.getJSONArray("pages")
                for (p in 0 until pagesArr.length()) {
                    val pObj = pagesArr.getJSONObject(p)
                    val cropQuad = mutableListOf<PointF>()
                    if (pObj.has("cropQuad")) {
                        val quadArr = pObj.getJSONArray("cropQuad")
                        for (q in 0 until quadArr.length()) {
                            val qObj = quadArr.getJSONObject(q)
                            cropQuad.add(PointF(qObj.getDouble("x").toFloat(), qObj.getDouble("y").toFloat()))
                        }
                    }
                    if (cropQuad.size != 4) {
                        cropQuad.clear()
                        cropQuad.addAll(listOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f)))
                    }

                    val filterModeStr = pObj.optString("filterMode", FilterMode.ORIGINAL.name)
                    val filterMode = try {
                        FilterMode.valueOf(filterModeStr)
                    } catch (e: Exception) {
                        FilterMode.ORIGINAL
                    }
                    val filterIntensity = pObj.optDouble("filterIntensity", 1.0).toFloat().coerceIn(0f, 1f)

                    proj.pages.add(
                        ScannedPage(
                            id = pObj.getString("id"),
                            originalImagePath = pObj.getString("originalImagePath"),
                            thumbnailPath = pObj.optString("thumbnailPath", pObj.getString("originalImagePath")),
                            cropQuad = cropQuad,
                            rotationDegrees = pObj.optInt("rotationDegrees", 0),
                            filterMode = filterMode,
                            filterIntensity = filterIntensity
                        )
                    )
                }
                list.add(proj)
            }
            return list
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Deletes a project and its image files from disk.
     */
    fun deleteProject(projectId: String) {
        val allProjects = getAllProjects()
        val target = allProjects.find { it.id == projectId }

        if (target != null) {
            for (page in target.pages) {
                try {
                    File(page.originalImagePath).takeIf { it.exists() }?.delete()
                    File(page.thumbnailPath).takeIf { it.exists() }?.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            target.exportedPdfPath?.let { path ->
                try { File(path).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
            }
            target.exportedElongatedPath?.let { path ->
                try { File(path).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
            }
        }

        val remaining = allProjects.filterNot { it.id == projectId }
        writeProjectsJson(remaining)
    }

    /**
     * Copies an exported file to public Downloads or Documents via MediaStore so it is accessible in file managers.
     */
    fun exportToPublicStorage(file: File, mimeType: String, displayName: String): Uri? {
        if (!file.exists()) return null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClickEm")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(file).use { input ->
                            input.copyTo(out)
                        }
                    }
                    uri
                } else null
            } else {
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClickEm").apply {
                    if (!exists()) mkdirs()
                }
                val destFile = File(publicDir, displayName)
                FileInputStream(file).use { input ->
                    FileOutputStream(destFile).use { out ->
                        input.copyTo(out)
                    }
                }
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun writeProjectsJson(projects: List<DocumentProject>) {
        val jsonArr = JSONArray()
        for (proj in projects) {
            val pObj = JSONObject().apply {
                put("id", proj.id)
                put("title", proj.title)
                put("createdAt", proj.createdAt)
                put("updatedAt", proj.updatedAt)
                put("category", proj.category)
                put("exportedPdfPath", proj.exportedPdfPath)
                put("exportedElongatedPath", proj.exportedElongatedPath)

                val pagesArr = JSONArray()
                for (page in proj.pages) {
                    val pg = JSONObject().apply {
                        put("id", page.id)
                        put("originalImagePath", page.originalImagePath)
                        put("thumbnailPath", page.thumbnailPath)
                        put("rotationDegrees", page.rotationDegrees)
                        put("filterMode", page.filterMode.name)
                        put("filterIntensity", page.filterIntensity.toDouble())

                        val quadArr = JSONArray()
                        for (pt in page.cropQuad) {
                            val ptObj = JSONObject().apply {
                                put("x", pt.x)
                                put("y", pt.y)
                            }
                            quadArr.put(ptObj)
                        }
                        put("cropQuad", quadArr)
                    }
                    pagesArr.put(pg)
                }
                put("pages", pagesArr)
            }
            jsonArr.put(pObj)
        }
        projectsFile.writeText(jsonArr.toString())
    }
}
