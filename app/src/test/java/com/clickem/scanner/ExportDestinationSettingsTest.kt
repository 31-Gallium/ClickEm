package com.clickem.scanner

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExportDestinationSettingsTest {

    @Test
    fun testSettingsManagerExportFolderKeysAndMethods() {
        val file = File("src/main/java/com/clickem/scanner/util/settings/SettingsManager.kt")
        assertTrue("SettingsManager.kt must exist", file.exists())
        val code = file.readText()

        assertTrue("Must define exportFolderUri property", code.contains("var exportFolderUri: String?"))
        assertTrue("Must define exportFolderPath property", code.contains("var exportFolderPath: String"))
        assertTrue("Must define resetExportFolder method", code.contains("fun resetExportFolder()"))
        assertTrue("Must define KEY_EXPORT_FOLDER_URI", code.contains("KEY_EXPORT_FOLDER_URI"))
        assertTrue("Must define KEY_EXPORT_FOLDER_PATH", code.contains("KEY_EXPORT_FOLDER_PATH"))
    }

    @Test
    fun testSettingsViewExportFolderPickerWiring() {
        val file = File("src/main/java/com/clickem/scanner/ui/settings/SettingsView.kt")
        assertTrue("SettingsView.kt must exist", file.exists())
        val code = file.readText()

        assertTrue("SettingsView must accept onPickExportFolder callback", code.contains("onPickExportFolder: (() -> Unit)?"))
        assertTrue("SettingsView must provide updateExportLocation method", code.contains("fun updateExportLocation(displayPath: String)"))
        assertTrue("SettingsView must have Browse button", code.contains("btnBrowseStorage"))
        assertTrue("SettingsView must have Reset button", code.contains("btnResetStorage"))
        assertTrue("SettingsView must have accessibility description for Browse button", code.contains("contentDescription = \"Browse and select export folder\""))
    }

    @Test
    fun testScanStorageManagerSafExportSupport() {
        val file = File("src/main/java/com/clickem/scanner/util/storage/ScanStorageManager.kt")
        assertTrue("ScanStorageManager.kt must exist", file.exists())
        val code = file.readText()

        assertTrue("ScanStorageManager must check exportFolderUri", code.contains("settingsManager.exportFolderUri"))
        assertTrue("ScanStorageManager must use DocumentsContract for tree URIs", code.contains("DocumentsContract.buildDocumentUriUsingTree"))
        assertTrue("ScanStorageManager must use DocumentsContract.createDocument", code.contains("DocumentsContract.createDocument"))
    }
}
