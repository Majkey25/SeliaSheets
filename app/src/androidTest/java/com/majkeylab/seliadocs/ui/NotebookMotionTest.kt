package com.majkeylab.seliadocs.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.data.NotebookEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotebookMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingDecorationDoesNotInterceptInputBehindIt() {
        var clicks = 0
        rule.setContent {
            SeliaDocsTheme {
                Box(Modifier.size(300.dp, 400.dp)) {
                    Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.Center).testTag("under-cover")) { Text("Draw") }
                    NotebookOpeningVisual(book(), { 0.35f }, Modifier.fillMaxSize())
                }
            }
        }
        rule.onNodeWithTag("under-cover").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun disabledOpeningCreatesNoDecorationAndFinishesOnce() {
        val finished = mutableListOf<String>()
        rule.setContent { SeliaDocsTheme { NotebookOpeningCover(book(), false, finished::add) } }
        rule.waitForIdle()
        rule.onNodeWithTag("notebook-opening-decoration").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("motion"), finished) }
    }

    @Test fun openingFinishesWithoutLeavingAHiddenLayer() {
        val finished = mutableListOf<String>()
        rule.setContent { SeliaDocsTheme { NotebookOpeningCover(book(), true, finished::add) } }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("notebook-opening-decoration").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("motion"), finished) }
    }

    private fun book() = NotebookEntity("motion", "Physics", "PERIWINKLE", "BAND", "RULED", "PORTRAIT", false, false, 1, 1, null)
}
