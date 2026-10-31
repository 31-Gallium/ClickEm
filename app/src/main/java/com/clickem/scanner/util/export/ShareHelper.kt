package com.clickem.scanner.util.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {

    /**
     * Shares a single exported PDF or image file via the Android system share sheet.
     */
    fun shareFile(context: Context, file: File, mimeType: String, title: String = "Share Scanned Document") {
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Shares multiple files simultaneously via ACTION_SEND_MULTIPLE.
     */
    fun shareMultipleFiles(context: Context, files: List<File>, mimeType: String = "image/jpeg", title: String = "Share Pages") {
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
