package com.majkeylab.seliadocs.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        rule.onNodeWithText("Replace library").performScrollTo().performClick()

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

    @Test
    fun coldRootAcknowledgesPendingReplacementWithoutOpeningBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeName = "cold-root-replacement-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putLong(REPLACEMENT_PRODUCED, 1).commit())
        val viewModel =
            BackupViewModel(
                context.applicationContext as Application,
                replacementPreferences = preferences,
            )

        try {
            assertTrue(viewModel.hasPendingReplacement())
            rule.activity.setContent { SeliaDocsApp(backupViewModel = viewModel) }
            rule.waitUntil(timeoutMillis = 10_000) { !viewModel.hasPendingReplacement() }
            assertFalse(viewModel.hasPendingReplacement())
        } finally {
            viewModel.viewModelScope.cancel()
            context.deleteSharedPreferences(storeName)
        }
    }

    @Test
    fun recreationDoesNotReportClaimedReplacementTwiceBeforeAcknowledgement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeName = "replacement-claim-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putLong(REPLACEMENT_PRODUCED, 1).commit())
        val viewModel =
            BackupViewModel(
                context.applicationContext as Application,
                replacementPreferences = preferences,
            )
        val acknowledgementStarted = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger(0)

        fun renderReporter() {
            rule.activity.setContent {
                val state by viewModel.state.collectAsStateWithLifecycle()
                MaterialTheme {
                    LibraryReplacementReporter(
                        replacementGeneration = state.replacementGeneration,
                        claimReplacement = viewModel::claimPendingReplacement,
                        acknowledgeReplacement = { generation ->
                            acknowledgementStarted.complete(Unit)
                            withContext(NonCancellable) {
                                releaseAcknowledgement.await()
                                viewModel.acknowledgeReplacement(generation)
                            }
                        },
                        releaseReplacementClaim = viewModel::releaseReplacementClaim,
                        onLibraryReplaced = { callbacks.incrementAndGet() },
                    )
                }
            }
        }

        try {
            renderReporter()
            rule.waitUntil(timeoutMillis = 10_000) { acknowledgementStarted.isCompleted }
            rule.activityRule.scenario.recreate()
            renderReporter()
            rule.waitForIdle()
            assertEquals(1, callbacks.get())
            releaseAcknowledgement.complete(Unit)
            rule.waitUntil(timeoutMillis = 10_000) { !viewModel.hasPendingReplacement() }
            assertEquals(1, callbacks.get())
        } finally {
            releaseAcknowledgement.complete(Unit)
            viewModel.viewModelScope.cancel()
            context.deleteSharedPreferences(storeName)
        }
    }

    @Test
    fun systemBackDoesNotCloseBackupWhileOperationRuns() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    BackupScreen(
                        state = BackupUiState(running = true),
                        onClose = {},
                        onExport = {},
                        onRestore = { _, _ -> },
                    )
                }
            }
        }

        rule.onNodeWithText("Backup & restore").assertIsDisplayed()
        pressBack()
        rule.onNodeWithText("Backup & restore").assertIsDisplayed()
    }

    private fun openBackupScreen() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("App & privacy"))
        rule.onNodeWithText("App & privacy").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Backup & restore"))
        rule.onNodeWithText("Backup & restore").performClick()
    }
}
