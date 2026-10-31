package com.clickem.scanner.model

sealed class CaptureAction {
    data class AddPage(val page: ScannedPage, val index: Int) : CaptureAction()
    data class DeletePage(val page: ScannedPage, val index: Int) : CaptureAction()
    data class UpdatePage(val oldPage: ScannedPage, val newPage: ScannedPage, val index: Int) : CaptureAction()
}
