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
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class HighlighterOpacityUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var settings by mutableStateOf(AppSettings())

    @Test
    fun penOpacityAndPresetColorsRetainEachOther() {
        showOptions(EditorTool.PEN)
        paletteControl("pen-opacity-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        rule.runOnIdle { assertEquals(128, settings.penColorArgb ushr 24) }
        paletteControl("brush-color-blue").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(0x803156D9.toInt(), settings.penColorArgb) }
        paletteControl("pen-opacity-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.runOnIdle { assertEquals(0x033156D9, settings.penColorArgb) }
    }

    @Test
    fun customPencilColorRequiresSixHexDigitsAndPreservesOpacity() {
        settings = AppSettings(penColorArgb = 0x80123456.toInt())
        showOptions(EditorTool.PENCIL)
        paletteControl("brush-custom-color").performClick()
        rule.onNodeWithTag("brush-color-hex").performTextReplacement("#nothex")
        rule.onNodeWithTag("brush-color-apply").assertIsNotEnabled()
        rule.onNodeWithTag("brush-color-hex").performTextReplacement("#abcdef")
        rule.onNodeWithTag("brush-color-apply").performClick()
        rule.runOnIdle { assertEquals(0x80ABCDEF.toInt(), settings.penColorArgb) }
        paletteControl("brush-custom-color").performClick()
        rule.onNodeWithTag("brush-color-red-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        rule.onNodeWithTag("brush-color-apply").performClick()
        rule.runOnIdle { assertEquals(0x8020CDEF.toInt(), settings.penColorArgb) }
    }

    @Test
    fun cancelingCustomHighlighterColorDoesNotChangeStoredColor() {
        showOptions(EditorTool.HIGHLIGHTER)
        paletteControl("brush-custom-color").performClick()
        rule.onNodeWithTag("brush-color-hex").performTextReplacement("FFFFFF")
        rule.onNodeWithTag("brush-color-cancel").performClick()
        rule.runOnIdle { assertEquals(AppSettings().highlighterColorArgb, settings.highlighterColorArgb) }
    }

    @Test
    fun opacityChangesPreserveRgbAndColorChangesPreserveOpacity() {
        showOptions(EditorTool.HIGHLIGHTER)
        val slider = paletteControl("highlighter-opacity-slider").assertIsDisplayed()
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(40f, range.current, 0.01f)
        assertEquals(10f..80f, range.range)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        rule.runOnIdle { assertEquals(0x99FFD54F.toInt(), settings.highlighterColorArgb) }
        paletteControl("brush-color-yellow").assertIsSelected()
        paletteControl("brush-color-pink").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(0x99F48FB1.toInt(), settings.highlighterColorArgb) }
        rule.onNodeWithTag("brush-shape-assist").assertDoesNotExist()
    }

    @Test
    fun opacityLimitsDoNotChangePenOrWidthSettings() {
        showOptions(EditorTool.HIGHLIGHTER)
        val slider = paletteControl("highlighter-opacity-slider")
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
        paletteControl("brush-shape-assist").assertIsDisplayed().performClick()
        rule.runOnIdle { assertFalse(settings.shapeAssist) }
    }

    private fun paletteControl(tag: String): SemanticsNodeInteraction {
        val interaction = rule.onNodeWithTag(tag)
        var diagnostic = ""
        // Bound retries and report geometry instead of hanging when a control cannot be revealed.
        repeat(4) { attempt ->
            val node = interaction.fetchSemanticsNode()
            val scroll = generateSequence(node.parent) { it.parent }.first {
                it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }
            val viewport = scroll.boundsInRoot
            val left = node.positionInRoot.x
            val right = left + node.size.width
            val range = scroll.config[SemanticsProperties.HorizontalScrollAxisRange]
            diagnostic = "$tag bounds=[$left,$right], viewport=$viewport, scroll=${range.value()}/${range.maxValue()}"
            val delta = when {
                left < viewport.left - 1f -> -ceil(viewport.left - left)
                right > viewport.right + 1f -> ceil(right - viewport.right)
                else -> return interaction.assertIsDisplayed()
            }
            if (attempt < 3) {
                android.util.Log.i("PaletteScroll", "$diagnostic delta=$delta")
                rule.runOnIdle { scroll.config[SemanticsActions.ScrollBy].action!!.invoke(delta, 0f) }
            }
        }
        throw AssertionError("Palette control is not fully visible after three scrolls: $diagnostic")
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
