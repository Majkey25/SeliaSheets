package cz.majkey.perko.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerkoRepositoryTest {
    private lateinit var database: PerkoDatabase
    private lateinit var repository: PerkoRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var nextId = 0
        database =
            Room.inMemoryDatabaseBuilder(context, PerkoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            PerkoRepository(
                database = database,
                clock = { 1_000L },
                idFactory = { "id-${nextId++}" },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createNotebookAlsoCreatesFirstPage() = runTest {
        val id = repository.createNotebook(request())

        assertEquals("Physics", repository.getNotebook(id).title)
        assertEquals(listOf(0), repository.getPages(id).map(PageEntity::pageIndex))
    }

    @Test
    fun pageMovesRemainContiguousAndOrdered() = runTest {
        val notebookId = repository.createNotebook(request())
        repository.addPage(notebookId)
        repository.addPage(notebookId)

        repository.movePage(notebookId, fromIndex = 2, toIndex = 0)

        val pages = repository.getPages(notebookId)
        assertEquals(listOf(0, 1, 2), pages.map(PageEntity::pageIndex))
        assertEquals(listOf("id-3", "id-1", "id-2"), pages.map(PageEntity::id))
    }

    @Test
    fun deletingOnlyPageIsRejected() = runTest {
        val notebookId = repository.createNotebook(request())
        val onlyPage = repository.getPages(notebookId).single()

        val error = runCatching { repository.deletePage(onlyPage.id) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun strokePayloadPersistsWithoutMetadataLoss() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val payload =
            StrokePayload(
                brushKind = "PRESSURE_PEN",
                colorArgb = 0xFF202124.toInt(),
                size = 4f,
                epsilon = 0.1f,
                inputs = byteArrayOf(1, 3, 5, 7),
            )

        repository.addStroke(page.id, payload)

        val saved = repository.getStrokes(page.id).single()
        assertEquals(payload.brushKind, saved.brushKind)
        assertEquals(payload.colorArgb, saved.colorArgb)
        assertEquals(payload.size, saved.size)
        assertArrayEquals(payload.inputs, saved.inputs)
    }

    private fun request() =
        CreateNotebookRequest(
            title = "Physics",
            coverColor = CoverColor.PERIWINKLE,
            coverPattern = CoverPattern.SOLID,
            paper = PaperTemplate.GRID,
            orientation = PageOrientation.PORTRAIT,
            fingerDrawing = false,
        )
}
