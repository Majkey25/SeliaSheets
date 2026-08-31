package com.majkeylab.seliadocs.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.recognition.RecognitionLanguage
import com.majkeylab.seliadocs.recognition.RecognitionModelStatus
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRecognitionScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun imageOcrCanBeDisabled() {
        rule.setContent {
            var settings by remember { mutableStateOf(AppSettings()) }
            SeliaDocsTheme(darkTheme = false) {
                SettingsScreen(
                    settings = settings,
                    onUpdate = { transform -> settings = transform(settings) },
                    onBackup = {},
                    onClose = {},
                )
            }
        }

        expandDrawing()
        scrollToTag("settings-image-ocr")
        rule.onNodeWithTag("settings-image-ocr").assertIsOn().performClick()
        rule.onNodeWithTag("settings-image-ocr").assertIsOff()
    }

    @Test
    fun englishSelectionUpdatesStateThenDownloadsEnglishModel() {
        var downloaded: RecognitionLanguage? = null
        rule.setContent {
            var settings by remember { mutableStateOf(AppSettings(handwritingRecognition = true)) }
            SeliaDocsTheme(darkTheme = false) {
                SettingsScreen(
                    settings = settings,
                    onUpdate = { transform -> settings = transform(settings) },
                    onBackup = {},
                    onClose = {},
                    onDownloadRecognitionModel = { downloaded = it },
                )
            }
        }

        expandDrawing()
        rule.onNodeWithTag("settings-handwriting-recognition").assertIsOn()
        rule.onNodeWithTag("settings-recognition-czech").assertIsSelected()
        scrollToTag("settings-recognition-english")
        rule.onNodeWithTag("settings-recognition-english").performClick()
        rule.onNodeWithTag("settings-recognition-english").assertIsSelected()
        scrollToTag("settings-recognition-download")
        rule.onNodeWithTag("settings-recognition-download").performClick()

        rule.runOnIdle { assertEquals(RecognitionLanguage.ENGLISH, downloaded) }
    }

    @Test
    fun downloadingStateIsDisabled() {
        rule.setContent {
            Screen(status = RecognitionModelStatus.Downloading)
        }

        expandDrawing()
        scrollToTag("settings-recognition-downloading")
        rule.onNodeWithTag("settings-recognition-downloading").assertIsNotEnabled()
    }

    @Test
    fun deletingStateIsDisabled() {
        rule.setContent {
            Screen(status = RecognitionModelStatus.Deleting)
        }

        expandDrawing()
        scrollToTag("settings-recognition-deleting")
        rule.onNodeWithTag("settings-recognition-deleting").assertIsNotEnabled()
        rule.onNodeWithTag("settings-recognition-download").assertDoesNotExist()
        rule.onNodeWithTag("settings-recognition-delete").assertDoesNotExist()
    }

    @Test
    fun readyStateDeletesOnce() {
        var deletes = 0
        rule.setContent {
            Screen(
                status = RecognitionModelStatus.Ready,
                onDelete = {
                    assertEquals(RecognitionLanguage.CZECH, it)
                    deletes++
                },
            )
        }

        expandDrawing()
        scrollToTag("settings-recognition-delete")
        rule.onNodeWithTag("settings-recognition-delete").performClick()

        rule.runOnIdle { assertEquals(1, deletes) }
    }

    @Test
    fun failedStateRetriesOnce() {
        var downloads = 0
        rule.setContent {
            Screen(
                status = RecognitionModelStatus.Failed("Model failed"),
                onDownload = {
                    assertEquals(RecognitionLanguage.CZECH, it)
                    downloads++
                },
            )
        }

        expandDrawing()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Retry download"))
        rule.onNode(hasText("Retry download") and hasClickAction()).performClick()

        rule.runOnIdle { assertEquals(1, downloads) }
    }

    private fun expandDrawing() {
        rule.onNodeWithText("Drawing").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Handwriting recognition"))
    }

    private fun scrollToTag(tag: String) {
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
    }

    @androidx.compose.runtime.Composable
    private fun Screen(
        status: RecognitionModelStatus,
        onDownload: (RecognitionLanguage) -> Unit = {},
        onDelete: (RecognitionLanguage) -> Unit = {},
    ) {
        SeliaDocsTheme(darkTheme = false) {
            SettingsScreen(
                settings = AppSettings(),
                onUpdate = {},
                onBackup = {},
                onClose = {},
                recognitionModelStatus = status,
                onDownloadRecognitionModel = onDownload,
                onDeleteRecognitionModel = onDelete,
            )
        }
    }
}
