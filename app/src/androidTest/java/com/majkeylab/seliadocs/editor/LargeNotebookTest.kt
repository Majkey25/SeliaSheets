package com.majkeylab.seliadocs.editor

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokeEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeNotebookTest {
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, SeliaDocsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = SeliaDocsRepository(database, clock = { 1L }, idFactory = { "notebook" })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun selectedPageObserversNeverReturnSiblingContent() = runTest {
        createNotebookWithContent(500)

        val strokes = repository.observeStrokes("page-250").first()
        val elements = repository.observeElements("page-250").first()

        val content = database.pageDao()
        assertEquals(500, content.getStrokesForNotebook("notebook").size)
        assertEquals(500, content.getElementsForNotebook("notebook").size)
        assertEquals(listOf("stroke-250"), strokes.map(StrokeEntity::id))
        assertEquals(listOf("element-250"), elements.map(ElementEntity::id))
        assertEquals(setOf("page-250"), (strokes.map { it.pageId } + elements.map { it.pageId }).toSet())
    }

    @Test
    fun largeNotebookLoadsEveryPageContent() = runTest {
        val pageIds = createNotebookWithContent(1_200)

        val content = repository.loadNotebook("notebook")

        assertEquals(1_200, content.pages.size)
        assertEquals(1_200, content.strokes.size)
        assertEquals(1_200, content.elements.size)
        assertEquals(pageIds.toSet(), content.strokes.map(StrokeEntity::pageId).toSet())
        assertEquals(pageIds.toSet(), content.elements.map(ElementEntity::pageId).toSet())
    }

    @Test
    fun concurrentInsertCannotSplitNotebookSnapshot() = runTest {
        database.close()
        val childQueryStarted = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)
        val firstChildQuery = AtomicBoolean(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.databaseBuilder(
                    context,
                    SeliaDocsDatabase::class.java,
                    "load-notebook-snapshot-${System.nanoTime()}.db",
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCallback(
                    { sql, _ ->
                        if (
                            sql.startsWith("SELECT * FROM strokes WHERE pageId IN") &&
                                firstChildQuery.compareAndSet(true, false)
                        ) {
                            val loadInTransaction = database.inTransaction()
                            childQueryStarted.countDown()
                            if (!loadInTransaction) {
                                check(writerCommitted.await(5, TimeUnit.SECONDS)) { "Writer did not commit" }
                            }
                        }
                    },
                    Executor { command -> command.run() },
                )
                .build()
        repository = SeliaDocsRepository(database, clock = { 1L }, idFactory = { "notebook" })
        repository.createNotebook(
            CreateNotebookRequest(
                "Concurrent",
                CoverColor.PERIWINKLE,
                CoverPattern.SOLID,
                PaperTemplate.GRID,
                PageOrientation.PORTRAIT,
                false,
            ),
        )
        val writer =
            async(Dispatchers.IO) {
                check(childQueryStarted.await(5, TimeUnit.SECONDS)) { "Child query did not start" }
                val page = PageEntity("concurrent-page", "notebook", 1, "GRID", 595, 842)
                database.withTransaction {
                    database.notebookDao().insertPage(page)
                    database.pageDao().insertStroke(stroke("concurrent-stroke", page.id))
                    database.pageDao().insertElement(element("concurrent-element", page.id))
                    database.pageDao().insertBlock(block("concurrent-block", page.id))
                }
                writerCommitted.countDown()
            }

        val content = repository.loadNotebook("notebook")
        writer.await()

        val pageIds = content.pages.map(PageEntity::id).toSet()
        val childPageIds =
            (content.strokes.map(StrokeEntity::pageId) +
                    content.elements.map(ElementEntity::pageId) +
                    content.blocks.map(BlockEntity::pageId))
                .toSet()
        assertTrue(pageIds.containsAll(childPageIds))
    }

    private suspend fun createNotebookWithContent(pageCount: Int): List<String> {
        val notebookId =
            repository.createNotebook(
                CreateNotebookRequest(
                    "Large",
                    CoverColor.PERIWINKLE,
                    CoverPattern.SOLID,
                    PaperTemplate.GRID,
                    PageOrientation.PORTRAIT,
                    false,
                ),
            )
        val notebooks = database.notebookDao()
        val pageIds = mutableListOf("notebook")
        repeat(pageCount - 1) { offset ->
            val index = offset + 1
            val pageId = "page-$index"
            notebooks.insertPage(PageEntity(pageId, notebookId, index, "GRID", 595, 842))
            pageIds += pageId
        }
        val content = database.pageDao()
        content.insertStrokes(pageIds.mapIndexed { index, pageId -> stroke("stroke-$index", pageId) })
        content.insertElements(pageIds.mapIndexed { index, pageId -> element("element-$index", pageId) })
        return pageIds
    }

    private fun stroke(id: String, pageId: String) =
        StrokeEntity(id, pageId, 0, "PEN", 0xff000000.toInt(), 3f, 0.1f, byteArrayOf(1))

    private fun element(id: String, pageId: String) =
        ElementEntity(
            id,
            pageId,
            0,
            "TEXT",
            0f,
            0f,
            100f,
            40f,
            0f,
            "Page",
            null,
            null,
            null,
            null,
        )

    private fun block(id: String, pageId: String) =
        BlockEntity(id, pageId, 0, "PARAGRAPH", "Page", false, 0, "START", null)
}
