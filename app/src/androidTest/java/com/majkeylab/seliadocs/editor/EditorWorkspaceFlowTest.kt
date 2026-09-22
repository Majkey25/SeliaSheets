package com.majkeylab.seliadocs.editor

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.documents.WordTextCodec
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import com.majkeylab.seliadocs.pdf.PdfTextSearcher
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
        fun checkBounds(): Triple<Offset, IntSize, android.graphics.Rect> {
            val node = rule.onNode(matcher).assertIsDisplayed().fetchSemanticsNode()
            val view = requireNotNull(node.root as? android.view.View) { "Android Compose root must expose its native View" }
            val (position, size, visible) = rule.runOnIdle {
                val visible = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(visible)
                Triple(node.positionOnScreen, node.size, visible)
            }
            assertTrue("Control at $position size=$size is outside window $visible",
                position.x >= visible.left - 1 && position.y >= visible.top - 1 &&
                    position.x + size.width <= visible.right + 1 && position.y + size.height <= visible.bottom + 1)
            return Triple(position, size, visible)
        }
        var previous: Triple<Offset, IntSize, android.graphics.Rect>? = null
        var stableSince = 0L
        var lastFailure: Throwable? = null
        try {
            rule.waitUntil(5_000) {
                val sample = runCatching { checkBounds() }.getOrElse {
                    lastFailure = it
                    previous = null
                    return@waitUntil false
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (sample != previous) { previous = sample; stableSince = now }
                now - stableSince >= 250L
            }
        } catch (failure: Throwable) {
            lastFailure?.let(failure::addSuppressed)
            val tree = runCatching { rule.onAllNodes(isRoot()).printToString(12) }
                .getOrElse { "Semantics unavailable: ${it.message}" }
            val details = "Control=$matcher lastBounds=$previous lastFailure=${lastFailure?.message}\n$tree"
            android.util.Log.e("WorkspaceChromeQA", details)
            runCatching {
                val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    java.io.File(rule.activity.getExternalFilesDir(null), "workspace-chrome-failed.png").outputStream().use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("Workspace chrome did not settle inside its visible window. $details", failure)
        }
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
        val button = hasContentDescription("More options") and inPane(index)
        rule.waitUntil(10_000) {
            runCatching { rule.onNode(button).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        assertInsideVisibleWindow(button)
        rule.onNode(button).assertIsEnabled().performClick()
    }
    private fun openBeside(title: String) {
        menu(0)
        var stage = "waiting for Open beside"
        try {
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("open-beside").assertIsDisplayed().assertIsEnabled() }.isSuccess
            }
            assertInsideVisibleWindow(hasTestTag("open-beside"))
            rule.onNodeWithTag("open-beside").assertIsEnabled().performClick()
            stage = "waiting for notebook picker"
            val picker = hasTestTag("workspace-notebook-picker")
            waitFor(picker)
            stage = "scrolling to notebook $title"
            val inPicker = hasAnyAncestor(picker)
            val notebook = hasText(title) and inPicker
            val list = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and inPicker
            rule.waitUntil(10_000) {
                runCatching {
                    rule.onNode(list).fetchSemanticsNode().children.any { SemanticsActions.OnClick in it.config }
                }.getOrDefault(false)
            }
            rule.onNode(list).performScrollToNode(hasText(title))
            rule.onNode(notebook).assertIsDisplayed().assertIsEnabled().performClick()
        } catch (failure: Throwable) {
            val details = runCatching {
                val workspace = rule.runOnIdle { ViewModelProvider(rule.activity)["editor-workspace", EditorWorkspaceHolder::class.java] }
                "pickerPane=${workspace.pickerPane} pending=${workspace.pending} failed=${workspace.failed}\n" +
                    rule.onAllNodes(isRoot()).printToString(12)
            }.getOrElse { "Picker diagnostics unavailable: ${it.message}" }
            android.util.Log.e("WorkspacePickerQA", "$stage\n$details")
            throw AssertionError("Open beside failed while $stage\n$details", failure)
        }
        waitFor(hasTestTag("secondary-editor"))
        rule.waitUntil(10_000) { holder(1).selectedPage.value != null }
        assertWorkspaceChromeVisible()
    }
    private fun type(index: Int, text: String, waitForKeyboard: Boolean = false) {
        val compact = hasTestTag("compact-tool-type") and inPane(index)
        val expanded = hasTestTag("toolbar-tool-type") and inPane(index)
        val button = if (runCatching { rule.onNode(compact).fetchSemanticsNode() }.isSuccess) compact else expanded
        rule.onNode(button).assertIsDisplayed().performClick()
        waitFor(hasTestTag("page-text") and hasSetTextAction() and inPane(index))
        paneNode(index, "page-text").assertIsEnabled().performTextInput(text)
        if (waitForKeyboard) {
            val view = requireNotNull(paneNode(index, "page-text").fetchSemanticsNode().root as? android.view.View)
            rule.waitUntil(10_000) {
                rule.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            }
        }
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
        waitFor(hasTestTag("editor-top-bar") and inPane(0))
        paneNode(0, "workspace-read-only").assertDoesNotExist()
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
        type(1, "Draft survives failed workspace save", waitForKeyboard = true)
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

    @Test fun notebookExportsFlushOtherPaneAndRetainFailedWordDestination() {
        val titles = fixture()
        val firstPage = requireNotNull(editor(0).state.value.selectedPage).id
        val secondPage = runBlocking { repository().addPage(notebooks.first()) }
        rule.waitUntil(10_000) { editor(0).state.value.pages.size == 2 }
        openBeside(titles.first)
        val secondary = editor(1)
        rule.runOnUiThread { secondary.selectPage(secondPage) }
        rule.waitUntil(10_000) { holder(1).selectedPage.value == secondPage }
        var workspace = rule.runOnIdle { ViewModelProvider(rule.activity)["editor-workspace", EditorWorkspaceHolder::class.java] }
        rule.waitUntil(10_000) { workspace.pending == null }
        val secondHolder = holder(1)
        val unsaved = InlineTextDraft(secondPage, null, CanvasPoint(48f, 100f), "Unsaved secondary Word text")
        rule.runOnUiThread { assertTrue(secondHolder.beginInlineText(unsaved)) }
        runBlocking { assertTrue(repository().getElements(secondPage).isEmpty()) }
        runBlocking(Dispatchers.IO) {
            SeliaDocsDatabase.get(rule.activity).openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER qa_workspace_save_failure BEFORE INSERT ON elements " +
                    "WHEN NEW.pageId = '$secondPage' BEGIN SELECT RAISE(ABORT, 'Forced export save failure'); END",
            )
        }
        failTrigger = true
        val probe = runBlocking {
            runCatching { repository().addElement(secondPage, ElementDraft(ElementKind.TEXT, 48f, 100f, 300f, 60f, text = "Failure probe")) }
        }
        assertTrue("The failure trigger must reject a real element write", probe.isFailure)
        val word = java.io.File.createTempFile("workspace-word-", ".docx", rule.activity.cacheDir)
        val pdf = java.io.File.createTempFile("workspace-pdf-", ".pdf", rule.activity.cacheDir)
        try {
            val action = EditorAction.ExportWordText(Uri.fromFile(word))
            rule.runOnUiThread { workspace.requestExport(0, action) }
            rule.waitUntil(10_000) { workspace.failed && workspace.pending == null }
            assertEquals(action, workspace.failedRequest?.exportAction)
            assertEquals(0, workspace.failedRequest?.exportPane)
            assertEquals(0L, word.length())
            assertEquals(unsaved, secondHolder.inlineTextDraft.value)
            runBlocking { assertTrue(repository().getElements(secondPage).isEmpty()) }

            rule.activityRule.scenario.recreate()
            waitFor(hasTestTag("workspace-save-failed"))
            workspace = rule.runOnIdle { ViewModelProvider(rule.activity)["editor-workspace", EditorWorkspaceHolder::class.java] }
            assertEquals(action, workspace.failedRequest?.exportAction)
            removeFailure()
            rule.onNodeWithTag("workspace-retry").performClick()
            rule.waitUntil(30_000) { word.length() > 0 && workspace.pending == null }
            assertFalse(workspace.failed)
            assertTrue(WordTextCodec.read(word).paragraphs.joinToString("\n").contains(unsaved.text))
            runBlocking { assertEquals(unsaved.text, repository().getElements(secondPage).single().text) }

            val primary = editor(0)
            val firstHolder = holder(0)
            val pdfDraft = InlineTextDraft(firstPage, null, CanvasPoint(48f, 100f), "Unsaved primary PDF text")
            rule.runOnUiThread {
                primary.dismissWordDocumentMessage()
                assertTrue(firstHolder.beginInlineText(pdfDraft))
                workspace.requestExport(1, EditorAction.ExportPdf(Uri.fromFile(pdf)))
            }
            rule.waitUntil(30_000) { pdf.length() > 0 && workspace.pending == null }
            assertFalse(workspace.failed)
            runBlocking {
                assertEquals(pdfDraft.text, repository().getElements(firstPage).single().text)
                assertEquals(2, PdfSandboxClient(rule.activity).inspect(pdf).pages.size)
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    assertTrue(PdfTextSearcher(rule.activity).search(pdf, 0, pdfDraft.text, allowOcr = false).isNotEmpty())
                }
            }
            rule.runOnUiThread {
                workspace.requestExport(0, EditorAction.ExportWordText(Uri.parse("content://missing-export-provider/output.docx")))
            }
            rule.waitUntil(30_000) {
                workspace.pending == null && primary.state.value.wordDocumentMessage ==
                    rule.activity.getString(com.majkeylab.seliadocs.R.string.word_export_failed)
            }
            assertFalse("Export failure belongs to the exporter, not draft-save retry", workspace.failed)
            assertEquals(null, workspace.failedRequest)
            rule.runOnUiThread { workspace.retry() }
            assertEquals(null, workspace.pending)
            assertTrue(primary.state.value.failed)
            rule.runOnUiThread {
                primary.dismissWordDocumentMessage()
                workspace.requestExport(0, EditorAction.ExportWordText(Uri.fromFile(word)))
            }
            rule.waitUntil(10_000) { workspace.pending == null }
            assertFalse(primary.state.value.failed)
            assertTrue(WordTextCodec.read(word).paragraphs.joinToString("\n").contains(pdfDraft.text))
        } finally {
            word.delete()
            pdf.delete()
        }
    }

    @Test fun savedDraftFromPreviousPageCannotOverwriteOtherPaneEdits() {
        rule.activity.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(1_000.dp, 744.dp))) {
                SeliaDocsApp()
            }
        }
        val titles = fixture()
        val firstPage = requireNotNull(editor(0).state.value.selectedPage).id
        val extraPages = runBlocking { List(2) { repository().addPage(notebooks.first()) } }
        rule.waitUntil(10_000) { editor(0).state.value.pages.size == 3 }
        openBeside(titles.first)
        rule.waitUntil(10_000) {
            runCatching { rule.onNode(hasText("Next page") and inPane(1)).assertIsEnabled() }.isSuccess
        }
        rule.onNode(hasText("Next page") and inPane(1)).performClick()
        rule.waitUntil(10_000) { holder(1).selectedPage.value == extraPages.first() }

        fun activate(index: Int) {
            paneNode(index, "workspace-pane-header").performTouchInput { click(Offset(8f, height / 2f)) }
        }
        fun goToPage(index: Int, pageNumber: Int, pageId: String) {
            activate(index)
            paneNode(index, "compact-page-location").performClick()
            rule.onNode(hasTestTag("page-thumbnail") and hasText("Page $pageNumber")).performScrollTo().performClick()
            rule.waitUntil(10_000) { holder(index).selectedPage.value == pageId }
        }

        activate(0)
        type(0, "Primary earlier text")
        goToPage(0, 3, extraPages.last())
        goToPage(1, 1, firstPage)
        type(1, "")
        paneNode(1, "page-text").performTextReplacement("Secondary latest text")
        rule.waitUntil(10_000) { editor(1).state.value.selectedBlocks.singleOrNull()?.text == "Secondary latest text" }

        goToPage(0, 2, extraPages.first())
        runBlocking { assertEquals("Secondary latest text", repository().getBlocks(firstPage).single().text) }
        assertEquals(null, holder(0).draftFor(firstPage))
        settings(0)
        waitFor(hasTestTag("settings-top-bar"))
        runBlocking { assertEquals("Secondary latest text", repository().getBlocks(firstPage).single().text) }
    }
}
