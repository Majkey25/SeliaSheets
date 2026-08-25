package com.majkeylab.seliadocs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackReturnsFromEditorToLibrary() {
        val title = "Back navigation ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithContentDescription("Add page").assertIsDisplayed()

        pressBack()

        rule.onNodeWithText("SeliaSheets").assertIsDisplayed()
    }

    @Test
    fun systemBackClosesSettingsAndReturnsToEditor() {
        val title = "Settings back ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithContentDescription("Add page").assertIsDisplayed()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Export PDF").assertIsDisplayed()
        rule.onNodeWithText("Settings").performClick()

        pressBack()

        rule.onNodeWithContentDescription("Add page").assertIsDisplayed()
    }
}
