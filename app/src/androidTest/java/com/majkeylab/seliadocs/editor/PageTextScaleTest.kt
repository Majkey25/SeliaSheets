package com.majkeylab.seliadocs.editor

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.TEXT_ELEMENT_MAX_LENGTH
import com.majkeylab.seliadocs.settings.InterfaceFont
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PageTextScaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun committedTextRefreshCannotReplayStaleTextWhileInputIsBlocked() {
        val block = mutableStateOf(BlockEntity("block", "page", 0, "PARAGRAPH", "Old text", false, 0, "START", null))
        val enabled = mutableStateOf(false)
        val shown = mutableStateOf(true)
        val saves = mutableListOf<String>()
        compose.setContent {
            if (shown.value) PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = listOf(block.value),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.PEN,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, text -> saves += text },
                onPageTextDraftChanged = { _, _ -> false },
                pageTextInputEnabled = enabled.value,
                onCommitElementTransform = {},
                assetFile = { File(it) },
                modifier = Modifier.size(360.dp, 640.dp),
            )
        }
        compose.onAllNodesWithText("Old text")[0].assertIsDisplayed()
        compose.runOnIdle { block.value = block.value.copy(text = "Committed text") }
        compose.onAllNodesWithText("Committed text")[0].assertIsDisplayed()
        compose.runOnIdle { enabled.value = true }
        compose.onAllNodesWithText("Committed text")[0].assertIsDisplayed()
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { assertTrue("Clean committed text must not be saved again: $saves", saves.isEmpty()) }
    }

    @Test
    fun pageTextGeometryIgnoresSystemFontScale() {
        val fontScale = mutableFloatStateOf(1f)
        val interfaceFont = mutableStateOf(InterfaceFont.SYSTEM)
        val page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842)
        val labels = listOf("Full page text", "Text element", "Math result", "Inline draft")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale.floatValue)) {
                SeliaDocsTheme(font = interfaceFont.value) {
                    PageCanvas(
                        page = page,
                        pageNumber = 1,
                        pageCount = 1,
                        strokes = emptyList(),
                        elements =
                            listOf(
                                element("text", "TEXT", 120f, "Text element"),
                                element("math", "MATH", 360f, "Math result"),
                            ),
                        blocks = listOf(BlockEntity("block", "page", 0, "PARAGRAPH", labels.first(), false, 0, "START", null)),
                        selectedStrokeIds = emptySet(),
                        selectedElementId = null,
                        fingerDrawing = false,
                        tool = EditorTool.PEN,
                        penWidth = 4f,
                        highlighterWidth = 16f,
                        pageTransitionEnabled = false,
                        onPreviousPage = {},
                        onNextPage = {},
                        onStrokeFinished = { _, _ -> },
                        onEraseFinished = { _, _ -> },
                        onSelectContent = { _, _ -> },
                        onMoveSelection = { _, _ -> },
                        onPageTextChanged = { _, _ -> },
                        onCommitElementTransform = {},
                        assetFile = { File(it) },
                        textPlacementEnabled = true,
                        textPlacementInputEnabled = false,
                        initialInlineTextDraft = InlineTextDraft("page", null, CanvasPoint(120f, 600f), labels.last()),
                        modifier = Modifier.size(360.dp, 640.dp),
                    )
                }
            }
        }

        val normal = labels.associateWith(::textLayout)
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale.floatValue = scale }
            for (font in listOf(InterfaceFont.SYSTEM, InterfaceFont.SERIF, InterfaceFont.MONOSPACE, InterfaceFont.SYSTEM)) {
                compose.runOnIdle { interfaceFont.value = font }
                compose.waitForIdle()
                labels.forEach { label ->
                    val expected = normal.getValue(label)
                    val actual = textLayout(label)
                    val message = "$label with $font at $scale font scale"
                    assertEquals(message, expected.lineCount, actual.lineCount)
                    repeat(expected.lineCount) { line ->
                        assertEquals(message, expected.getLineEnd(line), actual.getLineEnd(line))
                        assertEquals(message, expected.getLineRight(line), actual.getLineRight(line), 0.5f)
                        assertEquals(message, expected.getLineBottom(line), actual.getLineBottom(line), 0.5f)
                    }
                }
            }
        }

        labels.forEach { label ->
            assertEquals("$label moved at 200% font scale", normal.getValue(label).getLineBottom(0), textLayout(label).getLineBottom(0), 0.5f)
        }
    }

    @Test
    fun inlinePlacementUsesCurrentPermissionAfterPaneFocusChanges() {
        val enabled = mutableStateOf(false)
        val accepted = mutableListOf<InlineTextDraft>()
        compose.setContent {
            val canWrite = enabled.value
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.LASSO,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = {},
                assetFile = { File(it) },
                textPlacementEnabled = true,
                textPlacementInputEnabled = canWrite,
                initialInlineTextDraft = InlineTextDraft("page", null, null, ""),
                onTextPlacementDraftChanged = { pageId, elementId, point, text ->
                    if (canWrite) accepted += InlineTextDraft(pageId, elementId, point, text)
                    canWrite
                },
                modifier = Modifier.size(360.dp, 640.dp),
            )
        }
        compose.onNodeWithTag("inline-text-placement").performTouchInput { click(center) }
        compose.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        compose.runOnIdle { assertTrue(accepted.isEmpty()); enabled.value = true }
        compose.onNodeWithTag("inline-text-placement").performTouchInput { click(center) }
        compose.onNodeWithTag("inline-text-editor").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, accepted.size)
            assertEquals("page", accepted.single().pageId)
            assertTrue(requireNotNull(accepted.single().origin).x.isFinite())
        }
    }

    @Test
    fun oversizedInlinePasteKeepsThePreviousDraftAndShowsAnError() {
        var changed = false
        renderInlineDraft("Saved draft") { _, _, _, _ ->
            changed = true
            true
        }

        compose.onNodeWithTag("inline-text-editor")
            .performTextReplacement("A".repeat(TEXT_ELEMENT_MAX_LENGTH + 1))

        compose.onNodeWithTag("inline-text-editor").assertTextContains("Saved draft")
        compose.onNodeWithTag("inline-text-error").assertIsDisplayed().assertTextEquals("Text is too long.")
        assertFalse(changed)
    }

    @Test
    fun nonFittingInlinePasteKeepsThePreviousDraftAndShowsAnError() {
        var changed = false
        renderInlineDraft("Saved draft", CanvasPoint(120f, 790f)) { _, _, _, _ ->
            changed = true
            true
        }

        compose.onNodeWithTag("inline-text-editor").performTextReplacement("A".repeat(200))

        compose.onNodeWithTag("inline-text-editor").assertTextContains("Saved draft")
        compose.onNodeWithTag("inline-text-error")
            .assertIsDisplayed()
            .assertTextEquals("Text does not fit on this page.")
        assertFalse(changed)
    }

    private fun renderInlineDraft(
        text: String,
        point: CanvasPoint = CanvasPoint(120f, 300f),
        onDraftChanged: (String, String?, CanvasPoint, String) -> Boolean,
    ) {
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.LASSO,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = {},
                assetFile = { File(it) },
                textPlacementEnabled = true,
                initialInlineTextDraft = InlineTextDraft("page", null, point, text),
                onTextPlacementDraftChanged = onDraftChanged,
                modifier = Modifier.size(360.dp, 640.dp),
            )
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(text)[0]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun element(id: String, kind: String, y: Float, content: String) =
        ElementEntity(
            id = id,
            pageId = "page",
            zIndex = 0,
            kind = kind,
            x = 120f,
            y = y,
            width = 300f,
            height = 100f,
            rotation = 0f,
            text = content,
            assetId = null,
            shapeKind = null,
            expression = null,
            resultText = content.takeIf { kind == "MATH" },
        )
}
