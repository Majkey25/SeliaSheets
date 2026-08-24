package cz.majkey.perko.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.ui.PaperPreview
import cz.majkey.perko.ui.PerkoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookPreviewTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun paperPreviewsExposeLabelsAndSelection() {
        rule.setContent {
            PerkoTheme {
                PaperPreview(PaperTemplate.GRID, selected = true, onClick = {})
            }
        }

        rule.onNodeWithText("Grid").assertIsDisplayed().assertIsSelected()
    }
}
