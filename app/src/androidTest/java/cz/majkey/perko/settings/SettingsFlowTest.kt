package cz.majkey.perko.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("App details"))
        rule.onNodeWithText("App details").performClick()

        rule.onNodeWithText("Version 0.1.0-beta.1").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Support this app → Buy Me a Coffee"))
        rule.onNodeWithText("Support this app → Buy Me a Coffee").assertIsDisplayed()
    }
}
