package com.clickem.scanner.model

import java.io.Serializable
import java.util.UUID

data class DocumentProject(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
    var pages: MutableList<ScannedPage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var category: String = "Documents",
    var exportedPdfPath: String? = null,
    var exportedElongatedPath: String? = null
) : Serializable
