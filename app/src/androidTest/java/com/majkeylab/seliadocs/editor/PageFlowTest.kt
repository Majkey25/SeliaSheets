package com.majkeylab.seliadocs.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun addDuplicateAndDeletePages() {
        val title = "Pages ${System.nanoTime()}"
        createNotebook(title)
        rule.onNodeWithContentDescription("Open $title").performClick()
        rule.waitForIdle()
        val usesSheet = rule.onAllNodes(hasTestTag("page-thumbnail")).fetchSemanticsNodes().isEmpty()
        val compact = rule.onAllNodes(hasTestTag("compact-more")).fetchSemanticsNodes().isNotEmpty()
        if (usesSheet) openContents(compact)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasTestTag("page-thumbnail")).fetchSemanticsNodes().size == 1
        }

        if (usesSheet) pressBack()
        if (compact) {
            rule.onNodeWithTag("compact-more").performClick()
            rule.onNodeWithText("Add page").performClick()
        } else {
            rule.onNodeWithContentDescription("Add page").performClick()
        }
        if (usesSheet) openContents(compact)
        waitForPageCount(2)
        rule.onNodeWithContentDescription("Page 2 actions").performClick()
        rule.onNodeWithText("Duplicate page").performClick()
        waitForPageCount(3)
        rule.onNodeWithContentDescription("Page 3 actions").performClick()
        rule.onNodeWithText("Delete page").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitForPageCount(2)

        if (usesSheet) pressBack()
        pressBack()
        rule.onNodeWithText("SeliaSheets").assertIsDisplayed()
    }

    private fun createNotebook(title: String) {
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForPageCount(expected: Int) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasTestTag("page-thumbnail")).fetchSemanticsNodes().size == expected
        }
        assertEquals(
            expected,
            rule.onAllNodes(hasTestTag("page-thumbnail")).fetchSemanticsNodes().size,
        )
    }

    private fun openContents(compact: Boolean) {
        if (compact) {
            rule.onNodeWithTag("compact-page-location").performClick()
        } else {
            rule.onNodeWithText("Contents").performClick()
        }
    }
}
