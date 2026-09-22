package com.majkeylab.seliadocs.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.majkeylab.seliadocs.data.PageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContentsNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val pages = List(4) { index ->
        PageEntity("page-$index", "book", index, "BLANK", 595, 842, title = "Sheet ${index + 1}", bookmarked = index == 2)
    }

    @Test
    fun bookmarkFilterShowsOnlyBookmarkedPagesAndRespondsToChanges() {
        val current = mutableStateOf(pages)
        show(state = { EditorUiState(pages = current.value, selectedPageId = pages.first().id) })
        compose.onNodeWithTag("contents-bookmarks-filter").performClick().assertIsSelected()
        compose.onNodeWithText("Sheet 3").assertIsDisplayed()
        compose.onNodeWithText("Sheet 1").assertDoesNotExist()
        compose.runOnIdle { current.value = pages.map { it.copy(bookmarked = false) } }
        compose.onNodeWithTag("contents-no-bookmarks").assertIsDisplayed()
        compose.onNodeWithTag("contents-bookmarks-filter").performClick()
        compose.onNodeWithText("Sheet 1").assertIsDisplayed()
    }

    @Test
    fun jumpValidatesInputAndKeyboardDoneUsesThePageCallback() {
        val selected = mutableListOf<String>()
        show(onSelect = selected::add)
        compose.onNodeWithTag("contents-jump-page").performClick()
        val field = compose.onNodeWithTag("contents-page-number")
        for (invalid in listOf("0", "5", "-1", "no", "9999999999", "")) {
            field.performTextReplacement(invalid)
            compose.onNodeWithTag("contents-go-page").assertIsNotEnabled()
            field.performImeAction()
            compose.runOnIdle { assertTrue(selected.isEmpty()) }
        }
        field.performTextReplacement("4")
        field.performImeAction()
        compose.runOnIdle { assertEquals(listOf("page-3"), selected) }
        compose.onNodeWithTag("contents-page-number").assertDoesNotExist()
        compose.onNodeWithTag("contents-jump-page").performClick()
        compose.onNodeWithTag("contents-page-number").performTextReplacement("1")
        compose.onNodeWithTag("contents-go-page").performClick()
        compose.runOnIdle { assertEquals(listOf("page-3", "page-0"), selected) }
    }

    @Test
    fun sliderPreviewsWithoutNavigatingUntilPointerIsReleased() {
        val selected = mutableListOf<String>()
        show(onSelect = selected::add)
        val slider = compose.onNodeWithTag("contents-page-slider")
        slider.performTouchInput {
            down(Offset(width * 0.1f, center.y))
            moveTo(center, delayMillis = 100)
            moveTo(Offset(width * 0.95f, center.y), delayMillis = 100)
        }
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        compose.waitForIdle()
        compose.onNodeWithText("4 / 4").assertIsDisplayed()
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf("page-3"), selected) }
    }

    @Test
    fun singlePageHasNoUnusableSliderAndJumpUsesCurrentReorderedPage() {
        val current = mutableStateOf(pages.take(1))
        val selected = mutableListOf<String>()
        show(state = { EditorUiState(pages = current.value, selectedPageId = current.value.first().id) }, onSelect = selected::add)
        compose.onNodeWithTag("contents-page-slider").assertDoesNotExist()
        compose.runOnIdle { current.value = pages.reversed().mapIndexed { index, page -> page.copy(pageIndex = index) } }
        compose.onNodeWithTag("contents-jump-page").performClick()
        compose.onNodeWithTag("contents-page-number").performTextReplacement("1")
        compose.onNodeWithTag("contents-page-number").performImeAction()
        compose.runOnIdle { assertEquals(listOf("page-3"), selected) }
    }

    @Test
    fun busyPanelKeepsItsLayoutAndDisablesOpenMenuAndNavigation() {
        val enabled = mutableStateOf(true)
        val selected = mutableListOf<String>()
        show(onSelect = selected::add, enabled = { enabled.value })
        val before = compose.onNodeWithTag("contents-jump-page").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Page 1 actions").performClick()
        compose.runOnIdle { enabled.value = false }

        listOf("contents-jump-page", "contents-page-slider", "contents-bookmarks-filter").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("Add chapter").assertIsNotEnabled()
        compose.onNodeWithText("Sheet 1").assertIsNotEnabled()
        compose.onAllNodesWithTag("contents-bookmark")[0].assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page 1 actions").assertIsNotEnabled()
        listOf("Rename page", "Move to chapter", "Duplicate page", "Delete page").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        assertEquals(before, compose.onNodeWithTag("contents-jump-page").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

    @Test
    fun busyJumpDialogCannotNavigateAndResumesWithoutLosingItsInput() {
        val enabled = mutableStateOf(true)
        val selected = mutableListOf<String>()
        show(onSelect = selected::add, enabled = { enabled.value })
        compose.onNodeWithTag("contents-jump-page").performClick()
        compose.onNodeWithTag("contents-page-number").performTextReplacement("4")
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("contents-page-number").assertIsNotEnabled()
        compose.onNodeWithTag("contents-go-page").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertTrue(selected.isEmpty()); enabled.value = true }
        compose.onNodeWithTag("contents-go-page").performClick()
        compose.runOnIdle { assertEquals(listOf("page-3"), selected) }
    }

    @Test
    fun busyLocationBarKeepsPositionVisibleWithoutOpeningContentsOrBookmarking() {
        var callbacks = 0
        compose.setContent {
            MaterialTheme {
                PageLocationBar(
                    EditorUiState(pages = pages, selectedPageId = pages.first().id),
                    onOpenContents = { callbacks++ }, onBookmarkPage = { _, _ -> callbacks++ }, enabled = false,
                )
            }
        }
        compose.onNodeWithText("Sheet 1").assertIsDisplayed()
        compose.onNodeWithText("Contents").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("Bookmark page").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, callbacks) }
    }

    private fun show(
        state: () -> EditorUiState = { EditorUiState(pages = pages, selectedPageId = pages.first().id) },
        onSelect: (String) -> Unit = {},
        enabled: () -> Boolean = { true },
    ) {
        compose.setContent {
            MaterialTheme {
                ContentsPanel(state(), onSelect, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {},
                    loadPagePreview = { PagePreviewData(emptyList(), emptyList(), emptyList()) }, enabled = enabled())
            }
        }
    }
}
