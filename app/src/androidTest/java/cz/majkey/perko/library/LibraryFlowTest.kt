package cz.majkey.perko.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.majkey.perko.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createAndTrashNotebook() {
        val title = "Physics ${System.nanoTime()}"
        createNotebook(title)

        openActions(title, "Move to trash")
        rule.onNodeWithText("Move to trash").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun renameNotebookUpdatesLibrary() {
        val original = "Chemistry ${System.nanoTime()}"
        val renamed = "Organic chemistry ${System.nanoTime()}"
        createNotebook(original)

        openActions(original, "Rename")
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextReplacement(renamed)
        rule.onNodeWithText("Save").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(renamed)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(renamed).assertIsDisplayed()
    }

    @Test
    fun permanentDeleteRemovesTrashedNotebook() {
        val title = "Delete ${System.nanoTime()}"
        createNotebook(title)

        openActions(title, "Move to trash")
        rule.onNodeWithText("Move to trash").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Trash").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        openActions(title, "Delete permanently")
        rule.onNodeWithText("Delete permanently").performClick()
        rule.onNodeWithText("Delete").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun gridTemplateSelectsGridPortraitDefaults() {
        rule.onNodeWithContentDescription("New notebook").performClick()

        rule.onNodeWithText("Grid notebook").performClick()

        rule.onNodeWithContentDescription("Paper option: Grid").assertIsSelected()
        rule.onNodeWithContentDescription("Orientation option: Portrait").assertIsSelected()
    }

    private fun createNotebook(title: String) {
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    private fun openActions(title: String, expectedAction: String) {
        rule.onNodeWithContentDescription("Notebook actions: $title").performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(expectedAction)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
