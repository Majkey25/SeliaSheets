package com.majkeylab.seliadocs.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.BuildConfig
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appDetailsShowsVersionAndSupport() {
        val version = "Version ${BuildConfig.VERSION_NAME}"
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App & privacy"))
        rule.onNodeWithText("App & privacy").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App details"))
        rule.onNodeWithText("App details").assertIsDisplayed()

        rule.onNodeWithTag("settings-list").performScrollToNode(hasText(version))
        rule.onNodeWithText(version).assertIsDisplayed()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Support this app → Buy Me a Coffee"))
        rule.onNodeWithText("Support this app → Buy Me a Coffee").assertIsDisplayed()
    }

    @Test
    fun settingsShowsVisualNotebookDefaultsAndDrawingSamples() {
        rule.onNodeWithText("Settings").performClick()

        rule.onNodeWithText("Notebook defaults").assertIsDisplayed()
        rule.onNodeWithText("Notebook defaults").performClick()
        rule.onNodeWithText("Ruled notes").assertIsDisplayed()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Drawing"))
        rule.onNodeWithText("Drawing").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Pen sample"))
        rule.onNodeWithText("Pen sample").assertIsDisplayed()
    }

    @Test
    fun drawingWidthSlidersExposeLabelsAndValues() {
        rule.activity.setContent {
            SeliaDocsTheme(darkTheme = false) {
                SettingsScreen(
                    settings = AppSettings(penWidth = 7f, highlighterWidth = 31f),
                    onUpdate = {},
                    onBackup = {},
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("Drawing").performClick()

        listOf("Pen width" to "7 pt", "Highlighter width" to "31 pt").forEach { (label, value) ->
            val slider =
                hasContentDescription(label) and
                    hasStateDescription(value) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
            rule.onNodeWithTag("settings-list").performScrollToNode(slider)
            rule.onNode(slider).assertIsDisplayed()
        }
    }

    @Test
    fun notebookDefaultsStartsCollapsed() {
        rule.onNodeWithText("Settings").performClick()

        rule.onNodeWithText("Ruled notes").assertDoesNotExist()
    }

    @Test
    fun everySettingsGroupReportsCollapsedAndExpandedState() {
        rule.onNodeWithText("Settings").performClick()

        listOf("Notebook defaults", "Drawing", "Interface & export", "App & privacy").forEach { title ->
            val group =
                hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            rule.onNodeWithTag("settings-list").performScrollToNode(hasText(title))
            rule.onNode(group and hasStateDescription("Collapsed")).assertIsDisplayed()
            rule.onNode(group).performClick()
            rule.onNode(group and hasStateDescription("Expanded")).assertIsDisplayed()
            rule.onNode(group).performClick()
        }
    }

    @Test
    fun switchSettingMergesLabelAndTogglesOnce() {
        var changes = 0
        rule.activity.setContent {
            var checked by remember { mutableStateOf(false) }
            SeliaDocsTheme(darkTheme = false) {
                SwitchSetting(
                    label = "Finger drawing",
                    checked = checked,
                    tag = "switch-setting",
                    onChange = {
                        changes++
                        checked = it
                    },
                )
            }
        }

        val toggle =
            hasText("Finger drawing") and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onNodeWithTag("switch-setting").assertIsOff()
        rule.onNode(toggle).assertIsOff().performClick()
        rule.onNodeWithTag("switch-setting").assertIsOn()
        rule.runOnIdle { assertEquals(1, changes) }
    }

}
