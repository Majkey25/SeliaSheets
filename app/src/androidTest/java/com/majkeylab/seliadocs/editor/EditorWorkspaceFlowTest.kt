package com.majkeylab.seliadocs.editor

import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorWorkspaceFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val notebooks = mutableListOf<String>()
    private var failTrigger = false
    private fun repository() = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity))
    private fun inPane(index: Int) = hasAnyAncestor(hasTestTag(if (index == 0) "primary-editor" else "secondary-editor"))
    private fun paneNode(index: Int, tag: String) = rule.onNode(hasTestTag(tag) and inPane(index))
    private fun holder(index: Int): EditorSessionHolder = rule.runOnIdle {
        ViewModelProvider(rule.activity)[if (index == 0) "editor-session-holder" else "secondary-editor-session-holder", EditorSessionHolder::class.java]
    }
    private fun editor(index: Int): EditorViewModel = rule.runOnIdle {
        val holder = ViewModelProvider(rule.activity)[if (index == 0) "editor-session-holder" else "secondary-editor-session-holder", EditorSessionHolder::class.java]
        ViewModelProvider(holder)["editor", EditorViewModel::class.java]
    }
    private fun waitFor(matcher: SemanticsMatcher) = rule.waitUntil(10_000) {
        runCatching { rule.onNode(matcher).assertIsDisplayed() }.isSuccess
    }
    private fun assertInsideVisibleWindow(matcher: SemanticsMatcher) {
        fun checkBounds() {
            val node = rule.onNode(matcher).assertIsDisplayed().fetchSemanticsNode()
            val visible = android.graphics.Rect()
            val view = requireNotNull(node.root as? android.view.View) { "Android Compose root must expose its native View" }
            rule.runOnIdle { view.getWindowVisibleDisplayFrame(visible) }
            val position = node.positionOnScreen
            assertTrue("Control at $position size=${node.size} is outside window $visible",
                position.x >= visible.left - 1 && position.y >= visible.top - 1 &&
                    position.x + node.size.width <= visible.right + 1 && position.y + node.size.height <= visible.bottom + 1)
        }
        rule.waitUntil(5_000) { runCatching { checkBounds() }.isSuccess }
        checkBounds()
    }
    private fun assertWorkspaceChromeVisible() {
        (0..1).forEach { assertInsideVisibleWindow(hasTestTag("workspace-pane-header") and inPane(it)) }
        if (runCatching { rule.onNodeWithTag("workspace-divider").fetchSemanticsNode() }.isSuccess) {
            assertInsideVisibleWindow(hasTestTag("workspace-divider"))
        }
    }
    private fun fixture(): Pair<String, String> {
        val titles = "Workspace A ${System.nanoTime()}" to "Workspace B ${System.nanoTime()}"
        runBlocking {
            listOf(titles.first, titles.second).forEach { title ->
                notebooks += repository().createNotebook(CreateNotebookRequest(title, CoverColor.PERIWINKLE,
                    CoverPattern.SOLID, PaperTemplate.BLANK, PageOrientation.PORTRAIT, false))
            }
        }
        waitFor(hasContentDescription("Open ${titles.first}"))
        rule.onNodeWithContentDescription("Open ${titles.first}").performClick()
        waitFor(hasTestTag("editor-top-bar") and inPane(0))
        rule.waitUntil(10_000) { holder(0).selectedPage.value != null }
        return titles
    }
    private fun menu(index: Int) {
        rule.onNode(hasContentDescription("More options") and inPane(index)).performClick()
    }
    private fun openBeside(title: String) {
        menu(0)
        rule.onNodeWithTag("open-beside").performClick()
        val notebook = hasText(title) and hasAnyAncestor(hasTestTag("workspace-notebook-picker"))
        waitFor(notebook)
        rule.onNode(notebook).performScrollTo().performClick()
        waitFor(hasTestTag("secondary-editor"))
        rule.waitUntil(10_000) { holder(1).selectedPage.value != null }
        assertWorkspaceChromeVisible()
    }
    private fun type(index: Int, text: String) {
        val compact = hasTestTag("compact-tool-type") and inPane(index)
        val expanded = hasTestTag("toolbar-tool-type") and inPane(index)
        val button = if (runCatching { rule.onNode(compact).fetchSemanticsNode() }.isSuccess) compact else expanded
        rule.onNode(button).assertIsDisplayed().performClick()
        waitFor(hasTestTag("page-text") and hasSetTextAction() and inPane(index))
        paneNode(index, "page-text").assertIsEnabled().performTextInput(text)
        if (runCatching { rule.onNodeWithTag("secondary-editor").fetchSemanticsNode() }.isSuccess) assertWorkspaceChromeVisible()
    }
    private fun settings(index: Int) {
        menu(index)
        rule.onNode(hasText("Settings") and androidx.compose.ui.test.hasClickAction()).performClick()
    }

    @After fun cleanup() {
        if (failTrigger) removeFailure()
        rule.runOnUiThread {
            listOf("editor-session-holder", "secondary-editor-session-holder").forEach {
                ViewModelProvider(rule.activity)[it, EditorSessionHolder::class.java].viewModelStore.clear()
            }
        }
        runBlocking { notebooks.forEach { id ->
            val assets = repository().getPdfSources(id).map { it.assetId }
            repository().deleteNotebook(id)
            val store = com.majkeylab.seliadocs.data.AssetStore(java.io.File(rule.activity.filesDir, "assets"))
            assets.forEach { store.file(it).delete() }
        } }
    }
    private fun removeFailure() {
        runBlocking(Dispatchers.IO) { SeliaDocsDatabase.get(rule.activity).openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS qa_workspace_save_failure") }
        failTrigger = false
    }

    @Test fun workspaceSavePreservesQueuedPdfImportUntilItsCompletion() {
        val holder = EditorSessionHolder()
        holder.prepare("pending-import")
        val import = EditorAction.ImportPdf(Uri.parse("content://workspace-test/document.pdf"))
        holder.requestAction(import)
        holder.requestAction(EditorAction.WorkspaceSave(9))
        holder.beginActionSave()
        holder.completeActionSave(holder.sessionEpoch, true)
        assertEquals(import, holder.takeReadyAction())
        assertEquals(import, holder.actionState.value.executing)
        assertEquals(EditorAction.WorkspaceSave(9), holder.actionState.value.pending)
        assertEquals(null, holder.beginActionSave())
        holder.completeExecutingAction(holder.sessionEpoch, import)
        assertEquals(holder.sessionEpoch, holder.beginActionSave())
        holder.completeActionSave(holder.sessionEpoch, true)
        assertEquals(EditorAction.WorkspaceSave(9), holder.takeReadyAction())
    }

    @Test fun systemBackDismissesActiveSecondaryPdfSelectionBeforeClosingPane() {
        val titles = fixture()
        openBeside(titles.second)
        val secondary = editor(1)
        val file = java.io.File.createTempFile("workspace-back-", ".pdf", rule.activity.cacheDir)
        try {
            val document = android.graphics.pdf.PdfDocument()
            try {
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("Workspace source", 40f, 100f, android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 28f
                })
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            rule.runOnUiThread { secondary.importPdf(Uri.fromFile(file)) }
            rule.waitUntil(30_000) { secondary.state.value.selectedPage?.pdfSourceId != null }
            val pageId = requireNotNull(secondary.state.value.selectedPage).id
            rule.runOnUiThread {
                secondary.selectTool(EditorTool.LASSO)
                secondary.selectContent(pageId, listOf(CanvasPoint(35f, 65f), CanvasPoint(450f, 65f),
                    CanvasPoint(450f, 112f), CanvasPoint(35f, 112f), CanvasPoint(35f, 65f)))
            }
            rule.waitUntil(30_000) { secondary.state.value.pdfSelection != null }
            waitFor(hasTestTag("pdf-selection-bar") and inPane(1))
            androidx.test.espresso.Espresso.pressBack()
            rule.waitUntil(10_000) { secondary.state.value.pdfSelection == null && !secondary.state.value.pdfSelecting }
            rule.onNodeWithTag("secondary-editor").assertIsDisplayed()
            assertEquals(pageId, holder(1).selectedPage.value)
            androidx.test.espresso.Espresso.pressBack()
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("secondary-editor").fetchSemanticsNode() }.isFailure }
            rule.onNodeWithTag("primary-editor").assertIsDisplayed()
        } finally { file.delete() }
    }

    @Test fun independentDraftsSurviveRecreationAndSettingsSavesBoth() {
        val titles = fixture()
        val firstPage = requireNotNull(editor(0).state.value.selectedPage).id
        type(0, "Primary workspace draft")
        openBeside(titles.second)
        if (runCatching { rule.onNodeWithTag("workspace-divider").fetchSemanticsNode() }.isSuccess) {
            val workspace = rule.runOnIdle { ViewModelProvider(rule.activity)["editor-workspace", EditorWorkspaceHolder::class.java] }
            val split = workspace.split
            rule.onNodeWithTag("workspace-divider").performSemanticsAction(SemanticsActions.SetProgress) {
                assertFalse(it(Float.NaN))
            }
            assertEquals(split, workspace.split)
            rule.onNodeWithTag("workspace-divider").assertIsDisplayed()
        }
        val secondPage = requireNotNull(editor(1).state.value.selectedPage).id
        assertNotEquals(firstPage, secondPage)
        type(1, "Secondary workspace draft")
        rule.activityRule.scenario.recreate()
        waitFor(hasTestTag("page-text") and inPane(1))
        paneNode(1, "page-text").assertTextContains("Secondary workspace draft")
        assertEquals(firstPage, holder(0).selectedPage.value)
        assertEquals(secondPage, holder(1).selectedPage.value)
        settings(1)
        waitFor(hasTestTag("settings-top-bar"))
        runBlocking {
            assertEquals("Primary workspace draft", repository().getBlocks(firstPage).single().text)
            assertEquals("Secondary workspace draft", repository().getBlocks(secondPage).single().text)
        }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasTestTag("secondary-editor"))
        assertEquals(firstPage, holder(0).selectedPage.value)
        assertEquals(secondPage, holder(1).selectedPage.value)
    }

    @Test fun samePageSecondaryIsReadOnlyAndClosingItKeepsPrimary() {
        val titles = fixture()
        type(0, "One writer only")
        openBeside(titles.first)
        waitFor(hasTestTag("workspace-read-only") and inPane(1))
        rule.waitUntil(10_000) {
            runCatching { paneNode(1, "workspace-read-only").assertTextContains("Read only", substring = true) }.isSuccess
        }
        rule.onNode(hasSetTextAction() and inPane(1)).assertDoesNotExist()
        paneNode(1, "page-text").assertTextContains("One writer only")
        val primary = editor(0)
        val pageId = requireNotNull(primary.state.value.selectedPage).id
        rule.runOnUiThread { primary.updatePageText(pageId, "Updated by the owning pane") }
        rule.waitUntil(10_000) {
            runCatching { paneNode(1, "page-text").assertTextContains("Updated by the owning pane") }.isSuccess
        }
        assertEquals("Read-only DB refresh must not become a writable draft", null, holder(1).draftFor(pageId))
        rule.onNode(hasText("Close pane") and inPane(1)).performClick()
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("secondary-editor").fetchSemanticsNode() }.isFailure }
        waitFor(hasTestTag("primary-editor"))
        assertEquals(notebooks.first(), editor(0).state.value.notebook?.id)
        runBlocking { assertEquals("Updated by the owning pane", repository().getBlocks(pageId).single().text) }
    }

    @Test fun readOnlySecondaryFingerSwipeNavigatesToAnEditablePageWithoutInk() {
        val titles = fixture()
        val firstPage = requireNotNull(editor(0).state.value.selectedPage).id
        val nextPage = runBlocking { repository().addPage(notebooks.first()) }
        openBeside(titles.first)
        rule.waitUntil(10_000) {
            runCatching { paneNode(1, "workspace-read-only").assertTextContains("Read only", substring = true) }.isSuccess
        }
        paneNode(1, "page-paper").performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.35f), Offset(width * 0.2f, height * 0.35f), 300)
        }
        rule.waitUntil(10_000) { holder(1).selectedPage.value == nextPage }
        assertEquals(firstPage, holder(0).selectedPage.value)
        paneNode(1, "workspace-read-only").assertDoesNotExist()
        type(1, "Editable after leaving the shared page")
        val contents = (hasTestTag("compact-page-location") or
            (hasText("Contents") and androidx.compose.ui.test.hasClickAction())) and inPane(1)
        rule.onNode(contents).performClick()
        rule.onNode(hasTestTag("page-thumbnail") and hasText("Page 1")).performScrollTo().performClick()
        rule.waitUntil(10_000) {
            holder(1).selectedPage.value == firstPage &&
                runCatching { paneNode(1, "workspace-read-only").assertTextContains("Read only", substring = true) }.isSuccess
        }
        assertEquals(EditorTool.TYPE, editor(1).state.value.tool)
        paneNode(1, "page-paper").performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.35f), Offset(width * 0.2f, height * 0.35f), 300)
        }
        rule.waitUntil(10_000) { holder(1).selectedPage.value == nextPage }
        waitFor(hasTestTag("page-text") and hasSetTextAction() and inPane(1))
        assertEquals(EditorTool.TYPE, editor(1).state.value.tool)
        settings(1)
        waitFor(hasTestTag("settings-top-bar"))
        runBlocking {
            assertEquals(emptyList<com.majkeylab.seliadocs.data.StrokeEntity>(), repository().getStrokes(firstPage))
            assertEquals(emptyList<com.majkeylab.seliadocs.data.StrokeEntity>(), repository().getStrokes(nextPage))
            assertEquals("Editable after leaving the shared page", repository().getBlocks(nextPage).single().text)
        }
    }

    @Test fun failedSecondarySaveKeepsBothPanesAndRetryFinishesSettings() {
        val titles = fixture()
        openBeside(titles.second)
        val secondPage = requireNotNull(editor(1).state.value.selectedPage).id
        runBlocking(Dispatchers.IO) {
            SeliaDocsDatabase.get(rule.activity).openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER qa_workspace_save_failure BEFORE INSERT ON blocks " +
                    "WHEN NEW.pageId = '$secondPage' BEGIN SELECT RAISE(ABORT, 'Forced workspace failure'); END",
            )
        }
        failTrigger = true
        val probe = runBlocking { runCatching { repository().updatePageText(secondPage, "Failure trigger probe") } }
        assertTrue("The SQLite failure trigger did not reject an actual repository write: $probe", probe.isFailure)
        type(1, "Draft survives failed workspace save")
        settings(1)
        waitFor(hasTestTag("workspace-save-failed"))
        assertInsideVisibleWindow(hasTestTag("workspace-retry"))
        rule.onNodeWithTag("settings-top-bar").assertDoesNotExist()
        paneNode(1, "page-text").assertTextContains("Draft survives failed workspace save")
        assertEquals(notebooks.first(), editor(0).state.value.notebook?.id)
        assertEquals(notebooks.last(), editor(1).state.value.notebook?.id)
        rule.activityRule.scenario.recreate()
        waitFor(hasTestTag("workspace-save-failed"))
        assertWorkspaceChromeVisible()
        assertInsideVisibleWindow(hasTestTag("workspace-retry"))
        paneNode(1, "page-text").assertIsDisplayed().assertIsEnabled()
            .assertTextContains("Draft survives failed workspace save").performTextInput(" after recreation")
        removeFailure()
        rule.onNodeWithTag("workspace-retry").performClick()
        waitFor(hasTestTag("settings-top-bar"))
        runBlocking { assertEquals("Draft survives failed workspace save after recreation", repository().getBlocks(secondPage).single().text) }
    }
}
