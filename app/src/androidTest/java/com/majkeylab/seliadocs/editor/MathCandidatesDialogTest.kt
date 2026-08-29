package com.majkeylab.seliadocs.editor

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.recognition.InkMathCandidate
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MathCandidatesDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun titleCandidateTargetsSelectionAndDismissAreWired() {
        val selected = AtomicReference<InkMathCandidate>()
        val dismissed = AtomicBoolean()
        val candidates = List(20) { index -> InkMathCandidate("$index+1=", (index + 1).toString()) }
        rule.setContent {
            SeliaDocsTheme {
                MathCandidatesDialog(
                    candidates = candidates,
                    onSelect = selected::set,
                    onDismiss = { dismissed.set(true) },
                )
            }
        }

        rule.onNodeWithText("Choose math result").assertIsDisplayed()
        rule.onNodeWithTag("math-candidate-0").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(candidates.first(), selected.get())
        rule.onNodeWithTag("math-candidate-19").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("math-candidates-dismiss").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(dismissed.get())
    }
}
