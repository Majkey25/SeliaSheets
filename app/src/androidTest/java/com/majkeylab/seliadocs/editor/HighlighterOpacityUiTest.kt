package com.majkeylab.seliadocs.editor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlighterOpacityUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var settings by mutableStateOf(AppSettings())

    @Test
    fun penOpacityAndPresetColorsRetainEachOther() {
        showOptions(EditorTool.PEN)
        rule.onNodeWithTag("pen-opacity-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        rule.runOnIdle { assertEquals(128, settings.penColorArgb ushr 24) }
        rule.onNodeWithTag("brush-color-blue").performScrollTo().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(0x803156D9.toInt(), settings.penColorArgb) }
        rule.onNodeWithTag("pen-opacity-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.runOnIdle { assertEquals(0x033156D9, settings.penColorArgb) }
    }

    @Test
    fun customPencilColorRequiresSixHexDigitsAndPreservesOpacity() {
        settings = AppSettings(penColorArgb = 0x80123456.toInt())
        showOptions(EditorTool.PENCIL)
        rule.onNodeWithTag("brush-custom-color").performScrollTo().performClick()
        rule.onNodeWithTag("brush-color-hex").performTextReplacement("#nothex")
        rule.onNodeWithTag("brush-color-apply").assertIsNotEnabled()
        rule.onNodeWithTag("brush-color-hex").performTextReplacement("#abcdef")
        rule.onNodeWithTag("brush-color-apply").performClick()
        rule.runOnIdle { assertEquals(0x80ABCDEF.toInt(), settings.penColorArgb) }
        rule.onNodeWithTag("brush-custom-color").performScrollTo().performClick()
        rule.onNodeWithTag("brush-color-red-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        rule.onNodeWithTag("brush-color-apply").performClick()
        rule.runOnIdle { assertEquals(0x8020CDEF.toInt(), settings.penColorArgb) }
    }

    @Test
    fun cancelingCustomHighlighterColorDoesNotChangeStoredColor() {
        showOptions(EditorTool.HIGHLIGHTER)
        rule.onNodeWithTag("brush-custom-color").performScrollTo().performClick()
        rule.onNodeWithTag("brush-color-hex").performTextReplacement("FFFFFF")
        rule.onNodeWithTag("brush-color-cancel").performClick()
        rule.runOnIdle { assertEquals(AppSettings().highlighterColorArgb, settings.highlighterColorArgb) }
    }

    @Test
    fun opacityChangesPreserveRgbAndColorChangesPreserveOpacity() {
        showOptions(EditorTool.HIGHLIGHTER)
        val slider = rule.onNodeWithTag("highlighter-opacity-slider").performScrollTo().assertIsDisplayed()
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(40f, range.current, 0.01f)
        assertEquals(10f..80f, range.range)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        rule.runOnIdle { assertEquals(0x99FFD54F.toInt(), settings.highlighterColorArgb) }
        rule.onNodeWithTag("brush-color-yellow").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("brush-color-pink").performScrollTo().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(0x99F48FB1.toInt(), settings.highlighterColorArgb) }
        rule.onNodeWithTag("brush-shape-assist").assertDoesNotExist()
    }

    @Test
    fun opacityLimitsDoNotChangePenOrWidthSettings() {
        showOptions(EditorTool.HIGHLIGHTER)
        val slider = rule.onNodeWithTag("highlighter-opacity-slider").performScrollTo()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        rule.runOnIdle { assertEquals(26, settings.highlighterColorArgb ushr 24) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        rule.runOnIdle {
            assertEquals(204, settings.highlighterColorArgb ushr 24)
            assertEquals(AppSettings().penColorArgb, settings.penColorArgb)
            assertEquals(AppSettings().penWidth, settings.penWidth, 0f)
            assertEquals(AppSettings().highlighterWidth, settings.highlighterWidth, 0f)
        }
    }

    @Test
    fun penRetainsSmartShapesWithoutHighlighterOpacity() {
        showOptions(EditorTool.PEN)
        rule.onNodeWithTag("highlighter-opacity-slider").assertDoesNotExist()
        rule.onNodeWithTag("brush-shape-assist").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertFalse(settings.shapeAssist) }
    }

    private fun showOptions(tool: EditorTool) {
        rule.setContent {
            SeliaDocsTheme {
                Box(Modifier.width(360.dp)) {
                    CompactEditorPalette(
                        state = EditorUiState(tool = tool),
                        settings = settings,
                        onUpdateSettings = { update -> settings = update(settings) },
                        onSelectTool = {},
                        onEraserMode = {},
                        onAddText = {},
                        onAddImage = {},
                        onImportPdf = {},
                        onCleanShape = {},
                    )
                }
            }
        }
        rule.onNodeWithTag("compact-tool-${tool.name.lowercase()}").performClick()
    }
}
