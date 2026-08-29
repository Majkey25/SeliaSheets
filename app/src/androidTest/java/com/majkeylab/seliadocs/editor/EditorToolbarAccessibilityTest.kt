package com.majkeylab.seliadocs.editor

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditorToolbarAccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun expandedToolbarExposesRadioSelectionAndSingleCallbacks() {
        var toolCalls = 0
        var modeCalls = 0
        rule.setContent {
            SeliaDocsTheme {
                var state by remember {
                    mutableStateOf(EditorUiState(tool = EditorTool.ERASER, eraserMode = EraserMode.SEGMENT))
                }
                EditorToolBar(
                    state = state,
                    onSelectTool = {
                        toolCalls++
                        state = state.copy(tool = it)
                    },
                    onEraserMode = {
                        modeCalls++
                        state = state.copy(eraserMode = it)
                    },
                    onUndo = {},
                    onRedo = {},
                    onSearch = {},
                    onAddText = {},
                    onAddImage = {},
                    onImportPdf = {},
                    onCleanShape = {},
                    settings = AppSettings(),
                    onUpdateSettings = {},
                )
            }
        }

        rule.onNodeWithTag("toolbar-tool-eraser").assertIsSelected()
        rule.onNodeWithTag("toolbar-eraser-segment").assertIsSelected()
        rule.onNodeWithTag("toolbar-eraser-stroke").assertIsNotSelected().performSemanticClick()
        rule.waitForIdle()
        rule.onNodeWithTag("toolbar-eraser-stroke").assertIsSelected()
        rule.onNodeWithTag("toolbar-tool-lasso").assertIsNotSelected().performSemanticClick().assertIsSelected()
        rule.runOnIdle {
            assertEquals(1, modeCalls)
            assertEquals(1, toolCalls)
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performSemanticClick() =
        performSemanticsAction(SemanticsActions.OnClick) { it() }
}
