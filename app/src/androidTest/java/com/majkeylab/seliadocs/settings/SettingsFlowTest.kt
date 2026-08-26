package com.majkeylab.seliadocs.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
}
