package cz.majkey.perko.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.majkey.perko.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appDetailsShowsVersionAndSupport() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App & privacy"))
        rule.onNodeWithText("App & privacy").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App details"))
        rule.onNodeWithText("App details").assertIsDisplayed()

        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Version 0.1.0-beta.1"))
        rule.onNodeWithText("Version 0.1.0-beta.1").assertIsDisplayed()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Support this app → Buy Me a Coffee"))
        rule.onNodeWithText("Support this app → Buy Me a Coffee").assertIsDisplayed()
    }

    @Test
    fun settingsShowsVisualNotebookDefaultsAndDrawingSamples() {
        rule.onNodeWithText("Settings").performClick()

        rule.onNodeWithText("Notebook defaults").assertIsDisplayed()
        rule.onNodeWithText("Ruled notes").assertIsDisplayed()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Drawing"))
        rule.onNodeWithText("Drawing").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Pen sample"))
        rule.onNodeWithText("Pen sample").assertIsDisplayed()
    }
}
