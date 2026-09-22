package com.majkeylab.seliadocs.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.settings.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemePalettePickerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingPaletteUpdatesRadioStateAndCallsOnce() {
        var calls = 0
        rule.setContent {
            SeliaDocsTheme(false) {
                var selected by remember { mutableStateOf(ThemePalette.CLASSIC) }
                ThemePalettePicker(selected, {
                    calls++
                    selected = it
                })
            }
        }

        rule.onNodeWithContentDescription("Color palette: Classic").assertIsSelected()
        rule.onNodeWithContentDescription("Color palette: Ocean")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
            .assertIsSelected()
        rule.onNodeWithContentDescription("Color palette: Classic").assertIsNotSelected()
        rule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun keyboardCanChoosePaletteInDarkAppearance() {
        lateinit var inputModeManager: InputModeManager
        var selected by mutableStateOf(ThemePalette.CLASSIC)
        var calls = 0
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            SeliaDocsTheme(true) {
                ThemePalettePicker(selected, {
                    calls++
                    selected = it
                })
            }
        }

        rule.runOnIdle {
            assertTrue("Keyboard input mode request failed", inputModeManager.requestInputMode(InputMode.Keyboard))
        }
        rule.onNodeWithContentDescription("Color palette: Forest")
            .assertIsNotSelected()
            .performSemanticsAction(SemanticsActions.RequestFocus) {
                assertTrue("Forest palette focus request failed", it())
            }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
            .assertIsSelected()
        rule.onNodeWithContentDescription("Color palette: Classic").assertIsNotSelected()
        rule.runOnIdle {
            assertEquals(ThemePalette.FOREST, selected)
            assertEquals(1, calls)
        }
    }

    @Test
    fun previewsWrapWithinNarrowLayoutAtLargeFontSize() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                SeliaDocsTheme(false) {
                    Column(Modifier.width(200.dp).verticalScroll(rememberScrollState()).testTag("palette-viewport")) {
                        ThemePalettePicker(ThemePalette.CLASSIC, {})
                    }
                }
            }
        }

        val viewport = rule.onNodeWithTag("palette-viewport").fetchSemanticsNode().boundsInRoot
        listOf("Classic", "Ocean", "Forest", "Rose", "Amber", "Graphite").forEach { name ->
            val node = rule.onNodeWithContentDescription("Color palette: $name")
                .performScrollTo()
                .assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$name exceeds viewport: $bounds vs $viewport", bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
    }
}
