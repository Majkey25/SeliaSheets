package com.majkeylab.seliadocs.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ImageImportCancellationTest {
    @Test
    fun cancellationAfterImageCommitKeepsTheReferencedAsset() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(app))
        val book = repository.createNotebook(CreateNotebookRequest(
            "Image cancellation ${System.nanoTime()}", CoverColor.PERIWINKLE, CoverPattern.SOLID,
            PaperTemplate.BLANK, PageOrientation.PORTRAIT, false,
        ))
        val pageId = repository.getPages(book).single().id
        val source = File.createTempFile("image-cancel-", ".png", app.cacheDir)
        val assets = AssetStore(File(app.filesDir, "assets"))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val queue = LinkedBlockingQueue<Runnable>()
        val queuedMain = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        var subscription: Job? = null
        var scopeJob: Job? = null
        var assetId: String? = null
        Dispatchers.setMain(queuedMain)
        try {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            lateinit var editor: EditorViewModel
            onMain {
                editor = ViewModelProvider(owner, viewModelFactory {
                    initializer { EditorViewModel(app, book) }
                })[EditorViewModel::class.java]
            }
            scopeJob = requireNotNull(editor.viewModelScope.coroutineContext[Job])
            subscription = launch(start = CoroutineStart.UNDISPATCHED) { editor.state.collect() }
            withTimeout(30_000) {
                while (editor.state.value.selectedPage?.id != pageId) {
                    queue.poll(50, TimeUnit.MILLISECONDS)?.let { onMain(it::run) }
                    yield()
                }
            }
            val completed = CompletableDeferred<Boolean>()
            onMain { editor.importImage(pageId, Uri.fromFile(source), ocrEnabled = false, onComplete = completed::complete) }
            var cancelledAfterCommit = false
            withTimeout(30_000) {
                while (!completed.isCompleted) {
                    val next = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (next != null) {
                        // Inspect the committed DB before allowing any queued Main continuation to resume.
                        val element = repository.getElements(pageId).singleOrNull()
                        if (!cancelledAfterCommit && element != null) {
                            assertFalse("Import completed before the cancellation boundary", completed.isCompleted)
                            assertEquals(ElementKind.IMAGE.name, element.kind)
                            assetId = requireNotNull(element.assetId)
                            assertTrue("Asset must exist before cancellation", assets.file(requireNotNull(assetId)).isFile)
                            onMain { editor.viewModelScope.cancel() }
                            cancelledAfterCommit = true
                        }
                        onMain(next::run)
                    }
                    yield()
                }
            }
            assertTrue("Must cancel after the actual image-row commit", cancelledAfterCommit)
            assertFalse(completed.await())
            assertTrue(requireNotNull(scopeJob).isCancelled)
            val saved = repository.getElements(pageId).single()
            assertEquals(assetId, saved.assetId)
            val installed = assets.requireFile(requireNotNull(saved.assetId))
            val decoded = requireNotNull(BitmapFactory.decodeFile(installed.path))
            try {
                assertEquals(32, decoded.width)
                assertEquals(24, decoded.height)
            } finally { decoded.recycle() }
        } finally {
            subscription?.cancel()
            try { onMain(owner.viewModelStore::clear) } finally { Dispatchers.resetMain() }
            withTimeout(10_000) {
                while (true) {
                    val remaining = queue.poll() ?: break
                    onMain(remaining::run)
                    yield()
                }
                scopeJob?.join()
            }
            LibraryMutationGate.withLock {
                val ownedAssets = repository.getElements(pageId).mapNotNull { it.assetId }.toSet() + listOfNotNull(assetId)
                repository.deleteNotebook(book)
                ownedAssets.forEach { assets.file(it).delete() }
            }
            source.delete()
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
