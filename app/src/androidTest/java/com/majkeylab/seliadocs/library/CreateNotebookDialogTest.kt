package com.majkeylab.seliadocs.library

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateNotebookDialogTest {
    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val appReady = com.majkeylab.seliadocs.readyAppRule(rule)

    @Test
    fun keyboardLeavesNotebookActionsAndOptionsReachable() {
        var created = false
        rule.activity.setContent {
            SeliaDocsTheme(darkTheme = false) {
                CreateNotebookDialog(AppSettings(), {}, { created = true })
            }
        }
        val name = rule.onNodeWithContentDescription("Notebook name")
        name.performClick().performTextInput("Keyboard notebook")
        val root = name.fetchSemanticsNode().root as android.view.View
        try {
            rule.waitUntil(10_000) {
                rule.runOnIdle {
                    ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
            }
            rule.onNodeWithTag("notebook-orientation-options").performScrollTo().assertIsDisplayed()
            val create = rule.onNodeWithText("Create notebook").assertIsDisplayed()
            val bounds = create.fetchSemanticsNode().boundsInRoot
            rule.runOnIdle {
                val location = IntArray(2).also(root::getLocationOnScreen)
                val visible = android.graphics.Rect().also(root::getWindowVisibleDisplayFrame)
                assertTrue("Create button is behind the keyboard: $bounds vs $visible",
                    bounds.bottom + location[1] <= visible.bottom + 1f)
            }
            create.performClick()
            rule.runOnIdle { assertTrue(created) }
        } finally {
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        }
    }

    @Test
    fun orientationChoicesStayInsideDialogSection() {
        rule.activity.setContent {
            SeliaDocsTheme(darkTheme = false) {
                CreateNotebookDialog(
                    defaults = AppSettings(),
                    onDismiss = {},
                    onCreate = {},
                )
            }
        }
        rule.onNodeWithTag("notebook-orientation-options").performScrollTo()

        val section = rule.onNodeWithTag("notebook-orientation-options").fetchSemanticsNode().boundsInRoot
        val portrait =
            rule.onNodeWithContentDescription("Orientation option: Portrait")
                .fetchSemanticsNode().boundsInRoot
        val landscape =
            rule.onNodeWithContentDescription("Orientation option: Landscape")
                .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Portrait exceeds orientation section: $portrait vs $section",
            portrait.left >= section.left && portrait.right <= section.right,
        )
        assertTrue(
            "Landscape exceeds orientation section: $landscape vs $section",
            landscape.left >= section.left && landscape.right <= section.right,
        )
        assertTrue(
            "Orientation choices overlap: $portrait vs $landscape",
            portrait.right <= landscape.left ||
                landscape.right <= portrait.left ||
                portrait.bottom <= landscape.top ||
                landscape.bottom <= portrait.top,
        )
    }
}
