package com.majkeylab.seliadocs.library

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

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
