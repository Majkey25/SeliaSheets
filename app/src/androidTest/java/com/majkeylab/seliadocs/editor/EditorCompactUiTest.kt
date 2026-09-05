package com.majkeylab.seliadocs.editor

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorCompactUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun sessionHolderRetainsSameSessionAndResetsDifferentSession() {
        val holder = EditorSessionHolder()
        holder.prepare("0:notebook-a")
        assertTrue(holder.acceptDraft("page-a", TextFieldValue("Draft")))
        assertTrue(holder.beginClose(EditorCloseIntent.BACK))

        holder.prepare("0:notebook-a")
        assertFalse(holder.mutationsAllowed())
        assertEquals("Draft", holder.draftFor("page-a")?.text)

        holder.prepare("1:notebook-b")
        assertTrue(holder.mutationsAllowed())
        assertNull(holder.draftFor("page-a"))
    }

    @Test
    fun sessionHolderDropsDraftForDeletedPage() {
        val holder = EditorSessionHolder()
        holder.prepare("0:notebook")
        assertTrue(holder.acceptDraft("deleted-page", TextFieldValue("Draft")))
        assertTrue(holder.beginClose(EditorCloseIntent.BACK))

        assertNull(holder.latestDraft(setOf("remaining-page")))
        assertNull(holder.draftFor("deleted-page"))
    }

    @Test
    fun sessionHolderKeepsCloseIntentAndDraftUntilSaveCompletes() {
        val holder = EditorSessionHolder()
        holder.prepare("0:notebook")
        val draft = InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Draft")
        assertTrue(holder.beginInlineText(draft))
        assertTrue(holder.acceptDraft("page", TextFieldValue("Page draft")))
        holder.requestAction(EditorAction.SelectTool(EditorTool.PENCIL))
        val epoch = requireNotNull(holder.beginActionSave())
        holder.prepare("0:notebook")
        assertNull(holder.beginActionSave())
        assertFalse(holder.beginInlineText(draft.copy(text = "Rejected")))
        assertFalse(holder.acceptDraft("page", TextFieldValue("Rejected")))
        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
        holder.requestAction(EditorAction.AddText)
        assertNull(holder.takeReadyAction())
        assertEquals(draft, holder.inlineTextDraft.value)

        holder.completeActionSave(epoch, false)
        assertNull(holder.takeReadyAction())
        assertEquals(draft, holder.inlineTextDraft.value)
        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
        assertEquals(epoch, holder.beginActionSave())
        holder.clearInlineText()
        holder.completeActionSave(epoch, true)
        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
        assertNull(holder.takeReadyAction())
    }

    @Test
    fun callbacksFromPreviousSessionCannotClearTheNewDraftOrPendingAction() {
        val holder = EditorSessionHolder()
        holder.prepare("0:old-notebook")
        holder.requestAction(EditorAction.FinishText)
        val oldEpoch = requireNotNull(holder.beginActionSave())
        holder.prepare("1:new-notebook")
        val draft = InlineTextDraft("new-page", null, CanvasPoint(20f, 30f), "New draft")
        assertTrue(holder.beginInlineText(draft))
        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
        val newEpoch = requireNotNull(holder.beginActionSave())

        assertFalse(holder.clearInlineText(oldEpoch))
        holder.completeActionSave(oldEpoch, false)
        holder.completeActionSave(oldEpoch, true)
        assertEquals(draft, holder.inlineTextDraft.value)
        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.actionState.value.pending)
        assertTrue(holder.actionState.value.saving)
        assertNull(holder.takeReadyAction())
        holder.completeActionSave(newEpoch, true)
        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
        assertTrue(holder.beginClose(EditorCloseIntent.BACK))
        holder.completeClose(true, oldEpoch)
        assertFalse(holder.closeState.value.completed)
    }

    @Test
    fun pickerActionsRetainTheirPayloadUntilDraftSaveCompletes() {
        val source = Uri.parse("content://test/document/source")
        val destination = Uri.parse("content://test/document/export")
        val actions = listOf(
            EditorAction.ImportPdf(source),
            EditorAction.ExportPdf(destination),
            EditorAction.ImportImage("image-page", source, ocr = true),
        )
        actions.forEach { action ->
            val holder = EditorSessionHolder()
            holder.prepare("0:notebook")
            val draft = InlineTextDraft("draft-page", null, CanvasPoint(20f, 30f), "Draft before picker")
            assertTrue(holder.beginInlineText(draft))
            assertTrue(holder.acceptDraft("draft-page", TextFieldValue("Page text before picker")))
            holder.requestAction(action)
            val epoch = requireNotNull(holder.beginActionSave())
            holder.prepare("0:notebook")
            assertEquals(action, holder.actionState.value.pending)
            assertEquals(draft, holder.inlineTextDraft.value)
            assertNull(holder.takeReadyAction())
            assertFalse(holder.acceptDraft("draft-page", TextFieldValue("Rejected during save")))
            assertTrue(holder.clearInlineText(epoch))
            holder.completeActionSave(epoch, true)
            assertEquals(action, holder.takeReadyAction())
            assertNull(holder.takeReadyAction())
            holder.completeExecutingAction(epoch, action)
            assertFalse(holder.actionState.value.busy)
        }
    }

    @Test
    fun executingImportSurvivesRecreationAndKeepsBackQueuedUntilCompletion() {
        listOf(
            EditorAction.ImportPdf(Uri.parse("content://test/import.pdf")),
            EditorAction.ImportImage("page", Uri.parse("content://test/image.png"), ocr = false),
        ).forEach { action ->
            val holder = EditorSessionHolder()
            holder.prepare("0:notebook")
            holder.requestAction(action)
            val epoch = requireNotNull(holder.beginActionSave())
            holder.completeActionSave(epoch, true)
            assertEquals(action, holder.takeReadyAction())
            holder.prepare("0:notebook")
            assertEquals(action, holder.actionState.value.executing)
            assertTrue(holder.actionState.value.busy)
            assertFalse(holder.acceptDraft("page", TextFieldValue("Rejected during import")))
            assertFalse(holder.beginInlineText(InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Rejected")))
            holder.requestAction(EditorAction.AddText)
            holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
            holder.requestAction(EditorAction.AddText)
            assertNull(holder.beginActionSave())
            assertNull(holder.takeReadyAction())
            holder.completeExecutingAction(epoch - 1, action)
            assertEquals(action, holder.actionState.value.executing)

            holder.completeExecutingAction(epoch, action)
            assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.actionState.value.pending)
            assertEquals(epoch, holder.beginActionSave())
            holder.completeActionSave(epoch, true)
            assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
            assertFalse(holder.actionState.value.busy)
        }
    }

    @Test
    fun compactEditorKeepsPrimaryActionsVisibleAndOneToolSelected() {
        val title = openCompactEditor()

        listOf("compact-undo", "compact-redo", "compact-more", "compact-page-location").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed().assertHasClickAction()
        }
        val tools = listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
        tools.forEach { rule.onNodeWithTag("compact-tool-$it").assertIsDisplayed() }
        rule.onNodeWithTag("compact-insert").assertIsDisplayed()
        rule.onNodeWithTag("compact-tool-pen").assertIsSelected()
        assertEquals(
            "Compact palette must expose exactly one selected tool",
            1,
            tools.count {
                rule.onNodeWithTag("compact-tool-$it")
                    .fetchSemanticsNode().config[SemanticsProperties.Selected]
            },
        )
        rule.onNodeWithTag("compact-page-location").assertTextContains(title)
        rule.onNodeWithTag("compact-page-location").assertTextContains("Page 1 of 1")
    }

    @Test
    fun compactMenusExposeSecondaryActions() {
        openCompactEditor()

        rule.onNodeWithTag("compact-more").performClick()
        listOf("add-page", "search", "export", "settings").forEach {
            rule.onNodeWithTag("compact-more-$it").assertIsDisplayed()
        }
        pressBack()

        rule.onNodeWithTag("compact-insert").performClick()
        listOf("text", "image", "pdf").forEach {
            rule.onNodeWithTag("compact-insert-$it").assertIsDisplayed()
        }
        rule.onNodeWithTag("compact-insert-shape").assertDoesNotExist()
        rule.onNodeWithTag("compact-insert-math").assertDoesNotExist()
    }

    @Test
    fun compactTextObjectWaitsForPageTapThenSavesInline() {
        openCompactEditor()

        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        rule.onNodeWithText("Add text").assertDoesNotExist()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        val draft = "Inline ${System.nanoTime()}"
        val editor = rule.onNodeWithTag("inline-text-editor").assertIsDisplayed()
        editor.performTextInput(draft)
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val editorPageBounds = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
        val editorX = (editorBounds.left - editorPageBounds.left) / editorPageBounds.width
        val editorY = (editorBounds.top - editorPageBounds.top) / editorPageBounds.height
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText(draft).fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        val savedBounds = rule.onNodeWithText(draft).fetchSemanticsNode().boundsInRoot
        val savedPageBounds = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
        val savedX = (savedBounds.left - savedPageBounds.left) / savedPageBounds.width
        val savedY = (savedBounds.top - savedPageBounds.top) / savedPageBounds.height
        assertTrue(kotlin.math.abs(savedX - editorX) <= 0.03f)
        assertTrue(kotlin.math.abs(savedY - editorY) <= 0.03f)
        rule.onNodeWithTag("element-selection").assertIsDisplayed()
    }

    @Test
    fun selectedTextCanBeEditedInline() {
        openCompactEditor()
        val original = "Editable ${System.nanoTime()}"
        val updated = "$original updated"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(original)
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty() &&
                runCatching { rule.onNodeWithTag("element-context-bar").fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithText("Edit text", useUnmergedTree = true).performClick()
        val editor = rule.onNodeWithTag("inline-text-editor").assertTextContains(original)
        rule.onNodeWithTag("element-context-bar").assertDoesNotExist()
        rule.onNodeWithText("Delete").assertDoesNotExist()
        editor.performTextReplacement(updated)
        editor.performImeAction()

        rule.onNodeWithText(updated).assertIsDisplayed()
        rule.onNodeWithText(original).assertDoesNotExist()
    }

    @Test
    fun clearingExistingInlineTextDeletesItAndUndoRestoresIt() {
        val title = openCompactEditor()
        val original = "Clear and undo ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(original)
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("element-context-bar").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Edit text").performClick()
        rule.onNodeWithTag("inline-text-editor").performTextReplacement("")
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                gateAcquired.countDown()
                releaseGate.await()
            }
        }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.onNodeWithTag("inline-text-editor").assertIsNotEnabled()
            rule.onNodeWithTag("element-context-bar").assertDoesNotExist()
            rule.onNodeWithTag("ink-context-bar").assertDoesNotExist()
            rule.onNodeWithText("Delete").assertDoesNotExist()
            releaseGate.complete(Unit)
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithTag("compact-undo").assertIsEnabled() }.isSuccess
            }
            rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
            rule.onNodeWithText(original).assertDoesNotExist()
            assertStoredInlineTexts(title, emptyList())
            rule.onNodeWithTag("compact-undo").performClick()
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithText(original).assertIsDisplayed() }.isSuccess
            }
            assertStoredInlineTexts(title, listOf(original))
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun recreationKeepsOrSavesInlineText() {
        createAndOpenNotebook()
        val draft = "Recreated inline ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)

        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag("editor-top-bar").fetchSemanticsNode() }.isSuccess
        }
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag("inline-text-editor").fetchSemanticsNode() }.isSuccess ||
                rule.onAllNodes(hasText(draft)).fetchSemanticsNodes().size == 1
        }
        if (runCatching { rule.onNodeWithTag("inline-text-editor").fetchSemanticsNode() }.isSuccess) {
            rule.onNodeWithTag("inline-text-editor").assertTextContains(draft)
        } else {
            rule.onNodeWithText(draft).assertIsDisplayed()
            assertEquals(1, rule.onAllNodes(hasText(draft)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun toolSwitchSavesInlineTextBeforeChangingTool() {
        openCompactEditor()
        val draft = "Saved before Pencil ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)

        rule.onNodeWithTag("compact-tool-pencil").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-pencil").assertIsSelected()
        rule.onNodeWithText(draft).assertIsDisplayed()
    }

    @Test
    fun pageHistoryActionsAreDisabledWhileEditingPageOrInlineText() {
        openCompactEditor()
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput("History ${System.nanoTime()}")
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("compact-undo").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithTag("compact-undo").performClick()
        rule.onNodeWithTag("compact-redo").assertIsEnabled()
        rule.onNodeWithTag("compact-tool-type").performClick()
        rule.onNodeWithTag("compact-undo").assertIsNotEnabled()
        rule.onNodeWithTag("compact-redo").assertIsNotEnabled()

        rule.onNodeWithTag("compact-tool-pencil").performClick()
        rule.onNodeWithTag("compact-redo").assertIsEnabled()
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("compact-undo").assertIsNotEnabled()
        rule.onNodeWithTag("compact-redo").assertIsNotEnabled()
    }

    @Test
    fun emptyInlineTextDraftCreatesNoElement() {
        openCompactEditor()

        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").assertIsDisplayed().performImeAction()

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        rule.onNodeWithTag("element-selection").assertDoesNotExist()
    }

    @Test
    fun backSavesInlineTextBeforeLeavingTheEditor() {
        val title = openCompactEditor()
        val draft = "Saved on close ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithContentDescription("Open $title").performClick()

        assertStoredInlineTexts(title, listOf(draft))
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithText(draft).assertIsDisplayed()
    }

    @Test
    fun doneThenBackWaitsForTheInlineWrite() {
        val title = openCompactEditor()
        val draft = "Queued inline ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner =
            CoroutineScope(Dispatchers.IO).launch {
                LibraryMutationGate.withLock {
                    gateAcquired.countDown()
                    releaseGate.await()
                }
            }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            releaseGate.complete(Unit)
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithContentDescription("Open $title").performClick()

            assertStoredInlineTexts(title, listOf(draft))
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithText(draft).assertIsDisplayed()
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun recreationDuringToolSaveKeepsInputDisabledAndBackSavesOnce() {
        val title = createAndOpenNotebook()
        val draft = "Retained save ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                gateAcquired.countDown()
                releaseGate.await()
            }
        }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("compact-tool-pencil").performClick()
            rule.onNodeWithTag("inline-text-editor").assertIsNotEnabled().assertTextContains(draft)
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("inline-text-editor").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithTag("inline-text-editor").assertIsNotEnabled().assertTextContains(draft)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            releaseGate.complete(Unit)
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
            }
            assertStoredInlineTexts(title, listOf(draft))
            rule.onNodeWithContentDescription("Open $title").performClick()
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithText(draft).assertIsDisplayed()
            assertEquals(1, rule.onAllNodes(hasText(draft)).fetchSemanticsNodes().size)
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun addTextDuringSaveKeepsOriginalAndStartsAnEmptyPlacement() {
        val title = openCompactEditor()
        val original = "First text ${System.nanoTime()}"
        val next = "Second text ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(original)
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                gateAcquired.countDown()
                releaseGate.await()
            }
        }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.onNodeWithTag("compact-insert").performClick()
            rule.onNodeWithTag("compact-insert-text").performClick()
            releaseGate.complete(Unit)
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("inline-text-placement").fetchSemanticsNode() }.isSuccess
            }
            assertStoredInlineTexts(title, listOf(original))
            rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
            rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
            rule.onNodeWithTag("inline-text-editor").performTextInput(next)
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.waitUntil(5_000) {
                rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                    .fetchSemanticsNodes().isEmpty()
            }
            assertStoredInlineTexts(title, listOf(original, next))
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun expandedTextObjectAlsoStartsInlineOnThePage() {
        openEditor(widthDp = 1280)

        rule.onNodeWithTag("toolbar-insert").performClick()
        rule.onNodeWithTag("toolbar-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }

        rule.onNodeWithTag("inline-text-editor").assertIsDisplayed()
    }

    @Test
    fun selectedInkExposesTextConversion() {
        rule.activity.setContent {
            SeliaDocsTheme {
                CompactEditorPalette(
                    state = EditorUiState(selectedStrokeIds = setOf("stroke")),
                    onSelectTool = {},
                    onEraserMode = {},
                    onAddText = {},
                    onAddImage = {},
                    onImportPdf = {},
                    onCleanShape = {},
                )
            }
        }

        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-convert").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun dismissingSearchBeforeDebounceDoesNotRestoreQuery() {
        openCompactEditor()
        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-search").performClick()
        rule.onNodeWithTag("search-query").assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("search-query").performTextInput("stale-query")
            rule.onNodeWithText("Close").performClick()
            rule.mainClock.advanceTimeBy(300)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()

        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-search").performClick()
        rule.onNodeWithTag("search-query")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun searchFlushesPendingFullPageText() {
        val text = "PendingSearch${System.nanoTime()}"
        openCompactEditor()
        rule.onNodeWithTag("compact-tool-type").performClick()
        rule.onNodeWithTag("page-text").assertIsDisplayed().performTextInput(text)

        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-search").performClick()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("search-query").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("search-query").performTextInput(text)
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("search-result-0").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("search-result-0").assertIsDisplayed()
    }

    @Test
    fun typeFieldKeepsPageNavigationKeys() {
        openCompactEditor()
        addPageFromEditor()
        rule.onNodeWithTag("compact-page-location").assertTextContains("Page 2 of 2")
        selectTypeTool()

        rule.onNodeWithTag("page-text").performKeyInput { pressKey(Key.PageUp) }

        rule.onNodeWithTag("compact-page-location").assertTextContains("Page 2 of 2")
    }

    @Test
    fun compactActionsKeepFortyEightDpTouchTargets() {
        openCompactEditor()
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val minimumHeight = 48f * rootBounds.width / 360f
        val tags =
            listOf("back", "undo", "redo", "more", "page-location").map { "compact-$it" } +
                listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
                    .map { "compact-tool-$it" } +
                "compact-insert"

        tags.forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width ${bounds.width} must be at least $minimumHeight px", bounds.width >= minimumHeight)
            assertTrue("$tag height ${bounds.height} must be at least $minimumHeight px", bounds.height >= minimumHeight)
            assertTrue("$tag starts outside root: $bounds vs $rootBounds", bounds.left >= rootBounds.left)
            assertTrue("$tag ends outside root: $bounds vs $rootBounds", bounds.right <= rootBounds.right)
            assertTrue("$tag top exceeds root: $bounds vs $rootBounds", bounds.top >= rootBounds.top)
            assertTrue("$tag bottom exceeds root: $bounds vs $rootBounds", bounds.bottom <= rootBounds.bottom)
        }
    }
    @Test
    fun compactTopBarAtTwoHundredPercentKeepsActionsReachable() {
        val title = openEditor(widthDp = 360, fontScale = 2f)
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val minimum = 48f * rootBounds.width / 360f
        listOf("compact-back", "compact-page-location", "compact-more").forEach { tag ->
            val node = rule.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is narrower than 48 dp: $bounds", bounds.width >= minimum)
            assertTrue("$tag is shorter than 48 dp: $bounds", bounds.height >= minimum)
            assertTrue("$tag starts outside root: $bounds", bounds.left >= rootBounds.left)
            assertTrue("$tag ends outside root: $bounds", bounds.right <= rootBounds.right)
            assertTrue("$tag starts above root: $bounds", bounds.top >= rootBounds.top)
            assertTrue("$tag ends below root: $bounds", bounds.bottom <= rootBounds.bottom)
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("editor-top-bar-title", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        assertFalse(titleLayouts.single().isLineEllipsized(0))
        rule.onNodeWithTag("editor-top-bar-title", useUnmergedTree = true).assertTextContains("Page 1 of 1")
        assertEquals(
            listOf("$title, Page 1 of 1"),
            rule.onNodeWithTag("compact-page-location")
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        rule.onNodeWithTag("compact-undo").assertDoesNotExist()
        rule.onNodeWithTag("compact-redo").assertDoesNotExist()
        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-undo").assertIsDisplayed()
        rule.onNodeWithTag("compact-more-redo").assertIsDisplayed()
    }
    @Test
    fun activityRetainsOneSessionHolderAndClearsChildBetweenEditors() {
        rule.waitForIdle()
        val activityModelCount = rule.activity.viewModelStore.keys().size
        val firstTitle = openCompactEditor()
        rule.onNodeWithTag("compact-back").performClick()
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithContentDescription("Open $firstTitle").fetchSemanticsNode()
            }.isSuccess
        }
        assertEquals(activityModelCount + 1, rule.activity.viewModelStore.keys().size)
        val secondTitle = createAndOpenNotebook()

        rule.onNodeWithTag("editor-top-bar-title", useUnmergedTree = true).assertTextContains(secondTitle)
        assertEquals(activityModelCount + 1, rule.activity.viewModelStore.keys().size)
    }
    @Test
    fun recreationRetainsDraftAndSystemBackWaitsForFlush() {
        val title = createAndOpenNotebook()
        val draft = "Draft ${System.nanoTime()}"
        selectTypeTool()
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithTag("page-text").assertIsDisplayed().fetchSemanticsNode()
            }.isSuccess
        }
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner =
            CoroutineScope(Dispatchers.IO).launch {
                LibraryMutationGate.withLock {
                    gateAcquired.countDown()
                    releaseGate.await()
                }
            }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("page-text").performTextInput(draft)
            rule.mainClock.advanceTimeByFrame()
            addPageFromEditor()
            rule.mainClock.advanceTimeByFrame()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
            rule.onNodeWithTag("page-text").assertIsNotEnabled()
            addPageFromEditor()
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.autoAdvance = true
            rule.activityRule.scenario.recreate()

            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("editor-top-bar").fetchSemanticsNode() }.isSuccess ||
                    runCatching {
                        rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                    }.isSuccess
            }
            assertFalse(
                "Recreation restored the library route instead of the active editor",
                runCatching {
                    rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                }.isSuccess,
            )
            rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("page-text").assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithTag("page-text").assertTextContains(draft)
            rule.onNodeWithTag("page-text").assertIsNotEnabled()

            releaseGate.complete(Unit)
            rule.mainClock.autoAdvance = true
            rule.waitUntil(15_000) {
                runCatching {
                    rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                }.isSuccess
            }
            rule.onNodeWithContentDescription("Open $title").performClick()
            selectTypeTool()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("page-text").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithTag("page-text").assertTextContains(draft)
        } finally {
            rule.mainClock.autoAdvance = true
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    private fun selectTypeTool() {
        val tag = if (hasTag("compact-tool-type")) "compact-tool-type" else "toolbar-tool-type"
        rule.onNodeWithTag(tag).performClick().assertIsSelected()
    }

    private fun addPageFromEditor() {
        if (hasTag("compact-more")) {
            rule.onNodeWithTag("compact-more").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("compact-more-add-page").performClick()
        } else {
            rule.onNodeWithContentDescription("Add page").performClick()
        }
    }

    private fun hasTag(tag: String): Boolean =
        runCatching { rule.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess

    @Test
    fun compactPaletteAppliesInjectedNavigationInsets() {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 744.dp)),
            ) {
                SeliaDocsTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CompactEditorPalette(
                            state = EditorUiState(),
                            onSelectTool = {},
                            onEraserMode = {},
                            onAddText = {},
                            onAddImage = {},
                            onImportPdf = {},
                            onCleanShape = {},
                            contentInsets = WindowInsets(16.dp, 0.dp, 20.dp, 24.dp),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = rootBounds.width / 360f
        val safeLeft = (16f * density).toInt()
        val safeRight = (20f * density).toInt()
        val safeBottom = (24f * density).toInt()

        listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
            .map { "compact-tool-$it" }
            .plus("compact-insert")
            .forEach { tag ->
                val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag width ${bounds.width} must stay at least 48 dp", bounds.width >= 48f * density)
                assertTrue("$tag height ${bounds.height} must stay at least 48 dp", bounds.height >= 48f * density)
                assertTrue("$tag overlaps left inset: $bounds", bounds.left >= rootBounds.left + safeLeft)
                assertTrue("$tag overlaps right inset: $bounds", bounds.right <= rootBounds.right - safeRight)
                assertTrue("$tag overlaps bottom inset: $bounds", bounds.bottom <= rootBounds.bottom - safeBottom)
            }
    }

    @Test
    fun compactToolsExposeMaterialIconDescriptions() {
        openCompactEditor()

        listOf("Back", "Undo", "Redo", "More options", "Type", "Pen", "Pencil", "Highlighter", "Eraser", "Lasso", "Insert")
            .forEach { description ->
                rule.onNodeWithContentDescription(description).assertIsDisplayed()
            }
    }

    @Test
    fun compactPenExposesWidthsAndColorsWithoutLeavingEditor() {
        openCompactEditor()

        rule.onNodeWithTag("brush-width-slider").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-pen").performClick()

        val penRange =
            rule.onNodeWithTag("brush-width-slider")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, penRange.range.start)
        assertEquals(32f, penRange.range.endInclusive)
        rule.onNodeWithTag("brush-width-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        rule.waitForIdle()
        assertEquals(
            32f,
            rule.onNodeWithTag("brush-width-slider")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
                .current,
        )

        listOf(
            "brush-color-black",
            "brush-color-blue",
            "brush-color-red",
        )
            .forEach { tag -> rule.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction() }
        rule.onNodeWithTag("brush-shape-assist").assertExists().assertHasClickAction()

        rule.onNodeWithTag("compact-tool-highlighter").performClick()
        rule.onNodeWithTag("brush-width-slider").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-highlighter").performClick()
        val highlighterRange =
            rule.onNodeWithTag("brush-width-slider")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(4f, highlighterRange.range.start)
        assertEquals(64f, highlighterRange.range.endInclusive)
    }

    @Test
    fun compactPencilUsesItsOwnVisibleSelectedTool() {
        openCompactEditor()

        rule.onNodeWithTag("compact-tool-pencil").performClick()

        rule.onNodeWithTag("compact-tool-pencil").assertIsSelected()
        rule.onNodeWithTag("compact-tool-pen").assertIsNotSelected()
        val tools = listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
        assertEquals(
            1,
            tools.count {
                rule.onNodeWithTag("compact-tool-$it")
                    .fetchSemanticsNode().config[SemanticsProperties.Selected]
            },
        )
    }

    @Test
    fun compactEraserMenuSelectsBothModesAndDismisses() {
        openCompactEditor()

        rule.onNodeWithTag("compact-tool-eraser").performClick()
        rule.onNodeWithTag("compact-eraser-segment")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        rule.onNodeWithTag("compact-eraser-stroke")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
            .performClick()
        rule.onNodeWithTag("compact-eraser-stroke").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-eraser")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Whole stroke"))

        rule.onNodeWithTag("compact-tool-eraser").performClick()
        rule.onNodeWithTag("compact-eraser-stroke").assertIsSelected()
        rule.onNodeWithTag("compact-eraser-segment").performClick()
        rule.onNodeWithTag("compact-eraser-segment").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-eraser")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Segment"))
    }

    @Test
    fun mediumEditorKeepsExistingToolbarAndPageLocation() {
        openEditor(widthDp = 600)
        rule.onNodeWithTag("compact-page-location").assertDoesNotExist()
        rule.onNodeWithTag("compact-insert").assertDoesNotExist()
        rule.onNodeWithText("Contents").assertIsDisplayed()
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun expandedEditorKeepsExistingToolbarAndContentsPane() {
        openEditor(widthDp = 1280)
        rule.onNodeWithTag("compact-page-location").assertDoesNotExist()
        rule.onNodeWithTag("compact-insert").assertDoesNotExist()
        rule.onNodeWithText("Add chapter").assertExists()
        rule.onNodeWithContentDescription("Search").assertExists()
    }

    private fun openCompactEditor(): String {
        return openEditor(widthDp = 360)
    }

    private fun assertStoredInlineTexts(title: String, expected: List<String>) = runBlocking {
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
        val notebook = repository.getAllNotebooks().single { it.title == title }
        val pages = repository.getPages(notebook.id)
        val elements = pages.flatMap { repository.getElements(it.id) }.filter { it.kind == ElementKind.TEXT.name }
        assertEquals("Stored text elements: $elements", expected.sorted(), elements.map { it.text }.sortedBy { it })
        elements.forEach { element ->
            val page = pages.single { it.id == element.pageId }
            assertTrue(
                "Text must fit page ${page.widthPoints}x${page.heightPoints}: $element",
                element.x >= 0f && element.y >= 0f && element.width > 0f && element.height > 0f &&
                    element.x + element.width <= page.widthPoints && element.y + element.height <= page.heightPoints,
            )
        }
    }

    private fun openEditor(widthDp: Int, fontScale: Float = 1f): String {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(widthDp.dp, 744.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    SeliaDocsApp()
                }
            }
        }
        return createAndOpenNotebook()
    }

    private fun createAndOpenNotebook(): String {
        val title = "Compact editor ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").assertIsDisplayed().performTextReplacement(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Open $title").performClick()
        rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
        return title
    }
}
