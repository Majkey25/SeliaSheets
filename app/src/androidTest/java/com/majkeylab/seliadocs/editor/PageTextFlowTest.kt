package com.majkeylab.seliadocs.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PageTextFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typeToolWritesDirectlyToPage() {
        val saved = AtomicReference<Pair<String, String>>()
        var blocks by mutableStateOf(emptyList<BlockEntity>())
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = blocks,
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text ->
                    saved.set(pageId to text)
                    blocks =
                        listOf(
                            BlockEntity("block", pageId, 0, "PARAGRAPH", text, false, 0, "START", null),
                        )
                },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput("Lecture notes")
        compose.waitUntil(3_000) { saved.get()?.second == "Lecture notes" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals("page" to "Lecture notes", saved.get())

        compose.runOnUiThread { blocks = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(3_000) {
            val node = compose.onNodeWithTag("page-text").fetchSemanticsNode()
            runCatching { node.config[SemanticsProperties.EditableText].text }
                .getOrNull()
                .isNullOrEmpty()
        }
    }

    @Test
    fun typeToolKeepsOverflowOffThePage() {
        val saved = AtomicReference<Pair<String, String>>()
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text -> saved.set(pageId to text) },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput(List(80) { "A full line" }.joinToString("\n"))

        compose.onNodeWithText("Page full · Add a page to continue").assertIsDisplayed()
        assertEquals(null, saved.get())
    }
}
