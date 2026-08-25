package com.majkeylab.seliadocs.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        repeat(499) { offset ->
            val index = offset + 1
            val pageId = "page-$index"
            notebooks.insertPage(PageEntity(pageId, notebookId, index, "GRID", 595, 842))
            pageIds += pageId
        }
        val content = database.pageDao()
        content.insertStrokes(pageIds.mapIndexed { index, pageId -> stroke("stroke-$index", pageId) })
        content.insertElements(pageIds.mapIndexed { index, pageId -> element("element-$index", pageId) })

        val strokes = repository.observeStrokes("page-250").first()
        val elements = repository.observeElements("page-250").first()

        assertEquals(500, content.getStrokes(pageIds).size)
        assertEquals(500, content.getElements(pageIds).size)
        assertEquals(listOf("stroke-250"), strokes.map(StrokeEntity::id))
        assertEquals(listOf("element-250"), elements.map(ElementEntity::id))
        assertEquals(setOf("page-250"), (strokes.map { it.pageId } + elements.map { it.pageId }).toSet())
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
}
