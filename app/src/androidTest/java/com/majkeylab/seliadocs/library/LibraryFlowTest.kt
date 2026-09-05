package com.majkeylab.seliadocs.library

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

        rule.onNodeWithTag("notebook-dialog-scroll").performTouchInput {
            swipe(
                Offset(centerX, height * 0.75f),
                Offset(centerX, height * 0.45f),
                durationMillis = 300,
            )
        }
        rule.onNodeWithContentDescription("Notebook template: Grid notebook")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()

        rule.onNodeWithContentDescription("Paper option: Grid").assertIsSelected()
        rule.onNodeWithContentDescription("Orientation option: Portrait").assertIsSelected()
    }

    @Test
    fun newestNotebookMenuOpensWhenAnotherNotebookExists() {
        createNotebook("Existing ${System.nanoTime()}")
        val newest = "Newest ${System.nanoTime()}"
        createNotebook(newest)

        openActions(newest, "Move to trash")

        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assertIsDisplayed()
        rule.onNodeWithText("Move to trash").assertIsDisplayed()
    }

    @Test
    fun compactWidthUsesTwoNotebookColumns() {
        setTestViewport(fontScale = 1f)
        val first = "Compact first ${System.nanoTime()}"
        val second = "Compact second ${System.nanoTime()}"
        createNotebook(first)
        createNotebook(second)

        assertNotEquals(
            "360 dp library must place two cards in distinct columns",
            actionBounds(first).left,
            actionBounds(second).left,
            0.5f,
        )
    }

    @Test
    fun largeFontUsesOneNotebookColumn() {
        setTestViewport(fontScale = 2f)
        val first = "Large font first ${System.nanoTime()}"
        val second = "Large font second ${System.nanoTime()}"
        createNotebook(first)
        createNotebook(second)

        assertEquals(
            "200 percent font scale must keep one card column",
            actionBounds(first).left,
            actionBounds(second).left,
            0.5f,
        )
    }

    @Test
    fun longNotebookTitleUsesTwoEllipsizedLinesInsideCard() {
        setTestViewport(fontScale = 1f)
        val title = "Long notebook title ${"section ".repeat(10)}${System.nanoTime()}"
        createNotebook(title)
        val titleNode = rule.onNodeWithText(title)
        val layouts = mutableListOf<TextLayoutResult>()

        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }

        val layout = layouts.single()
        assertEquals("Compact notebook title must use two lines", 2, layout.lineCount)
        assertTrue("Second title line must be ellipsized", layout.isLineEllipsized(1))
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val cardBounds = rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode().boundsInRoot
        assertTrue("Title starts outside card: $titleBounds vs $cardBounds", titleBounds.left >= cardBounds.left)
        assertTrue("Title ends outside card: $titleBounds vs $cardBounds", titleBounds.right <= cardBounds.right)
        assertTrue("Title top exceeds card: $titleBounds vs $cardBounds", titleBounds.top >= cardBounds.top)
        assertTrue("Title bottom exceeds card: $titleBounds vs $cardBounds", titleBounds.bottom <= cardBounds.bottom)
    }

    @Test
    fun notebookHasOneNamedOpenTargetAndSeparateActions() {
        setTestViewport(fontScale = 1f)
        val title = "Open target ${System.nanoTime()}"
        createNotebook(title)
        val titleCenter = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.center
        val openTargets =
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                .filter { node -> node.boundsInRoot.contains(titleCenter) }

        assertEquals("Notebook title must have exactly one open target", 1, openTargets.size)
        val openTarget = openTargets.single()
        assertEquals(listOf("Open $title"), openTarget.config[SemanticsProperties.ContentDescription])
        assertEquals(Role.Button, openTarget.config[SemanticsProperties.Role])
        rule.onNodeWithContentDescription("Notebook actions: $title")
            .assertHasClickAction()
            .performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Move to trash")).fetchSemanticsNodes().isNotEmpty()
        }
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

    private fun setTestViewport(fontScale: Float) {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 744.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    SeliaDocsApp()
                }
            }
        }
    }

    private fun actionBounds(title: String): Rect =
        rule.onNodeWithContentDescription("Notebook actions: $title")
            .fetchSemanticsNode()
            .boundsInRoot
}
