package com.majkeylab.seliadocs.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.backup.validTestStrokePayload
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.recognition.RecognitionModelManager
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageContentReadinessTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun switchingPagesWaitsForMatchingContentBeforeDraftsAndQueuedActions() = assertPageContentReadiness(closeWhileLoading = false)

    @Test
    fun closingWhileNextPageLoadsDoesNotWaitForItsContent() = assertPageContentReadiness(closeWhileLoading = true)

    private fun assertPageContentReadiness(closeWhileLoading: Boolean) {
        val application = rule.activity.application
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebook = runBlocking {
            repository.createNotebook(CreateNotebookRequest(
                "Page readiness ${System.nanoTime()}", CoverColor.SAGE, CoverPattern.SOLID,
                PaperTemplate.BLANK, PageOrientation.PORTRAIT, false,
            ))
        }
        val first = runBlocking { repository.getPages(notebook).single().id }
        val second = runBlocking { repository.addPage(notebook, first) }
        val firstElement = runBlocking {
            repository.updatePageText(first, "First page only")
            repository.updatePageText(second, "Second page only")
            repository.addStroke(first, validTestStrokePayload())
            repository.addElement(first, ElementDraft(ElementKind.TEXT, 40f, 300f, 180f, 50f, text = "First object"))
        }
        val secondLoadStarted = CompletableDeferred<Unit>()
        val releaseSecondLoad = CompletableDeferred<Unit>()
        val closed = AtomicBoolean()
        lateinit var holder: EditorSessionHolder
        lateinit var editor: EditorViewModel
        try {
            rule.runOnUiThread {
                holder = ViewModelProvider(rule.activity, viewModelFactory { initializer { EditorSessionHolder() } })[
                    "editor-session-holder", EditorSessionHolder::class.java]
                holder.prepare("0:$notebook")
                editor = ViewModelProvider(holder, viewModelFactory {
                    initializer {
                        EditorViewModel(application, notebook, initialTool = EditorTool.TYPE,
                            mutationAllowed = holder::mutationsAllowed,
                            pageContentLoadBoundary = { pageId ->
                                if (pageId == second) {
                                    secondLoadStarted.complete(Unit)
                                    releaseSecondLoad.await()
                                }
                            },
                        )
                    }
                })["editor", EditorViewModel::class.java]
            }
            val recognition = RecognitionModelManager()
            rule.setContent {
                SeliaDocsTheme {
                    EditorRoute(
                        notebookId = notebook,
                        libraryGeneration = 0,
                        recognitionModelManager = recognition,
                        settings = AppSettings(pageTransition = false),
                        onUpdateSettings = {},
                        onBack = { closed.set(true) },
                        onSettings = {},
                    )
                }
            }
            rule.waitUntil(5_000) { editor.state.value.pageContentReady && editor.state.value.blocks.singleOrNull()?.text == "First page only" }
            rule.onNodeWithTag("page-text").assertIsEnabled().assertTextContains("First page only")
            assertEquals(first, editor.state.value.selectedPage?.id)
            assertEquals(1, editor.state.value.strokes.size)
            assertEquals(firstElement, editor.state.value.elements.single().id)

            rule.runOnUiThread { holder.requestAction(EditorAction.SelectPage(second)) }
            rule.waitUntil(5_000) { secondLoadStarted.isCompleted && editor.state.value.selectedPage?.id == second }
            rule.runOnIdle {
                val loading = editor.state.value
                assertFalse(loading.pageContentReady)
                assertTrue(loading.strokes.isEmpty())
                assertTrue(loading.elements.isEmpty())
                assertTrue(loading.blocks.isEmpty())
                assertEquals(null, holder.draftFor(second))
            }
            rule.onNodeWithTag("page-text").assertDoesNotExist()
            rule.onNodeWithTag("element-$firstElement").assertDoesNotExist()

            if (closeWhileLoading) {
                rule.runOnUiThread { holder.requestAction(EditorAction.WorkspaceSave(1L)) }
                rule.waitUntil(5_000) {
                    holder.workspaceSaveResult.value == (1L to true) && !holder.actionState.value.busy
                }
                assertFalse("Workspace save must not wait for the next page read", releaseSecondLoad.isCompleted)
                rule.runOnUiThread { holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK)) }
                rule.waitUntil(5_000) { closed.get() }
                assertFalse("Back must complete before the next page read is released", releaseSecondLoad.isCompleted)
                runBlocking {
                    assertEquals("First page only", repository.getBlocks(first).single().text)
                    assertEquals("Second page only", repository.getBlocks(second).single().text)
                    assertEquals(firstElement, repository.getElements(first).single().id)
                    assertEquals(1, repository.getStrokes(first).size)
                }
                return
            }

            val queued = EditorAction.SelectTool(EditorTool.PENCIL)
            rule.runOnUiThread { holder.requestAction(queued) }
            rule.runOnIdle {
                assertEquals(queued, holder.actionState.value.pending)
                assertFalse(holder.actionState.value.saving)
                assertEquals(EditorTool.TYPE, editor.state.value.tool)
            }
            releaseSecondLoad.complete(Unit)
            rule.waitUntil(5_000) { editor.state.value.pageContentReady && editor.state.value.tool == EditorTool.PENCIL }
            rule.onNodeWithTag("page-text").assertIsDisplayed().assertTextContains("Second page only")
            rule.runOnUiThread { holder.requestAction(EditorAction.SelectTool(EditorTool.TYPE)) }
            rule.waitUntil(5_000) { editor.state.value.tool == EditorTool.TYPE }
            rule.onNodeWithTag("page-text").assertIsEnabled().performTextReplacement("Second page edited")
            rule.waitUntil(5_000) { editor.state.value.blocks.singleOrNull()?.text == "Second page edited" }
            runBlocking {
                assertEquals("First page only", repository.getBlocks(first).single().text)
                assertEquals("Second page edited", repository.getBlocks(second).single().text)
                assertEquals(firstElement, repository.getElements(first).single().id)
                assertEquals(1, repository.getStrokes(first).size)
            }
        } finally {
            releaseSecondLoad.complete(Unit)
            rule.runOnUiThread { rule.activity.viewModelStore.clear() }
            runBlocking { repository.deleteNotebook(notebook) }
        }
    }
}
