package com.majkeylab.seliadocs.editor

import androidx.activity.compose.setContent
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentsFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chapterPageTitleBookmarkAndAssignmentWorkFromContents() {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 744.dp)),
            ) {
                SeliaDocsApp()
            }
        }
        val notebook = "Contents ${System.nanoTime()}"
        createNotebook(notebook)
        rule.onNodeWithContentDescription("Open $notebook").performClick()
        rule.onNodeWithTag("compact-page-location").performClick()
        rule.onNodeWithContentDescription("Preview of page 1").assertIsDisplayed()

        rule.onNodeWithText("Add chapter").performClick()
        rule.onNodeWithTag("name-dialog-input").performTextInput("Mechanics")
        rule.onNodeWithText("Save").performClick()
        waitForText("Mechanics")
        rule.onNodeWithText("Mechanics").assertIsDisplayed()

        openPageAction("Move to chapter")
        rule.onNodeWithTag("move-chapter-option").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText("Unfiled pages")).fetchSemanticsNodes().isEmpty()
        }

        openPageAction("Rename page")
        rule.onNodeWithTag("name-dialog-input").performTextClearance()
        rule.onNodeWithTag("name-dialog-input").performTextInput("Newton's laws")
        rule.onNodeWithText("Save").performClick()
        waitForText("Newton's laws")
        rule.onNodeWithTag("page-thumbnail").assertTextContains("Newton's laws")

        rule.onNodeWithContentDescription("Bookmark page")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not bookmarked"))
            .performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasContentDescription("Remove page bookmark")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Remove page bookmark")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bookmarked"))
    }

    private fun createNotebook(title: String) {
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(value: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openPageAction(label: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasContentDescription("Page 1 actions")).fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithContentDescription("Page 1 actions").performClick()
        waitForText(label)
        rule.onNodeWithText(label).performClick()
    }
}
