package com.clickem.scanner

import android.app.Application
import com.clickem.scanner.util.storage.ScanStorageManager

class ClickEmApplication : Application() {

    lateinit var storageManager: ScanStorageManager
        private set

    override fun onCreate() {
        super.onCreate()
        storageManager = ScanStorageManager(this)
        com.clickem.scanner.ui.edit.SinglePageEditView.prewarmFilterThumbnails()
    }
}
