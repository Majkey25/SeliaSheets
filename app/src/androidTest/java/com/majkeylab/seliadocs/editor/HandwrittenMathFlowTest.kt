package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.recognition.InkMathCandidate
import com.majkeylab.seliadocs.recognition.InkTextRecognizer
import com.majkeylab.seliadocs.recognition.RecognitionCandidate
import com.majkeylab.seliadocs.recognition.RecognitionLanguage
import com.majkeylab.seliadocs.recognition.RecognitionRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandwrittenMathFlowTest {
    @Test
    fun pageSwitchDropsInFlightHandwritingConversion() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue()
        withEditor(provider::create) { viewModel, firstPageId ->
            onMain(viewModel::addPage)
            val secondPageId =
                await(viewModel, "second page") { it.pages.size == 2 }.pages.single { it.id != firstPageId }.id
            onMain { viewModel.selectPage(firstPageId) }
            await(viewModel, "first page selected") { it.selectedPage?.id == firstPageId }
            onMain {
                viewModel.addStroke(
                    firstPageId,
                    rawStroke(),
                    shapeAssist = false,
                    handwritingRecognition = false,
                )
            }
            await(viewModel, "conversion source") { it.strokes.size == 1 }
            onMain {
                viewModel.selectContent(
                    firstPageId,
                    listOf(
                        CanvasPoint(20f, 60f),
                        CanvasPoint(150f, 60f),
                        CanvasPoint(150f, 120f),
                        CanvasPoint(20f, 120f),
                        CanvasPoint(20f, 60f),
                    ),
                )
                viewModel.recognizeSelectedHandwriting(RecognitionLanguage.ENGLISH)
            }
            invocation.awaitStarted()
            onMain { viewModel.selectPage(secondPageId) }
            invocation.complete(listOf(RecognitionCandidate("Stale conversion")))
            invocation.awaitClosed()
            drainMutationGate()

            val state = viewModel.state.value
            assertEquals(secondPageId, state.selectedPage?.id)
            assertTrue(state.handwritingCandidates.isEmpty())
        }
    }

    @Test
    fun selectedHandwritingAddsChosenPageTextAndKeepsInk() = runBlocking {
        val provider = ControlledProvider()
        val invocation =
            provider.enqueue(
                listOf(
                    RecognitionCandidate("Lecture notes"),
                    RecognitionCandidate("Lecture votes"),
                ),
            )
        withEditor(provider::create) { viewModel, pageId ->
            onMain {
                viewModel.addStroke(
                    pageId,
                    rawStroke(),
                    shapeAssist = false,
                    handwritingRecognition = false,
                )
            }
            await(viewModel, "raw handwriting") { it.strokes.size == 1 }
            onMain {
                viewModel.selectContent(
                    pageId,
                    listOf(
                        CanvasPoint(20f, 60f),
                        CanvasPoint(150f, 60f),
                        CanvasPoint(150f, 120f),
                        CanvasPoint(20f, 120f),
                        CanvasPoint(20f, 60f),
                    ),
                )
                viewModel.recognizeSelectedHandwriting(RecognitionLanguage.ENGLISH)
            }

            invocation.awaitStarted()
            invocation.awaitClosed()
            await(viewModel, "handwriting candidates") { it.handwritingCandidates.size == 2 }
            onMain { viewModel.addHandwritingCandidateToPage("Lecture notes") }

            val converted = await(viewModel, "page text conversion") {
                it.selectedBlocks.singleOrNull()?.text == "Lecture notes" && it.handwritingCandidates.isEmpty()
            }
            assertEquals(1, converted.strokes.size)
            assertTrue(converted.canUndo)
            onMain(viewModel::undo)
            val undone = await(viewModel, "conversion undo") { it.selectedBlocks.isEmpty() }
            assertEquals(1, undone.strokes.size)
        }
    }

    @Test
    fun recognizedExpressionUsesAssignmentsFromPageText() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue(listOf(RecognitionCandidate("width*height=")))
        withEditor(provider::create) { viewModel, pageId ->
            onMain { viewModel.updatePageText(pageId, "width=12\nheight=4") }
            await(viewModel, "math assignments") {
                it.selectedBlocks.singleOrNull()?.text == "width=12\nheight=4"
            }

            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            invocation.awaitClosed()

            val recognized = await(viewModel, "variable math") { it.elements.size == 1 }
            val math = recognized.elements.single()
            assertEquals("width*height=", math.expression)
            assertEquals("width*height = 48", math.resultText)
        }
    }

    @Test
    fun uniqueResultPreservesInkAndHasOneStepUndoRedo() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue(listOf(RecognitionCandidate("2+3=")))
        withEditor(provider::create) { viewModel, pageId ->
            onMain {
                viewModel.addStroke(
                    pageId,
                    rawStroke(),
                    shapeAssist = false,
                    handwritingRecognition = true,
                    recognitionLanguage = RecognitionLanguage.ENGLISH,
                )
            }

            invocation.awaitStarted()
            invocation.awaitClosed()
            val inserted = await(viewModel, "unique math") {
                it.strokes.size == 1 && it.elements.singleOrNull()?.kind == ElementKind.MATH.name
            }
            assertEquals("2+3=", inserted.elements.single().expression)
            assertEquals("2+3 = 5", inserted.elements.single().resultText)
            assertTrue(inserted.elements.single().x > 120f)

            onMain(viewModel::undo)
            await(viewModel, "math undo") {
                it.strokes.size == 1 && it.elements.isEmpty() && it.canRedo
            }

            onMain(viewModel::redo)
            await(viewModel, "math redo") {
                it.strokes.size == 1 && it.elements.singleOrNull()?.kind == ElementKind.MATH.name
            }
        }
        assertEquals(1, provider.calls.get())
        provider.assertNoUnexpectedCall()
    }

    @Test
    fun ambiguousResultWaitsForChoiceAndDismissAddsNothing() = runBlocking {
        val provider = ControlledProvider()
        val first =
            provider.enqueue(listOf(RecognitionCandidate("2+3="), RecognitionCandidate("2+8=")))
        val second =
            provider.enqueue(listOf(RecognitionCandidate("4+1="), RecognitionCandidate("4+7=")))
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        withEditor(
            recognizerProvider = provider::create,
            recognitionCommitBoundary = {
                commitStarted.complete(Unit)
                releaseCommit.await()
            },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            val ambiguous = await(viewModel, "ambiguous math") {
                it.ambiguousMathCandidates.size == 2
            }
            first.awaitStarted()
            first.awaitClosed()
            assertTrue(ambiguous.elements.isEmpty())

            onMain {
                viewModel.chooseMathCandidate(InkMathCandidate("2+8=", "10"))
            }
            commitStarted.await()
            val selected = await(viewModel, "chosen math") { it.elements.size == 1 }
            assertEquals("2+8=", selected.elements.single().expression)
            assertEquals(1, selected.strokes.size)

            try {
                onMain {
                    viewModel.addStroke(
                        pageId,
                        rawStroke(180f),
                        shapeAssist = false,
                        handwritingRecognition = true,
                        recognitionLanguage = RecognitionLanguage.ENGLISH,
                    )
                }
            } finally {
                releaseCommit.complete(Unit)
            }
            await(viewModel, "second ambiguity") { it.ambiguousMathCandidates.size == 2 }
            second.awaitStarted()
            second.awaitClosed()
            onMain(viewModel::dismissMathCandidates)

            val dismissed = viewModel.state.value
            assertTrue(dismissed.ambiguousMathCandidates.isEmpty())
            assertEquals(1, dismissed.elements.size)
            assertEquals(2, dismissed.strokes.size)
        }
    }

    @Test
    fun staleCompletionAfterNewInkInsertsNothing() = runBlocking {
        val provider = ControlledProvider()
        val first = provider.enqueue()
        val second = provider.enqueue()
        withEditor(provider::create) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            first.awaitStarted()
            addRecognizedStroke(viewModel, pageId, rawStroke(180f))
            first.complete(listOf(RecognitionCandidate("2+3=")))
            first.awaitClosed()
            second.awaitStarted()
            second.complete(emptyList())
            second.awaitClosed()
            drainMutationGate()

            assertEquals(2, viewModel.state.value.strokes.size)
            assertTrue(viewModel.state.value.elements.isEmpty())
            assertEquals(2, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun staleCompletionAfterEraseInsertsNothing() = runBlocking {
        val provider = ControlledProvider()
        val completion = provider.enqueue()
        withEditor(provider::create) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            completion.awaitStarted()
            onMain { viewModel.setEraserMode(EraserMode.STROKE) }
            onMain { viewModel.eraseStrokes(pageId, listOf(CanvasPoint(80f, 80f))) }
            await(viewModel, "erased ink") { it.strokes.isEmpty() }
            completion.complete(listOf(RecognitionCandidate("2+3=")))
            completion.awaitClosed()
            drainMutationGate()

            assertTrue(viewModel.state.value.elements.isEmpty())
            assertEquals(1, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun staleCompletionAfterUndoInsertsNothing() = runBlocking {
        val provider = ControlledProvider()
        val completion = provider.enqueue()
        withEditor(provider::create) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            completion.awaitStarted()
            onMain(viewModel::undo)
            await(viewModel, "stroke undo") { it.strokes.isEmpty() }
            completion.complete(listOf(RecognitionCandidate("2+3=")))
            completion.awaitClosed()
            drainMutationGate()

            assertTrue(viewModel.state.value.elements.isEmpty())
            assertEquals(1, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun staleCompletionAfterPageSwitchInsertsNothing() = runBlocking {
        val provider = ControlledProvider()
        val completion = provider.enqueue()
        withEditor(provider::create) { viewModel, firstPageId ->
            onMain(viewModel::addPage)
            val secondPageId = await(viewModel, "second page") {
                it.pages.size == 2 && it.selectedPage?.id != firstPageId
            }.selectedPage!!.id
            onMain { viewModel.selectPage(firstPageId) }
            await(viewModel, "first page") { it.selectedPage?.id == firstPageId }
            addRecognizedStroke(viewModel, firstPageId, rawStroke())
            completion.awaitStarted()

            onMain { viewModel.selectPage(secondPageId) }
            completion.complete(listOf(RecognitionCandidate("2+3=")))
            completion.awaitClosed()
            drainMutationGate()

            assertEquals(secondPageId, viewModel.state.value.selectedPage?.id)
            val application = ApplicationProvider.getApplicationContext<Application>()
            val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
            assertTrue(repository.getElements(firstPageId).isEmpty())
            assertEquals(1, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun missingModelAndProviderFailurePreserveInkWithoutGeneralFailure() = runBlocking {
        val calls = AtomicInteger()
        val recognizeStarted = CompletableDeferred<Unit>()
        val recognizerClosed = CompletableDeferred<Unit>()
        withEditor(
            recognizerProvider = {
                when (calls.getAndIncrement()) {
                    0 -> error("Digital ink model is not downloaded.")
                    1 -> Unit
                    else -> throw AssertionError("Unexpected recognizer provider call")
                }
                object : InkTextRecognizer {
                    override suspend fun recognize(request: RecognitionRequest): List<RecognitionCandidate> {
                        recognizeStarted.complete(Unit)
                        error("recognizer failed")
                    }

                    override fun close() {
                        recognizerClosed.complete(Unit)
                    }
                }
            },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            val missing = await(viewModel, "missing model") { it.recognitionMessage != null }
            assertEquals(1, missing.strokes.size)
            assertFalse(missing.failed)

            addRecognizedStroke(viewModel, pageId, rawStroke(180f))
            val failed = await(viewModel, "recognizer failure") {
                it.strokes.size == 2 && it.recognitionMessage == "Handwriting recognition unavailable."
            }
            recognizeStarted.await()
            recognizerClosed.await()
            assertTrue(failed.elements.isEmpty())
            assertFalse(failed.failed)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun disabledRecognitionNeverCreatesProvider() = runBlocking {
        val provider = ControlledProvider()
        withEditor(provider::create) { viewModel, pageId ->
            onMain {
                viewModel.addStroke(
                    pageId,
                    rawStroke(),
                    shapeAssist = false,
                    handwritingRecognition = false,
                    recognitionLanguage = RecognitionLanguage.ENGLISH,
                )
            }
            await(viewModel, "raw ink") { it.strokes.size == 1 }
            drainMutationGate()

            assertEquals(0, provider.calls.get())
            provider.assertNoUnexpectedCall()
            assertFalse(viewModel.state.value.failed)
        }
    }

    @Test
    fun shapeAssistedHeldLineNeverCreatesProvider() = runBlocking {
        val provider = ControlledProvider()
        withEditor(provider::create) { viewModel, pageId ->
            onMain {
                viewModel.addStroke(
                    pageId,
                    heldLine(),
                    shapeAssist = true,
                    handwritingRecognition = true,
                    recognitionLanguage = RecognitionLanguage.ENGLISH,
                )
            }
            val shape = await(viewModel, "held line") {
                it.strokes.isEmpty() && it.elements.singleOrNull()?.kind == ElementKind.SHAPE.name
            }
            drainMutationGate()

            assertEquals(0, provider.calls.get())
            provider.assertNoUnexpectedCall()
            assertEquals(ShapeKind.LINE.name, shape.elements.single().shapeKind)
        }
    }

    @Test
    fun twoStrokeBurstUsesDrawingOrderAndOneProvider() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue(emptyList())
        val debounce = ManualDebounce()
        withEditor(provider::create, debounce::await) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            debounce.next()
            addRecognizedStroke(viewModel, pageId, rawStroke(180f))
            debounce.next().release()
            val request = invocation.awaitStarted()
            invocation.awaitClosed()
            val state = await(viewModel, "two raw strokes") { it.strokes.size == 2 }

            assertEquals(1, provider.calls.get())
            assertEquals(state.strokes.map { it.id }, request.fingerprints.map { it.strokeId })
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun backToBackQueuedStrokesShareBurstAndKeepCallbackOrder() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue(emptyList())
        val releaseDebounce = CompletableDeferred<Unit>()
        withEditor(provider::create, releaseDebounce::await) { viewModel, pageId ->
            holdMutationGate {
                onMain {
                    viewModel.addStroke(
                        pageId,
                        rawStroke(),
                        shapeAssist = false,
                        handwritingRecognition = true,
                        recognitionLanguage = RecognitionLanguage.ENGLISH,
                    )
                    viewModel.addStroke(
                        pageId,
                        rawStroke(180f),
                        shapeAssist = false,
                        handwritingRecognition = true,
                        recognitionLanguage = RecognitionLanguage.ENGLISH,
                    )
                }
            }
            val state = await(viewModel, "queued burst strokes") { it.strokes.size == 2 }
            releaseDebounce.complete(Unit)
            val request = invocation.awaitStarted()
            invocation.awaitClosed()

            assertEquals(state.strokes.map { it.id }, request.fingerprints.map { it.strokeId })
            assertEquals(2, request.strokes.size)
            assertEquals(1, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun overlongStrokeSkipsRecognition() = runBlocking {
        val provider = ControlledProvider()
        withEditor(provider::create) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke(lastTimeMillis = 10_001L))
            drainMutationGate()

            assertEquals(1, viewModel.state.value.strokes.size)
            assertEquals(0, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun candidateChoiceInvalidationAfterWriteRollsBackMathAndKeepsRawHistory() = runBlocking {
        val provider = ControlledProvider()
        val invocation =
            provider.enqueue(listOf(RecognitionCandidate("2+3="), RecognitionCandidate("2+8=")))
        val committed = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        withEditor(
            recognizerProvider = provider::create,
            recognitionCommitBoundary = {
                committed.complete(Unit)
                releaseCommit.await()
            },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            invocation.awaitClosed()
            val ambiguous = await(viewModel, "candidate choice") { it.ambiguousMathCandidates.size == 2 }

            onMain { viewModel.chooseMathCandidate(ambiguous.ambiguousMathCandidates.last()) }
            committed.await()
            try {
                onMain { viewModel.selectTool(EditorTool.TYPE) }
            } finally {
                releaseCommit.complete(Unit)
            }
            drainMutationGate()
            val rolledBack = await(viewModel, "rolled back candidate") {
                it.elements.isEmpty() && it.strokes.size == 1
            }
            assertEquals(null, rolledBack.recognitionMessage)
            assertTrue(rolledBack.canUndo)
            onMain(viewModel::undo)

            val undone = await(viewModel, "raw undo") { it.elements.isEmpty() && it.strokes.isEmpty() }
            assertTrue(undone.canRedo)
        }
    }

    @Test
    fun uniqueInvalidationAfterPostInsertSnapshotRollsBackMathAndKeepsRawHistory() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue()
        val committed = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        withEditor(
            recognizerProvider = provider::create,
            recognitionCommitBoundary = {
                committed.complete(Unit)
                releaseCommit.await()
            },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            invocation.complete(listOf(RecognitionCandidate("2+3=")))
            invocation.awaitClosed()
            committed.await()
            try {
                onMain { viewModel.selectTool(EditorTool.TYPE) }
            } finally {
                releaseCommit.complete(Unit)
            }
            drainMutationGate()
            val rolledBack = await(viewModel, "rolled back unique result") {
                it.elements.isEmpty() && it.strokes.size == 1
            }
            assertEquals(null, rolledBack.recognitionMessage)
            assertTrue(rolledBack.canUndo)
            onMain(viewModel::undo)

            val undone = await(viewModel, "unique raw undo") {
                it.elements.isEmpty() && it.strokes.isEmpty()
            }
            assertTrue(undone.canRedo)
        }
    }

    @Test
    fun settingsRoundTripRetainsEditorViewModelAndHistory() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        val holder = EditorSessionHolder()
        holder.prepare("0:$notebookId")
        val factory = viewModelFactory {
            initializer { EditorViewModel(application, notebookId) }
        }
        try {
            lateinit var original: EditorViewModel
            onMain { original = ViewModelProvider(holder, factory)[EditorViewModel::class.java] }
            val pageId = await(original, "settings page") { it.selectedPage != null }.selectedPage!!.id
            onMain {
                original.addStroke(
                    pageId,
                    rawStroke(),
                    shapeAssist = false,
                    handwritingRecognition = false,
                )
            }
            await(original, "settings history") { it.strokes.size == 1 && it.canUndo }

            onMain {
                assertTrue(holder.beginClose(EditorCloseIntent.SETTINGS))
                holder.completeClose(saved = true)
                holder.consumeCompletedClose()
                holder.prepare("0:$notebookId")
            }
            lateinit var resumed: EditorViewModel
            onMain { resumed = ViewModelProvider(holder, factory)[EditorViewModel::class.java] }

            assertSame(original, resumed)
            onMain(resumed::undo)
            await(resumed, "settings retained undo") { it.strokes.isEmpty() }
        } finally {
            onMain(holder.viewModelStore::clear)
            repository.deleteNotebook(notebookId)
        }
        Unit
    }

    @Test
    fun newInkInvalidatesInferenceBeforeItsPersistence() = runBlocking {
        val provider = ControlledProvider()
        val stale = provider.enqueue()
        val current = provider.enqueue(emptyList())
        withEditor(provider::create) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            stale.awaitStarted()

            holdMutationGate {
                stale.complete(listOf(RecognitionCandidate("2+3=")))
                stale.awaitClosed()
                onMain {
                    viewModel.addStroke(
                        pageId,
                        rawStroke(180f),
                        shapeAssist = false,
                        handwritingRecognition = true,
                        recognitionLanguage = RecognitionLanguage.ENGLISH,
                    )
                }
            }

            current.awaitStarted()
            current.awaitClosed()
            val state = await(viewModel, "new ink race") { it.strokes.size == 2 }
            assertTrue(state.elements.isEmpty())
            assertEquals(2, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun toolChangeBeforeStrokePersistenceCannotRearmRecognition() = runBlocking {
        val provider = ControlledProvider()
        withEditor(provider::create) { viewModel, pageId ->
            holdMutationGate {
                onMain {
                    viewModel.addStroke(
                        pageId,
                        rawStroke(),
                        shapeAssist = false,
                        handwritingRecognition = true,
                        recognitionLanguage = RecognitionLanguage.ENGLISH,
                    )
                    viewModel.selectTool(EditorTool.TYPE)
                }
            }
            await(viewModel, "tool race raw ink") { it.strokes.size == 1 }
            drainMutationGate()

            assertEquals(0, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun multiStrokeDurationAcceptsTenSecondsAndRejectsTenSecondsPlusOne() = runBlocking {
        val acceptedProvider = ControlledProvider()
        val acceptedInvocation = acceptedProvider.enqueue(emptyList())
        val acceptedDebounce = ManualDebounce()
        withEditor(acceptedProvider::create, acceptedDebounce::await) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke(lastTimeMillis = 5_000L))
            acceptedDebounce.next()
            addRecognizedStroke(viewModel, pageId, rawStroke(180f, lastTimeMillis = 4_999L))
            acceptedDebounce.next().release()
            acceptedInvocation.awaitStarted()
            acceptedInvocation.awaitClosed()

            assertEquals(1, acceptedProvider.calls.get())
            acceptedProvider.assertNoUnexpectedCall()
        }

        val rejectedProvider = ControlledProvider()
        val rejectedDebounce = ManualDebounce()
        withEditor(rejectedProvider::create, rejectedDebounce::await) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke(lastTimeMillis = 5_000L))
            rejectedDebounce.next()
            addRecognizedStroke(viewModel, pageId, rawStroke(180f, lastTimeMillis = 5_000L))
            rejectedDebounce.next().release()
            drainMutationGate()

            assertEquals(0, rejectedProvider.calls.get())
            rejectedProvider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun corruptPersistedStrokeShowsUnavailableAndPreservesPayload() = runBlocking {
        val provider = ControlledProvider()
        val debounce = ManualDebounce()
        withEditor(provider::create, debounce::await) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            val gate = debounce.next()
            val application = ApplicationProvider.getApplicationContext<Application>()
            val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
            val corruptInputs = byteArrayOf(0x01, 0x02, 0x03)
            LibraryMutationGate.withLock {
                val stroke = repository.getStrokes(pageId).single()
                repository.replaceStrokes(pageId, listOf(stroke.copy(inputs = corruptInputs)))
            }

            gate.release()
            val failed = await(viewModel, "corrupt recognition payload") {
                it.recognitionMessage == "Handwriting recognition unavailable."
            }

            assertTrue(repository.getStrokes(pageId).single().inputs.contentEquals(corruptInputs))
            assertTrue(failed.elements.isEmpty())
            assertFalse(failed.failed)
            assertEquals(0, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun closeBeforeUniqueWritePreservesRawInkOnly() = runBlocking {
        val mutationAllowed = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider = ControlledProvider()
        val invocation = provider.enqueue()
        withEditor(
            recognizerProvider = provider::create,
            mutationAllowed = mutationAllowed::get,
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            mutationAllowed.set(false)
            invocation.complete(listOf(RecognitionCandidate("2+3=")))
            invocation.awaitClosed()
            drainMutationGate()

            val state = viewModel.state.value
            assertEquals(1, state.strokes.size)
            assertTrue(state.elements.isEmpty())
            assertEquals(1, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun strokeThirtyThreeRecognizesNewestThirtyTwoInOrder() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue(emptyList())
        val debounce = ManualDebounce()
        withEditor(provider::create, debounce::await) { viewModel, pageId ->
            repeat(33) { index ->
                addRecognizedStroke(viewModel, pageId, rawStroke(index * 3f))
                val gate = debounce.next()
                if (index == 32) gate.release()
            }
            val request = invocation.awaitStarted()
            invocation.awaitClosed()
            val state = await(viewModel, "33 raw strokes") { it.strokes.size == 33 }

            assertEquals(32, request.fingerprints.size)
            assertEquals(
                state.strokes.takeLast(32).map { it.id },
                request.fingerprints.map { it.strokeId },
            )
            assertEquals(1, provider.calls.get())
            provider.assertNoUnexpectedCall()
        }
    }

    @Test
    fun postInsertFailureRollsBackMathAndKeepsRawHistory() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue()
        withEditor(
            recognizerProvider = provider::create,
            recognitionCommitBoundary = { error("Injected post-insert failure") },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            val application = ApplicationProvider.getApplicationContext<Application>()
            val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
            val expectedStroke = repository.getStrokes(pageId).single()
            val expectedElements = repository.getElements(pageId)
            val expectedBlocks = repository.getBlocks(pageId)
            invocation.complete(listOf(RecognitionCandidate("2+3=")))
            invocation.awaitClosed()
            val failed = await(viewModel, "rolled back recognition") {
                it.recognitionMessage == "Handwriting recognition unavailable."
            }
            val restoredStroke = repository.getStrokes(pageId).single()

            assertEquals(expectedStroke.copy(inputs = restoredStroke.inputs), restoredStroke)
            assertArrayEquals(expectedStroke.inputs, restoredStroke.inputs)
            assertEquals(expectedElements, repository.getElements(pageId))
            assertEquals(expectedBlocks, repository.getBlocks(pageId))
            assertTrue(failed.canUndo)
            onMain(viewModel::undo)
            await(viewModel, "raw undo after rollback") { it.strokes.isEmpty() && it.elements.isEmpty() }
        }
    }

    @Test
    fun rollbackFailureInvalidatesHistoryAndSetsGeneralFailure() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val provider = ControlledProvider()
        val invocation =
            provider.enqueue(
                listOf(
                    RecognitionCandidate("2+3="),
                    RecognitionCandidate("2+8="),
                ),
            )
        lateinit var pageToDelete: String
        val boundaryDeletedPage = CompletableDeferred<String>()
        withEditor(
            recognizerProvider = provider::create,
            recognitionCommitBoundary = {
                repository.deletePage(pageToDelete)
                boundaryDeletedPage.complete(pageToDelete)
                error("Injected post-insert failure")
            },
        ) { viewModel, firstPageId ->
            onMain(viewModel::addPage)
            val secondPageId = await(viewModel, "rollback second page") {
                it.pages.size == 2 && it.selectedPage?.id != firstPageId
            }.selectedPage!!.id
            pageToDelete = secondPageId
            addRecognizedStroke(viewModel, secondPageId, rawStroke())
            invocation.awaitStarted()
            invocation.awaitClosed()
            val ambiguity = await(viewModel, "rollback ambiguity") {
                it.ambiguousMathCandidates.size == 2
            }
            onMain { viewModel.chooseMathCandidate(ambiguity.ambiguousMathCandidates.first()) }
            assertEquals(secondPageId, boundaryDeletedPage.await())

            val failed = await(viewModel, "rollback failure") {
                it.failed &&
                    !it.canUndo &&
                    !it.canRedo &&
                    it.recognitionMessage == "Handwriting recognition unavailable." &&
                    it.pages.none { page -> page.id == secondPageId } &&
                    it.selectedPage?.id == firstPageId
            }
            assertTrue(failed.failed)
            assertFalse(failed.canUndo)
            assertFalse(failed.canRedo)
            assertEquals(firstPageId, failed.selectedPage?.id)
            assertTrue(repository.getElements(secondPageId).isEmpty())
            assertEquals(1, failed.pages.size)
        }
    }

    @Test
    fun ambiguityChoicePostInsertFailureRollsBackAndPublishesMessage() = runBlocking {
        val provider = ControlledProvider()
        val invocation =
            provider.enqueue(
                listOf(
                    RecognitionCandidate("2+3="),
                    RecognitionCandidate("2+8="),
                ),
            )
        withEditor(
            recognizerProvider = provider::create,
            recognitionCommitBoundary = { error("Injected post-insert failure") },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            invocation.awaitClosed()
            val ambiguity = await(viewModel, "choice rollback ambiguity") {
                it.ambiguousMathCandidates.size == 2
            }

            onMain { viewModel.chooseMathCandidate(ambiguity.ambiguousMathCandidates.last()) }
            val failed = await(viewModel, "choice rollback") {
                it.recognitionMessage == "Handwriting recognition unavailable." && it.elements.isEmpty()
            }

            assertFalse(failed.failed)
            assertEquals(1, failed.strokes.size)
            assertTrue(failed.canUndo)
            onMain(viewModel::undo)
            await(viewModel, "choice rollback undo") { it.strokes.isEmpty() && it.elements.isEmpty() }
        }
    }

    @Test
    fun toolInvalidationInsideNonCancellableBeforeWriteDiscardsResult() = runBlocking {
        val provider = ControlledProvider()
        val invocation = provider.enqueue()
        val beforeWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        withEditor(
            recognizerProvider = provider::create,
            recognitionWriteBoundary = {
                beforeWrite.complete(Unit)
                releaseWrite.await()
            },
        ) { viewModel, pageId ->
            addRecognizedStroke(viewModel, pageId, rawStroke())
            invocation.awaitStarted()
            invocation.complete(listOf(RecognitionCandidate("2+3=")))
            invocation.awaitClosed()
            beforeWrite.await()
            try {
                onMain { viewModel.selectTool(EditorTool.TYPE) }
            } finally {
                releaseWrite.complete(Unit)
            }
            drainMutationGate()

            val state = viewModel.state.value
            assertEquals(1, state.strokes.size)
            assertTrue(state.elements.isEmpty())
            assertFalse(state.failed)
        }
    }

    private suspend fun addRecognizedStroke(
        viewModel: EditorViewModel,
        pageId: String,
        stroke: Stroke,
    ) {
        onMain {
            viewModel.addStroke(
                pageId,
                stroke,
                shapeAssist = false,
                handwritingRecognition = true,
                recognitionLanguage = RecognitionLanguage.ENGLISH,
            )
        }
        await(viewModel, "persisted stroke") { state ->
            state.strokes.any { it.pageId == pageId && it.inputs.contentEquals(InkCodec.encode(stroke).inputs) }
        }
    }

    private suspend fun withEditor(
        recognizerProvider: suspend (RecognitionLanguage) -> InkTextRecognizer,
        recognitionDelay: suspend () -> Unit = {},
        recognitionWriteBoundary: suspend () -> Unit = {},
        recognitionCommitBoundary: suspend () -> Unit = {},
        mutationAllowed: () -> Boolean = { true },
        block: suspend (EditorViewModel, String) -> Unit,
    ) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        val factory =
            viewModelFactory {
                initializer {
                    EditorViewModel(
                        application,
                        notebookId,
                        mutationAllowed = mutationAllowed,
                        recognizerProvider = recognizerProvider,
                        recognitionDelay = recognitionDelay,
                        recognitionWriteBoundary = recognitionWriteBoundary,
                        recognitionCommitBoundary = recognitionCommitBoundary,
                    )
                }
            }
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = ViewModelProvider(owner, factory)[EditorViewModel::class.java] }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id
            block(viewModel, pageId)
        } finally {
            onMain(owner.viewModelStore::clear)
            repository.deleteNotebook(notebookId)
        }
    }

    private suspend fun await(
        viewModel: EditorViewModel,
        label: String,
        predicate: (EditorUiState) -> Boolean,
    ): EditorUiState {
        val result = withTimeoutOrNull(30_000) { viewModel.state.first(predicate) }
        if (result != null) return result
        val state = viewModel.state.value
        throw AssertionError(
            "Timed out waiting for $label: strokes=${state.strokes.size}, " +
                "elements=${state.elements.size}, canUndo=${state.canUndo}, " +
                "canRedo=${state.canRedo}, failed=${state.failed}",
        )
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun rawStroke(
        offsetX: Float = 0f,
        lastTimeMillis: Long = 200L,
    ): Stroke =
        Stroke(
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 40f + offsetX, 80f, 0L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 80f + offsetX, 96f, 100L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 120f + offsetX, 80f, lastTimeMillis, 0.01f, 0.7f, 0.2f, 0.3f),
        )

    private fun heldLine(): Stroke =
        Stroke(
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 40f, 80f, 0L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 120f, 81f, 160L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 220f, 80f, 260L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 220f, 80f, 600L, 0.01f, 0.7f, 0.2f, 0.3f),
        )

    private fun request() =
        CreateNotebookRequest(
            "Handwriting ${System.nanoTime()}",
            CoverColor.PERIWINKLE,
            CoverPattern.SOLID,
            PaperTemplate.BLANK,
            PageOrientation.PORTRAIT,
            false,
        )

    private class ControlledProvider {
        val calls = AtomicInteger()
        val requests = mutableListOf<RecognitionRequest>()
        val unexpectedCall = CompletableDeferred<Unit>()
        private val invocations = ArrayDeque<Invocation>()

        fun enqueue(value: List<RecognitionCandidate>? = null): Invocation =
            Invocation().also { invocation ->
                value?.let(invocation::complete)
                invocations.addLast(invocation)
            }

        suspend fun create(language: RecognitionLanguage): InkTextRecognizer {
            assertEquals(RecognitionLanguage.ENGLISH, language)
            calls.incrementAndGet()
            val invocation =
                invocations.removeFirstOrNull()
                    ?: run {
                        unexpectedCall.complete(Unit)
                        throw AssertionError("Unexpected recognizer provider call")
                    }
            return object : InkTextRecognizer {
                override suspend fun recognize(request: RecognitionRequest): List<RecognitionCandidate> =
                    withContext(NonCancellable) {
                        requests += request
                        invocation.started.complete(request)
                        invocation.result.await()
                    }

                override fun close() {
                    invocation.closed.complete(Unit)
                }
            }
        }

        suspend fun assertNoUnexpectedCall() {
            assertNull(withTimeoutOrNull(250) { unexpectedCall.await() })
        }
    }

    private class Invocation {
        val started = CompletableDeferred<RecognitionRequest>()
        val closed = CompletableDeferred<Unit>()
        val result = CompletableDeferred<List<RecognitionCandidate>>()

        fun complete(value: List<RecognitionCandidate>) {
            check(result.complete(value))
        }

        suspend fun awaitStarted(): RecognitionRequest = withTimeout(30_000) { started.await() }

        suspend fun awaitClosed() {
            withTimeout(30_000) { closed.await() }
        }
    }

    private class ManualDebounce {
        private val gates = Channel<DebounceGate>(Channel.UNLIMITED)

        suspend fun await() {
            val gate = DebounceGate()
            gates.send(gate)
            gate.release.await()
        }

        suspend fun next(): DebounceGate = withTimeout(30_000) { gates.receive() }
    }

    private class DebounceGate {
        val release = CompletableDeferred<Unit>()

        fun release() {
            check(release.complete(Unit))
        }
    }

    private suspend fun drainMutationGate() {
        withContext(Dispatchers.Default) {
            LibraryMutationGate.withLock { Unit }
        }
        onMain { Unit }
    }

    private suspend fun holdMutationGate(block: suspend () -> Unit) = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder =
            launch(Dispatchers.Default) {
                LibraryMutationGate.withLock {
                    entered.complete(Unit)
                    release.await()
                }
            }
        entered.await()
        try {
            block()
        } finally {
            release.complete(Unit)
            holder.join()
        }
    }
}
