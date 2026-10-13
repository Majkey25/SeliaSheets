package com.majkeylab.seliadocs.library

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryQueryTest {
    @Test
    fun failedCreationPreservesQueryAndExistingNotebook() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java).build()
        val repository = SeliaDocsRepository(database)
        val request = CreateNotebookRequest(
            "Physics", CoverColor.PERIWINKLE, CoverPattern.SOLID, PaperTemplate.BLANK, PageOrientation.PORTRAIT, false,
        )
        val viewModel = LibraryViewModel(
            application,
            repository,
            AssetStore(File(application.cacheDir, "library-query-${System.nanoTime()}")),
        )
        try {
            val notebookId = repository.createNotebook(request)
            viewModel.setQuery("Physics")
            withTimeout(5_000) { viewModel.state.first { it.query == "Physics" && it.notebooks.isNotEmpty() } }
            viewModel.createNotebook(request.copy(title = ""))
            val failed = withTimeout(5_000) { viewModel.state.first { it.failed } }
            assertEquals("Physics", failed.query)
            assertEquals(listOf(notebookId), failed.notebooks.map { it.id })
            assertEquals(listOf(notebookId), repository.getAllNotebooks().map { it.id })
        } finally {
            viewModel.viewModelScope.cancel()
            database.close()
        }
    }
}
