package cz.majkey.perko.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.After
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
        val ids = ArrayDeque(listOf("notebook", "page-0", "page-1", "page-2"))
        database =
            Room.inMemoryDatabaseBuilder(context, PerkoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            PerkoRepository(
                database = database,
                clock = { 1_000L },
                idFactory = ids::removeFirst,
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
        assertEquals(listOf("page-2", "page-0", "page-1"), pages.map(PageEntity::id))
    }

    @Test
    fun deletingOnlyPageIsRejected() = runTest {
        val notebookId = repository.createNotebook(request())
        val onlyPage = repository.getPages(notebookId).single()

        val error = runCatching { repository.deletePage(onlyPage.id) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
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
