package com.majkeylab.seliadocs.editor

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.TEXT_ELEMENT_MAX_LENGTH
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.recognition.RecognitionModelManager
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSavedStateDraftTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val owners = mutableListOf<RegistryOwner>()

    @After
    fun tearDown() = onMain { owners.forEach(RegistryOwner::destroy) }

    @Test
    fun freshProcessRestoresBothPaneDraftsAndTheirNotebookContext() = onMain {
        val original = owner()
        val workspace = original.workspace()
        workspace.prepare("7:opened-book", "opened-book", null)
        workspace.primary = WorkspacePane("primary-book")
        workspace.secondary = WorkspacePane("secondary-book")
        workspace.activePane = 1
        val primary = original.editor("editor-session-holder")
        val secondary = original.editor("secondary-editor-session-holder")
        primary.prepare("7:primary-book")
        secondary.prepare("7:secondary-book")
        val newDraft = InlineTextDraft(
            "primary-page-2", null, CanvasPoint(42f, 84f), "New draft ".repeat(1_000),
        )
        val editedDraft = InlineTextDraft(
            "secondary-page-3", "existing-text", CanvasPoint(96f, 144f),
            "Edited text ".repeat(1_000).take(TEXT_ELEMENT_MAX_LENGTH),
        )
        assertTrue(primary.beginInlineText(newDraft))
        assertTrue(secondary.beginInlineText(editedDraft))
        val insertionId = requireNotNull(primary.inlineInsertionId)
        primary.requestAction(EditorAction.DeletePage(newDraft.pageId))
        workspace.request(WorkspaceChange.BACK)

        val saved = parcelCopy(original.save())
        original.destroy()
        val restored = owner(saved)
        val restoredWorkspace = restored.workspace()
        restoredWorkspace.prepare("7:opened-book", "opened-book", null)
        assertEquals(WorkspacePane("primary-book"), restoredWorkspace.primary)
        assertEquals(WorkspacePane("secondary-book"), restoredWorkspace.secondary)
        assertEquals(1, restoredWorkspace.activePane)
        assertNull(restoredWorkspace.pending)
        val restoredPrimary = restored.editor("editor-session-holder")
        val restoredSecondary = restored.editor("secondary-editor-session-holder")
        restoredPrimary.prepare("7:primary-book")
        restoredSecondary.prepare("7:secondary-book")
        assertNotSame(primary, restoredPrimary)
        assertNotSame(secondary, restoredSecondary)
        assertEquals(newDraft, restoredPrimary.inlineTextDraft.value)
        assertEquals(editedDraft, restoredSecondary.inlineTextDraft.value)
        assertEquals(insertionId, restoredPrimary.inlineInsertionId)
        assertNull(restoredSecondary.inlineInsertionId)
        assertNull(restoredPrimary.actionState.value.pending)
    }

    @Test
    fun failedSaveRetainsDraftButClearAndSessionSwitchDoNotResurrectIt() = onMain {
        val original = owner()
        val primary = original.editor("editor-session-holder")
        val secondary = original.editor("secondary-editor-session-holder")
        primary.prepare("0:first-book")
        secondary.prepare("0:second-book")
        val draft = InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Unsaved text")
        assertTrue(primary.beginInlineText(draft))
        assertTrue(secondary.beginInlineText(draft.copy(pageId = "other-page")))
        primary.requestAction(EditorAction.FinishText)
        primary.completeActionSave(requireNotNull(primary.beginActionSave()), false)

        val saved = parcelCopy(original.save())
        original.destroy()
        val restored = owner(saved)
        val restoredPrimary = restored.editor("editor-session-holder")
        val restoredSecondary = restored.editor("secondary-editor-session-holder")
        restoredPrimary.prepare("0:first-book")
        restoredSecondary.prepare("0:second-book")
        assertEquals(draft, restoredPrimary.inlineTextDraft.value)
        assertEquals(draft.copy(pageId = "other-page"), restoredSecondary.inlineTextDraft.value)
        val primaryInsertionId = requireNotNull(restoredPrimary.inlineInsertionId)
        val secondaryInsertionId = requireNotNull(restoredSecondary.inlineInsertionId)
        assertTrue(restoredPrimary.clearInlineText())
        assertNull(restoredPrimary.inlineInsertionId)
        assertTrue(restoredPrimary.beginInlineText(draft))
        assertNotEquals(primaryInsertionId, requireNotNull(restoredPrimary.inlineInsertionId))
        assertTrue(restoredPrimary.clearInlineText())
        restoredSecondary.prepare("1:replacement-book")
        assertNull(restoredSecondary.inlineInsertionId)
        assertTrue(restoredSecondary.beginInlineText(draft))
        assertNotEquals(secondaryInsertionId, requireNotNull(restoredSecondary.inlineInsertionId))
        assertTrue(restoredSecondary.clearInlineText())

        val cleared = parcelCopy(restored.save())
        restored.destroy()
        val reopened = owner(cleared)
        val reopenedPrimary = reopened.editor("editor-session-holder")
        val reopenedSecondary = reopened.editor("secondary-editor-session-holder")
        reopenedPrimary.prepare("0:first-book")
        reopenedSecondary.prepare("1:replacement-book")
        assertNull(reopenedPrimary.inlineTextDraft.value)
        assertNull(reopenedSecondary.inlineTextDraft.value)
    }

    @Test
    fun malformedSavedDraftIsDiscardedWithoutBlockingANewDraft() = onMain {
        val snapshots = listOf(
            draftSnapshot() to InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Draft"),
            draftSnapshot().apply { putString("sessionKey", "old:book") } to null,
            draftSnapshot().apply { putString("pageId", "") } to null,
            draftSnapshot().apply { putString("text", "x".repeat(TEXT_ELEMENT_MAX_LENGTH + 1)) } to null,
            draftSnapshot().apply { putFloatArray("origin", floatArrayOf(Float.NaN, 30f)) } to null,
            draftSnapshot().apply { putFloatArray("origin", floatArrayOf(20f)) } to null,
            draftSnapshot().apply { putString("elementId", "") } to null,
            draftSnapshot().apply { putString("origin", "not coordinates") } to null,
            draftSnapshot().apply { putString("insertionId", "") } to null,
        )
        snapshots.forEach { (snapshot, expected) ->
            val original = owner()
            val extras = original.extras().apply {
                this[ViewModelProvider.VIEW_MODEL_KEY] = "editor-session-holder"
            }
            extras.createSavedStateHandle()["editor-session"] = snapshot
            val saved = parcelCopy(original.save())
            original.destroy()
            val restored = owner(saved)
            val holder = restored.editor("editor-session-holder")
            holder.prepare("0:book")
            assertEquals(expected, holder.inlineTextDraft.value)
            val valid = InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Recovered editor")
            assertTrue(holder.beginInlineText(valid))
            assertEquals(valid, holder.inlineTextDraft.value)
        }
    }

    @Test
    fun pageSelectionWithoutInlineDraftRespectsNotebookAndLibraryGeneration() = onMain {
        val original = owner()
        original.editor("editor-session-holder").apply {
            prepare("0:book")
            selectedPage.value = "second-page"
        }
        val saved = parcelCopy(original.save())
        original.destroy()
        listOf("0:book", "0:other-book", "1:book").forEach { session ->
            val restored = owner(parcelCopy(saved)).editor("editor-session-holder")
            assertEquals("second-page", restored.selectedPage.value)
            restored.prepare(session)
            assertEquals(if (session == "0:book") "second-page" else null, restored.selectedPage.value)
            assertNull(restored.inlineTextDraft.value)
            assertNull(restored.inlineInsertionId)
        }
    }

    @Test
    fun optionalInlineSnapshotValidationDoesNotPartiallyRestoreSelection() = onMain {
        val selected = Bundle().apply {
            putString("sessionKey", "0:book")
            putString("selectedPageId", "second-page")
        }
        val snapshots = listOf(
            selected to "second-page",
            Bundle(selected).apply { remove("selectedPageId") } to null,
            Bundle(selected).apply { putString("selectedPageId", "") } to null,
            Bundle(selected).apply { putInt("selectedPageId", 7) } to null,
            Bundle(selected).apply { putString("text", "Incomplete draft") } to null,
            Bundle(selected).apply { putString("pageId", "draft-page") } to null,
            Bundle(selected).apply { putString("insertionId", "incomplete-insertion") } to null,
        )
        snapshots.forEach { (snapshot, expected) ->
            val original = owner()
            val extras = original.extras().apply {
                this[ViewModelProvider.VIEW_MODEL_KEY] = "editor-session-holder"
            }
            extras.createSavedStateHandle()["editor-session"] = snapshot
            val saved = parcelCopy(original.save())
            original.destroy()
            val restored = owner(saved).editor("editor-session-holder")
            restored.prepare("0:book")
            assertEquals(expected, restored.selectedPage.value)
            assertNull(restored.inlineTextDraft.value)
            assertNull(restored.inlineInsertionId)
        }
    }

    @Test
    fun selectedPageSurvivesFreshOwnerAndAnchorsPlainTextImport() =
        restorePageSelectionAndImport(deleteSelectedPage = false)

    @Test
    fun deletedSelectedPageFallsBackBeforePlainTextImport() =
        restorePageSelectionAndImport(deleteSelectedPage = true)

    private fun restorePageSelectionAndImport(deleteSelectedPage: Boolean) {
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
        val notebook = runBlocking {
            repository.createNotebook(CreateNotebookRequest(
                "Saved selection ${System.nanoTime()}", CoverColor.SAGE, CoverPattern.SOLID,
                PaperTemplate.BLANK, PageOrientation.PORTRAIT, false,
            ))
        }
        val source = File(rule.activity.cacheDir, "saved-selection-${System.nanoTime()}.txt")
        var routeOwner by mutableStateOf<RegistryOwner?>(null)
        try {
            val first = runBlocking { repository.getPages(notebook).single().id }
            val second = runBlocking { repository.addPage(notebook, first) }
            val third = runBlocking { repository.addPage(notebook, second) }
            lateinit var original: RegistryOwner
            onMain {
                original = owner()
                routeOwner = original
            }
            val recognition = RecognitionModelManager()
            rule.setContent {
                routeOwner?.let { current ->
                    key(current) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides current) {
                            SeliaDocsTheme {
                                EditorRoute(
                                    notebookId = notebook,
                                    libraryGeneration = 0,
                                    recognitionModelManager = recognition,
                                    settings = AppSettings(pageTransition = false),
                                    onUpdateSettings = {},
                                    onBack = {},
                                    onSettings = {},
                                )
                            }
                        }
                    }
                }
            }
            lateinit var holder: EditorSessionHolder
            lateinit var originalEditor: EditorViewModel
            rule.runOnIdle {
                holder = original.editor("editor-session-holder")
                originalEditor = ViewModelProvider(holder)["editor", EditorViewModel::class.java]
            }
            rule.waitUntil(5_000) { originalEditor.state.value.pageContentReady }
            rule.runOnUiThread { holder.requestAction(EditorAction.SelectPage(second)) }
            rule.waitUntil(5_000) {
                originalEditor.state.value.pageContentReady && originalEditor.state.value.selectedPage?.id == second &&
                    holder.selectedPage.value == second && !holder.actionState.value.busy
            }
            lateinit var saved: Bundle
            onMain {
                assertNull(holder.inlineTextDraft.value)
                saved = parcelCopy(original.save())
                routeOwner = null
                original.destroy()
            }
            if (deleteSelectedPage) runBlocking { repository.deletePage(second) }
            lateinit var restored: RegistryOwner
            onMain {
                restored = owner(saved)
                val restoredHolder = restored.editor("editor-session-holder")
                assertNotSame(holder, restoredHolder)
                assertEquals(second, restoredHolder.selectedPage.value)
                routeOwner = restored
            }
            lateinit var restoredEditor: EditorViewModel
            rule.runOnIdle {
                holder = restored.editor("editor-session-holder")
                restoredEditor = ViewModelProvider(holder)["editor", EditorViewModel::class.java]
                assertNotSame(originalEditor, restoredEditor)
            }
            val anchor = if (deleteSelectedPage) first else second
            rule.waitUntil(5_000) {
                restoredEditor.state.value.pageContentReady && restoredEditor.state.value.selectedPage?.id == anchor &&
                    holder.selectedPage.value == anchor
            }
            source.writeText("Imported after restored page", Charsets.UTF_8)
            rule.runOnUiThread { holder.requestAction(EditorAction.ImportPlainText(Uri.fromFile(source))) }
            val expectedCount = if (deleteSelectedPage) 3 else 4
            rule.waitUntil(5_000) {
                restoredEditor.state.value.pages.size == expectedCount && restoredEditor.state.value.pageContentReady &&
                    !holder.actionState.value.busy
            }
            runBlocking {
                val pages = repository.getPages(notebook)
                val imported = pages.single { it.id !in setOf(first, second, third) }
                val expected = if (deleteSelectedPage) listOf(first, imported.id, third)
                    else listOf(first, second, imported.id, third)
                assertEquals(expected, pages.map { it.id })
                assertEquals("Imported after restored page", repository.getBlocks(imported.id).single().text)
            }
        } finally {
            onMain {
                routeOwner = null
                owners.forEach(RegistryOwner::destroy)
            }
            runBlocking { repository.deleteNotebook(notebook) }
            source.delete()
        }
    }

    @Test
    fun restoredRouteEditsTheOriginalElementOnItsNonFirstPage() {
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
        val notebook = runBlocking {
            repository.createNotebook(CreateNotebookRequest(
                "Saved draft ${System.nanoTime()}", CoverColor.SAGE, CoverPattern.SOLID,
                PaperTemplate.BLANK, PageOrientation.PORTRAIT, false,
            ))
        }
        try {
            val first = runBlocking { repository.getPages(notebook).single().id }
            val second = runBlocking { repository.addPage(notebook, first) }
            val elementId = runBlocking {
                repository.addElement(second, ElementDraft(ElementKind.TEXT, 40f, 80f, 300f, 60f, text = "Original text"))
            }
            lateinit var restored: RegistryOwner
            onMain {
                val original = owner()
                original.editor("editor-session-holder").apply {
                    prepare("0:$notebook")
                    selectedPage.value = first
                    assertTrue(beginInlineText(InlineTextDraft(second, elementId, CanvasPoint(40f, 80f), "Restored draft")))
                }
                val saved = parcelCopy(original.save())
                original.destroy()
                restored = owner(saved)
            }
            val recognition = RecognitionModelManager()
            rule.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides restored) {
                    SeliaDocsTheme {
                        EditorRoute(
                            notebookId = notebook,
                            libraryGeneration = 0,
                            recognitionModelManager = recognition,
                            settings = AppSettings(pageTransition = false),
                            onUpdateSettings = {},
                            onBack = {},
                            onSettings = {},
                        )
                    }
                }
            }
            lateinit var holder: EditorSessionHolder
            lateinit var editor: EditorViewModel
            rule.runOnIdle {
                holder = restored.editor("editor-session-holder")
                editor = ViewModelProvider(holder)["editor", EditorViewModel::class.java]
            }
            rule.waitUntil(5_000) { editor.state.value.pageContentReady && editor.state.value.selectedPage?.id == second }
            rule.onNodeWithTag("inline-text-editor")
                .assertIsEnabled().assertTextContains("Restored draft").performTextReplacement("Edited after restore")
            rule.runOnIdle { assertEquals(elementId, holder.inlineTextDraft.value?.elementId) }
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.waitUntil(5_000) { holder.inlineTextDraft.value == null && !holder.actionState.value.busy }
            runBlocking {
                assertTrue(repository.getElements(first).isEmpty())
                val saved = repository.getElements(second).single()
                assertEquals(elementId, saved.id)
                assertEquals("Edited after restore", saved.text)
            }
        } finally {
            onMain { owners.forEach(RegistryOwner::destroy) }
            runBlocking { repository.deleteNotebook(notebook) }
        }
    }

    @Test
    fun insertionCommittedAfterSnapshotIsNotInsertedAgainAfterRestore() =
        restorePendingInsertion(commitBeforeRestore = true)

    @Test
    fun insertionCancelledBeforeCommitRemainsRecoverableAfterRestore() =
        restorePendingInsertion(commitBeforeRestore = false)

    private fun restorePendingInsertion(commitBeforeRestore: Boolean) {
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
        val notebook = runBlocking {
            repository.createNotebook(CreateNotebookRequest(
                "Pending saved draft ${System.nanoTime()}", CoverColor.SAGE, CoverPattern.SOLID,
                PaperTemplate.BLANK, PageOrientation.PORTRAIT, false,
            ))
        }
        var routeOwner by mutableStateOf<RegistryOwner?>(null)
        val releaseGate = CompletableDeferred<Unit>()
        var gateOwner: Job? = null
        try {
            val first = runBlocking { repository.getPages(notebook).single().id }
            val page = runBlocking { repository.addPage(notebook, first) }
            lateinit var original: RegistryOwner
            lateinit var holder: EditorSessionHolder
            lateinit var insertionId: String
            onMain {
                original = owner()
                holder = original.editor("editor-session-holder")
                holder.prepare("0:$notebook")
                assertTrue(holder.beginInlineText(InlineTextDraft(page, null, CanvasPoint(40f, 80f), "Before typing")))
                insertionId = requireNotNull(holder.inlineInsertionId)
                routeOwner = original
            }
            val recognition = RecognitionModelManager()
            rule.setContent {
                routeOwner?.let { current ->
                    key(current) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides current) {
                            SeliaDocsTheme {
                                EditorRoute(
                                    notebookId = notebook,
                                    libraryGeneration = 0,
                                    recognitionModelManager = recognition,
                                    settings = AppSettings(pageTransition = false),
                                    onUpdateSettings = {},
                                    onBack = {},
                                    onSettings = {},
                                )
                            }
                        }
                    }
                }
            }
            lateinit var editor: EditorViewModel
            rule.runOnIdle { editor = ViewModelProvider(holder)["editor", EditorViewModel::class.java] }
            rule.waitUntil(5_000) { editor.state.value.pageContentReady && editor.state.value.selectedPage?.id == page }
            rule.onNodeWithTag("inline-text-editor").assertIsEnabled().performTextReplacement("Saved across snapshot")
            rule.runOnIdle { assertEquals(insertionId, holder.inlineInsertionId) }
            val acquired = CountDownLatch(1)
            gateOwner = CoroutineScope(Dispatchers.IO).launch {
                LibraryMutationGate.withLock {
                    acquired.countDown()
                    releaseGate.await()
                }
            }
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.waitUntil(5_000) { holder.actionState.value.saving }
            lateinit var saved: Bundle
            onMain { saved = parcelCopy(original.save()) }
            if (commitBeforeRestore) {
                releaseGate.complete(Unit)
                runBlocking { requireNotNull(gateOwner).join() }
                rule.waitUntil(5_000) { holder.inlineTextDraft.value == null && !holder.actionState.value.busy }
                runBlocking {
                    val committed = repository.getElements(page).single()
                    assertEquals(insertionId, committed.id)
                    val newer = committed.copy(text = "Newer committed content")
                    LibraryMutationGate.withLock { repository.updateElement(newer) }
                    val replayRepository = SeliaDocsRepository(
                        SeliaDocsDatabase.get(rule.activity.application),
                        clock = { error("A completed insertion must not touch timestamps") },
                    )
                    val replay = ElementDraft(ElementKind.TEXT, 40f, 80f, 300f, 60f, text = "Stale snapshot text")
                    assertEquals(newer, LibraryMutationGate.withLock { replayRepository.addElementEntity(page, replay, insertionId) })
                    assertTrue(runCatching {
                        LibraryMutationGate.withLock { repository.addElementEntity(first, replay, insertionId) }
                    }.exceptionOrNull() is IllegalArgumentException)
                    val image = LibraryMutationGate.withLock {
                        repository.addElementEntity(first, ElementDraft(ElementKind.IMAGE, 10f, 10f, 40f, 40f, assetId = "saved-state-test-${System.nanoTime()}.png"))
                    }
                    assertTrue(runCatching {
                        LibraryMutationGate.withLock { repository.addElementEntity(first, replay, image.id) }
                    }.exceptionOrNull() is IllegalArgumentException)
                    assertEquals(image, repository.getElement(image.id))
                }
            }
            onMain {
                routeOwner = null
                original.destroy()
            }
            releaseGate.complete(Unit)
            runBlocking { requireNotNull(gateOwner).join() }
            if (!commitBeforeRestore) runBlocking { assertTrue(repository.getElements(page).isEmpty()) }
            lateinit var restored: RegistryOwner
            onMain {
                restored = owner(saved)
                routeOwner = restored
            }
            rule.runOnIdle {
                holder = restored.editor("editor-session-holder")
                editor = ViewModelProvider(holder)["editor", EditorViewModel::class.java]
            }
            rule.waitUntil(5_000) { editor.state.value.pageContentReady && editor.state.value.selectedPage?.id == page }
            if (commitBeforeRestore) {
                rule.waitUntil(5_000) { holder.inlineTextDraft.value == null }
                rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
                rule.runOnUiThread { holder.requestAction(EditorAction.FinishText) }
            } else {
                rule.runOnIdle { assertEquals(insertionId, holder.inlineInsertionId) }
                rule.onNodeWithTag("inline-text-editor").assertIsEnabled().assertTextContains("Saved across snapshot").performImeAction()
            }
            rule.waitUntil(5_000) { holder.inlineTextDraft.value == null && !holder.actionState.value.busy }
            runBlocking {
                val stored = repository.getElements(page).single()
                assertEquals(insertionId, stored.id)
                assertEquals(if (commitBeforeRestore) "Newer committed content" else "Saved across snapshot", stored.text)
            }
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner?.join() }
            onMain {
                routeOwner = null
                owners.forEach(RegistryOwner::destroy)
            }
            runBlocking { repository.deleteNotebook(notebook) }
        }
    }

    private fun draftSnapshot() = Bundle().apply {
        putString("sessionKey", "0:book")
        putString("pageId", "page")
        putString("text", "Draft")
        putFloatArray("origin", floatArrayOf(20f, 30f))
    }

    private fun owner(saved: Bundle? = null) = RegistryOwner(saved).also(owners::add)

    private fun parcelCopy(saved: Bundle): Bundle {
        val output = Parcel.obtain()
        val bytes = try {
            output.writeBundle(saved)
            output.marshall()
        } finally { output.recycle() }
        assertTrue("Two maximum-length drafts must fit within 64 KiB", bytes.size < 64 * 1_024)
        val input = Parcel.obtain()
        return try {
            input.unmarshall(bytes, 0, bytes.size)
            input.setDataPosition(0)
            requireNotNull(input.readBundle(javaClass.classLoader))
        } finally { input.recycle() }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class RegistryOwner(saved: Bundle?) : SavedStateRegistryOwner, ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val lifecycle = LifecycleRegistry(this)
        override val viewModelStore = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry
        override val defaultViewModelProviderFactory get() = SavedStateViewModelFactory()
        override val defaultViewModelCreationExtras get() = extras()

        init {
            controller.performAttach()
            enableSavedStateHandles()
            controller.performRestore(saved)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun extras() = MutableCreationExtras().apply {
            this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@RegistryOwner
            this[VIEW_MODEL_STORE_OWNER_KEY] = this@RegistryOwner
        }

        fun editor(key: String): EditorSessionHolder = model(key, EditorSessionHolder::class.java)

        fun workspace(): EditorWorkspaceHolder = model("editor-workspace", EditorWorkspaceHolder::class.java)

        private fun <T : ViewModel> model(key: String, type: Class<T>): T =
            ViewModelProvider(viewModelStore, SavedStateViewModelFactory(), extras())[key, type]

        fun save(): Bundle = Bundle().also(controller::performSave)

        fun destroy() {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            viewModelStore.clear()
        }
    }
}
