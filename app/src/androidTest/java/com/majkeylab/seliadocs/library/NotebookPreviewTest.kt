package com.majkeylab.seliadocs.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.ui.PaperPreview
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
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
            SeliaDocsTheme {
                PaperPreview(PaperTemplate.GRID, selected = true, onClick = {})
            }
        }

        rule.onNodeWithText("Grid").assertIsDisplayed().assertIsSelected()
    }
}
