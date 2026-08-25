package com.majkeylab.seliadocs.backup

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createDocumentContractUsesEditableBackupNameAndMime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = backupFileName(LocalDate.of(2026, 8, 25))
        val intent = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE).createIntent(context, name)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(BACKUP_MIME_TYPE, intent.type)
        assertEquals("SeliaSheets-backup-2026-08-25.seliasheets", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun replaceRequiresConfirmationFromBackupScreen() {
        openBackupScreen()

        rule.onNodeWithText("Export library").assertIsDisplayed()
        rule.onNodeWithText("Merge backup").assertIsDisplayed()
        rule.onNodeWithText("Replace library").performClick()

        rule.onNodeWithText("Replace entire library?").assertIsDisplayed()
        rule.onNodeWithText("Keep existing library").assertIsDisplayed()
    }

    @Test
    fun backupScreenSurvivesRecreationAndBackReturnsToSettings() {
        openBackupScreen()

        rule.activityRule.scenario.recreate()

        rule.onNodeWithText("Backup & restore").assertIsDisplayed()
        pressBack()
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    private fun openBackupScreen() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App & privacy"))
        rule.onNodeWithText("App & privacy").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Backup & restore"))
        rule.onNodeWithText("Backup & restore").performClick()
    }
}
