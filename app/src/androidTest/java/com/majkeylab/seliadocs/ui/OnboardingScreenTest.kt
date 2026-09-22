package com.majkeylab.seliadocs.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun introductionExplainsInputAndBackupThenFinishes() {
        var finishes = 0
        rule.setContent { SeliaDocsTheme { OnboardingScreen(onFinish = { finishes++ }) } }
        rule.onNodeWithText("One notebook per subject").assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Pen, fingers, keyboard").assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("One notebook per subject").assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Your work stays with you").assertIsDisplayed()
        rule.onNodeWithText("Start writing").performClick()
        assertEquals(1, finishes)
    }

    @Test fun skipFinishesWithoutChangingPages() {
        var finishes = 0
        rule.setContent { SeliaDocsTheme { OnboardingScreen(onFinish = { finishes++ }) } }
        rule.onNodeWithText("Skip").performClick()
        assertEquals(1, finishes)
    }

    @Test fun savingPreventsDuplicateCompletion() {
        rule.setContent { SeliaDocsTheme { OnboardingScreen(onFinish = {}, saving = true) } }
        rule.onNodeWithText("Skip").assertIsNotEnabled()
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }
}
