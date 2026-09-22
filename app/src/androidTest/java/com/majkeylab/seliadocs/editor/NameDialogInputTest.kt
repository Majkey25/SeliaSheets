package com.majkeylab.seliadocs.editor

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.activity.ComponentActivity
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NameDialogInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun chapterNameIsFocusedAndAcceptsUnicodeImeCommit() {
        var saved: String? = null
        rule.setContent { SeliaDocsTheme(false) {
            NameDialog("Chapter name", "", false, {}, { saved = it }, maxLength = 120)
        } }
        rule.onNodeWithTag("name-dialog-input").assertIsFocused().performTextInput("Žluťoučký – Biology")
        rule.onNodeWithTag("name-dialog-input").performImeAction()
        assertEquals("Žluťoučký – Biology", saved)
    }

    @Test fun chapterLengthIsEnforcedBeforeSave() {
        var saved: String? = null
        rule.setContent { SeliaDocsTheme(false) {
            NameDialog("Chapter name", "Draft", false, {}, { saved = it }, maxLength = 120)
        } }
        rule.onNodeWithTag("name-dialog-input").performTextReplacement("x".repeat(120))
        rule.onNodeWithTag("name-dialog-input").performTextInput("y")
        rule.onNodeWithTag("name-dialog-input").assertTextContains("x".repeat(120))
        rule.onNodeWithText("Save").performClick()
        assertEquals("x".repeat(120), saved)
    }

    @Test fun inputConnectionPreservesComposingTextAndCommitsUnicode() {
        var saved: String? = null
        rule.setContent { SeliaDocsTheme(false) {
            NameDialog("Chapter name", "", false, {}, { saved = it }, maxLength = 120)
        } }
        val field = rule.onNodeWithTag("name-dialog-input").assertIsFocused()
        val root = requireNotNull(field.fetchSemanticsNode().root as? android.view.View)
        rule.runOnIdle {
            val connection = requireNotNull(root.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.setComposingText("Příro", 1))
            assertTrue(connection.setComposingText("Přírodověda", 1))
            assertTrue(connection.finishComposingText())
        }
        field.assertTextContains("Přírodověda")
        field.performImeAction()
        assertEquals("Přírodověda", saved)
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    fun titleFieldAdvertisesHandwritingEditingGestures() {
        rule.setContent { SeliaDocsTheme(false) {
            NameDialog("Chapter name", "", false, {}, {}, maxLength = 120)
        } }
        val root = requireNotNull(rule.onNodeWithTag("name-dialog-input").assertIsFocused()
            .fetchSemanticsNode().root as? android.view.View)
        rule.runOnIdle {
            val info = android.view.inputmethod.EditorInfo()
            requireNotNull(root.onCreateInputConnection(info))
            assertTrue(info.supportedHandwritingGestures.contains(android.view.inputmethod.InsertGesture::class.java))
            assertTrue(info.supportedHandwritingGestures.contains(android.view.inputmethod.DeleteGesture::class.java))
        }
    }
}
