package com.majkeylab.seliadocs

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class AdaptiveLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryTopBarUsesOneSafeTopInset() {
        assertSingleTopInset("library-top-bar")
    }

    @Test
    fun editorTopBarUsesOneSafeTopInset() {
        val title = "Inset editor ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Open $title").performClick()

        assertSingleTopInset("editor-top-bar")
    }

    @Test
    fun settingsTopBarUsesOneSafeTopInset() {
        rule.onNodeWithText("Settings").performClick()

        assertSingleTopInset("settings-top-bar")
    }

    @Test
    fun backupTopBarUsesOneSafeTopInset() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App & privacy"))
        rule.onNodeWithText("App & privacy").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Backup & restore"))
        rule.onNodeWithText("Backup & restore").performClick()

        assertSingleTopInset("backup-top-bar")
    }

    private fun assertSingleTopInset(tag: String) {
        var safeTopInset = 0
        var density = 0f
        rule.runOnIdle {
            safeTopInset =
                ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                    ?.getInsets(
                        WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
                    )
                    ?.top
                    ?: 0
            density = rule.activity.resources.displayMetrics.density
        }
        assertTrue("Expected a non-zero safe top inset", safeTopInset > 0)
        val rootBounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val contentHeight = TopAppBarDefaults.TopAppBarExpandedHeight.value * density
        assertEquals("$tag root must stay edge-to-edge", 0f, rootBounds.top, 0.5f)
        assertEquals(
            "$tag must consume the safe top inset once",
            safeTopInset + contentHeight,
            rootBounds.height,
            0.5f,
        )
        val titleBounds =
            rule.onNodeWithTag("$tag-title", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag title overlaps the safe top inset: ${titleBounds.top} < $safeTopInset",
            titleBounds.top >= safeTopInset,
        )
        assertTrue(
            "$tag title exceeds its Material content slot: ${titleBounds.bottom} > ${safeTopInset + contentHeight}",
            titleBounds.bottom <= safeTopInset + contentHeight,
        )
    }
}
