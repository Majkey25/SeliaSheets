package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorWorkspaceStateTest {
    @Test
    fun closedPaneCannotKeepAReadOnlyPageLockOrOldSaveResult() {
        val holder = EditorSessionHolder()
        holder.prepare("book")
        holder.selectedPage.value = "shared-page"
        holder.finishWorkspaceSave(7)
        holder.beginClose(EditorCloseIntent.BACK)
        holder.completeClose(true)
        holder.consumeCompletedClose()
        assertNull(holder.selectedPage.value)
        assertNull(holder.workspaceSaveResult.value)
    }

    @Test
    fun sharedPageWaitsForBusyOperationAndDoesNotAutomaticallyRetryFailure() {
        val workspace = EditorWorkspaceHolder()
        workspace.prepare("library:first", "first", "page-one")
        workspace.request(WorkspaceChange.PRIMARY, WorkspacePane("first"))
        val first = workspace.pending
        workspace.synchronizeSharedPage("shared-page")
        assertEquals(first, workspace.pending)
        workspace.finish(true)
        workspace.synchronizeSharedPage("shared-page")
        assertEquals(WorkspaceChange.SHARED_PAGE, workspace.pending?.change)
        workspace.finish(false)
        val failure = workspace.failedRequest
        workspace.synchronizeSharedPage("shared-page")
        assertNull(workspace.pending)
        assertEquals(failure, workspace.failedRequest)
        workspace.request(WorkspaceChange.SHARED_PAGE)
        workspace.sharedPageSaved = "shared-page"
        workspace.finish(true)
        workspace.synchronizeSharedPage("shared-page")
        assertNull(workspace.pending)
        workspace.synchronizeSharedPage(null)
        assertNull(workspace.sharedPageSaved)
    }

    @Test
    fun newNotebookSessionsCannotReuseAnOldWorkspaceResultId() {
        val workspace = EditorWorkspaceHolder()
        workspace.prepare("first", "first", null)
        workspace.request(WorkspaceChange.SETTINGS)
        val firstId = requireNotNull(workspace.pending).id
        val pane = EditorSessionHolder()
        pane.prepare("first")
        pane.finishWorkspaceSave(firstId)
        workspace.prepare("second", "second", null)
        workspace.request(WorkspaceChange.SETTINGS)
        assertEquals(firstId + 1, requireNotNull(workspace.pending).id)
        pane.prepare("second")
        assertNull(pane.workspaceSaveResult.value)
    }

    @Test
    fun workspaceSaveWaitsForQueuedPageMutationToExecute() {
        val holder = EditorSessionHolder()
        holder.prepare("notebook")
        holder.requestAction(EditorAction.AddPage)
        holder.requestAction(EditorAction.WorkspaceSave(8))
        holder.beginActionSave()
        holder.completeActionSave(holder.sessionEpoch, true)
        assertEquals(EditorAction.AddPage, holder.takeReadyAction())
        assertEquals(EditorAction.WorkspaceSave(8), holder.actionState.value.pending)
    }

    @Test
    fun workspaceSaveCannotDiscardPendingUndoAndReportsFailure() {
        listOf(true, false).forEach { saved ->
            val holder = EditorSessionHolder()
            holder.prepare("notebook")
            holder.requestAction(EditorAction.Undo)
            holder.beginActionSave()
            holder.requestAction(EditorAction.WorkspaceSave(7))
            holder.requestAction(EditorAction.NextPage)
            assertEquals(EditorAction.Undo, holder.actionState.value.pending)
            holder.completeActionSave(holder.sessionEpoch, saved)
            if (saved) {
                assertEquals(EditorAction.Undo, holder.takeReadyAction())
                assertEquals(EditorAction.WorkspaceSave(7), holder.actionState.value.pending)
                holder.requestAction(EditorAction.NextPage)
                holder.beginActionSave()
                holder.completeActionSave(holder.sessionEpoch, true)
                assertEquals(EditorAction.WorkspaceSave(7), holder.takeReadyAction())
                holder.finishWorkspaceSave(7)
                assertEquals(7L to true, holder.workspaceSaveResult.value)
            } else {
                assertEquals(7L to false, holder.workspaceSaveResult.value)
                assertNull(holder.actionState.value.pending)
            }
        }
    }

    @Test
    fun failedReplaceRetainsBothPanesAndItsRetryTarget() {
        val workspace = EditorWorkspaceHolder()
        workspace.prepare("library:first", "first", "page-one")
        workspace.secondary = WorkspacePane("second", "page-two")
        workspace.request(WorkspaceChange.SECONDARY, WorkspacePane("third"))
        val pending = workspace.pending
        workspace.prepare("library:first", "first", null)
        assertEquals(pending, workspace.pending)
        workspace.finish(false)
        assertEquals(WorkspacePane("first", "page-one"), workspace.primary)
        assertEquals(WorkspacePane("second", "page-two"), workspace.secondary)
        assertEquals(pending, workspace.failedRequest)
        workspace.request(WorkspaceChange.SECONDARY, workspace.failedRequest?.target)
        assertEquals(WorkspacePane("third"), workspace.pending?.target)
    }

    @Test
    fun viewportHistoryIsIndependentAndBounded() {
        val first = EditorSessionHolder()
        val second = EditorSessionHolder()
        repeat(17) { index -> first.rememberViewport("$index", PageViewport(2f, index.toFloat(), 3f)) }
        second.rememberViewport("16", PageViewport(3f))
        assertEquals(PageViewport(), first.viewportFor("0"))
        assertEquals(PageViewport(2f, 16f, 3f), first.viewportFor("16"))
        assertEquals(PageViewport(3f), second.viewportFor("16"))
    }
}
