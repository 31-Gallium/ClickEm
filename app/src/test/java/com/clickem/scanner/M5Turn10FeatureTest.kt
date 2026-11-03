package com.clickem.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verification test suite for Turn 10 & 11 features:
 * 1. Dedicated inline Undo buttons in Reorder mode and Move panel.
 * 2. Multi-selection in Reorder mode (click/tap cards to toggle multiple pages).
 * 3. Selection persistence holding to the correct images during reorder and undo.
 * 4. Camera deactivation when covered by overlays to conserve battery/CPU.
 * 5. Smooth navigation between Loadout and Project review without camera flashing.
 * 6. Snapping and appending new pages into an open loadout session.
 */
class M5Turn10FeatureTest {

    private fun resolveFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot resolve path: $relativePath from working dir: ${File(".").absolutePath}")
    }

    @Test
    fun testReorderAndMoveInlineUndoButtons() {
        val batchReviewFile = resolveFile("src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt")
        val content = batchReviewFile.readText()

        // 1. Move panel must have inline undo button with ic_undo
        assertTrue("BatchReviewView must have btnMoveUndo reference", content.contains("btnMoveUndo"))
        assertTrue("Move panel must instantiate an Undo button with ic_undo", content.contains("R.drawable.ic_undo"))
        assertTrue("Move history stack must track page order & selection snapshot", content.contains("val moveHistory = mutableListOf<PageOrderSnapshot>()"))

        // 2. Reorder canvas must have inline undo button in header
        assertTrue("Reorder canvas must have btnReorderUndo", content.contains("btnReorderUndo"))
        assertTrue("Reorder history stack must track page order & selection snapshot", content.contains("val reorderHistory = mutableListOf<PageOrderSnapshot>()"))

        // 3. Dragging when move panel is active must NOT trigger overlapping showUndoBar
        assertTrue("Dragging while move panel is active must register in moveHistory instead of popup bar",
            content.contains("if (::moveOverlayBar.isInitialized && moveOverlayBar.visibility == View.VISIBLE) {\n                            val preIds = preDragSelected.filter { it in preDragPages.indices }.map { preDragPages[it].id }.toSet()")
        )
    }

    @Test
    fun testReorderModeMultiSelectAndUndoSelectionPreservation() {
        val batchReviewFile = resolveFile("src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt")
        val content = batchReviewFile.readText()

        // 1. PageOrderSnapshot data model stores both pages and selectedPageIds
        assertTrue("PageOrderSnapshot must store pages and selectedPageIds",
            content.contains("data class PageOrderSnapshot(\n        val pages: List<ScannedPage>,\n        val selectedPageIds: Set<String>\n    )")
        )

        // 2. ReorderGridAdapter must support multi-select via selectedIds
        assertTrue("ReorderGridAdapter must take selectedIds MutableSet",
            content.contains("class ReorderGridAdapter(\n        val items: MutableList<ScannedPage>,\n        val selectedIds: MutableSet<String>")
        )

        // 3. Clicking cards in reorder mode must toggle selection
        assertTrue("Clicking a card in reorder mode must toggle selection in selectedIds",
            content.contains("if (selectedIds.contains(p.id)) {\n                        selectedIds.remove(p.id)\n                    } else {\n                        selectedIds.add(p.id)\n                    }")
        )

        // 4. Selected card visual check badge
        assertTrue("Selected card must show checkmark indicator",
            content.contains("holder.ivCheckBadge.setImageResource(R.drawable.ic_check)")
        )

        // 5. Undo in move panel must restore selection to the exact same images by ID
        assertTrue("Move panel Undo must restore selection by page ID to keep holding to correct images",
            content.contains("for (i in pages.indices) {\n                        if (pages[i].id in snapshot.selectedPageIds) {\n                            adapter.selectedPositions.add(i)\n                        }\n                    }")
        )

        // 6. Undo in reorder canvas must restore selection to the exact same images by ID
        assertTrue("Reorder canvas Undo must restore selectedIds from snapshot",
            content.contains("selectedIds.clear()\n                    selectedIds.addAll(snapshot.selectedPageIds)")
        )

        // 7. Done button in reorder canvas must map selectedIds back to adapter.selectedPositions
        assertTrue("Reorder canvas Done must sync selection back to main review adapter by page ID",
            content.contains("adapter.selectedPositions.clear()\n                for (i in pages.indices) {\n                    if (pages[i].id in selectedIds) {\n                        adapter.selectedPositions.add(i)\n                    }\n                }")
        )
    }

    @Test
    fun testReorderHoverDropTargetIndicator() {
        val batchReviewFile = resolveFile("src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt")
        val content = batchReviewFile.readText()

        // 1. ReorderGridAdapter ViewHolder must include dropTargetOverlay and tvDropIndicator
        assertTrue("ViewHolder must contain dropTargetOverlay FrameLayout",
            content.contains("val dropTargetOverlay: FrameLayout"))
        assertTrue("ViewHolder must contain tvDropIndicator TextView",
            content.contains("val tvDropIndicator: TextView"))

        // 2. Hover state sets prominent drop indicator text and scale
        assertTrue("setHoverState must configure drop indicator text",
            content.contains("tvDropIndicator.text = if (count > 1)"))
        assertTrue("setHoverState must scale card when hovered",
            content.contains("card.animate().scaleX(1.05f).scaleY(1.05f)"))

        // 3. onChildDraw must detect hovered view in real time
        assertTrue("onChildDraw must find hovered view under drag coordinates",
            content.contains("recyclerView.findChildViewUnder(centerX, centerY)"))

        // 4. Subtitle updates to show placement position
        assertTrue("Canvas subtitle must dynamically indicate placement position",
            content.contains("Placing at position \${to + 1} of \${reorderPages.size}"))

        // 5. Drop target overlay must use high-visibility frost stroke
        assertTrue("dropTargetOverlay must use 3dp pure white stroke",
            content.contains("setStroke((3 * density).toInt(), Color.parseColor(\"#FFFFFF\"))"))
    }

    @Test
    fun testCameraDeactivationWhenNotOnCameraView() {
        val cameraViewFile = resolveFile("src/main/java/com/clickem/scanner/ui/camera/CameraView.kt")
        val content = cameraViewFile.readText()

        // Verify activateCamera and deactivateCamera exist and manage lifecycle and preview
        assertTrue("CameraView must define activateCamera()", content.contains("fun activateCamera()"))
        assertTrue("CameraView must define deactivateCamera()", content.contains("fun deactivateCamera()"))
        assertTrue("deactivateCamera must unbind CameraX to save CPU", content.contains("cameraProvider?.unbindAll()"))
        assertTrue("deactivateCamera must make preview invisible", content.contains("previewView.visibility = View.INVISIBLE"))
        assertTrue("activateCamera must make preview visible", content.contains("previewView.visibility = View.VISIBLE"))
        assertTrue("setIsCoveredByOverlay must deactivate camera when covered",
            content.contains("if (covered && !isRetakeModeActive()) {\n                deactivateCamera()")
        )
    }

    @Test
    fun testNavigationLogicPreventsCameraFlash() {
        val mainActivityFile = resolveFile("src/main/java/com/clickem/scanner/MainActivity.kt")
        val content = mainActivityFile.readText()

        // 1. pushOverlay must keep currentTop visible during animation, hiding it only on completion
        assertTrue("pushOverlay must keep previous view visible until entry animation completes",
            content.contains("withEndAction {\n                if (currentTop != null && currentTop != overlayBackStack.peek()) {\n                    currentTop.visibility = View.GONE")
        )

        // 2. popOverlay must restore previousView immediately before exit animation begins
        assertTrue("popOverlay must make previousView VISIBLE immediately so it appears under exiting view",
            content.contains("val previousView = overlayBackStack.peek()\n        if (previousView != null && cameraView?.isRetakeModeActive() != true) {\n            previousView.visibility = View.VISIBLE")
        )

        // 3. showBatchReview onLoadoutRequested must seamlessly transition without camera flash
        assertFalse("showBatchReview must not call popOverlay() before showLoadout()",
            content.contains("popOverlay()\n                showLoadout()")
        )
    }

    @Test
    fun testAppendNewPagesToExistingLoadout() {
        val mainActivityFile = resolveFile("src/main/java/com/clickem/scanner/MainActivity.kt")
        val content = mainActivityFile.readText()

        // 1. MainActivity must have pending append batch tracking
        assertTrue("MainActivity must track pendingAppendBatchView", content.contains("private var pendingAppendBatchView: BatchReviewView? = null"))
        assertTrue("MainActivity must implement openCameraToAppendPages", content.contains("private fun openCameraToAppendPages("))

        // 2. openProjectFromLoadout must hook up onSnapNewPagesRequested
        assertTrue("openProjectFromLoadout must wire onSnapNewPagesRequested to openCameraToAppendPages",
            content.contains("onSnapNewPagesRequested = {\n                openCameraToAppendPages(batchView, project)\n            }")
        )

        // 3. onDoneRequested must append to pendingAppendBatchView
        assertTrue("onDoneRequested must append newly snapped pages to pending batch",
            content.contains("appendingBv.pages.addAll(pages)\n                    appendingBv.notifyPagesUpdated()")
        )

        // 4. Back press handling must handle pending append state
        assertTrue("Back press must restore pending append batch view if user cancels snapping",
            content.contains("val appendingBv = pendingAppendBatchView\n                if (appendingBv != null) {")
        )
    }

    @Test
    fun testNoLegacyCyanHexes() {
        val filesToCheck = listOf(
            resolveFile("src/main/java/com/clickem/scanner/MainActivity.kt"),
            resolveFile("src/main/java/com/clickem/scanner/ui/batch/BatchReviewView.kt"),
            resolveFile("src/main/java/com/clickem/scanner/ui/camera/CameraView.kt")
        )
        val bannedHexes = listOf("38bdf8", "0284c7", "0ea5e9", "38BDF8")
        for (file in filesToCheck) {
            val content = file.readText().lowercase()
            for (banned in bannedHexes) {
                assertFalse("File ${file.name} must not contain cyan hex $banned", content.contains(banned.lowercase()))
            }
        }
    }
}
