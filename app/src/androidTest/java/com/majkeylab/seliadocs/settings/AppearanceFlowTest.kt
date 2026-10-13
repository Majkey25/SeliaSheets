package com.majkeylab.seliadocs.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class AppearanceFlowTest {
    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val appReady = com.majkeylab.seliadocs.readyAppRule(rule)

    @Test
    fun colorEditsBeforeSettingsEmissionPreserveBothFields() {
        val initial = AppSettings(themePalette = ThemePalette.CUSTOM, darkThemePalette = ThemePalette.CLASSIC)
        val queued = mutableListOf<(AppSettings) -> AppSettings>()
        rule.activity.setContent {
            SeliaDocsTheme(false) {
                SettingsScreen(initial, { queued.add(it) }, {}, {})
            }
        }
        rule.onNodeWithText("Appearance").performClick()
        fun edit(label: String, value: String) {
            rule.onNodeWithTag("settings-list").performScrollToNode(hasText(label))
            rule.onNodeWithText(label).performClick()
            rule.onNodeWithTag("theme-color-hex").performTextReplacement(value)
            closeSoftKeyboard()
            rule.onNodeWithTag("theme-color-apply").performClick()
        }
        edit("Accent", "#006677")
        edit("Background", "#DDEEFF")
        rule.runOnIdle {
            assertEquals(2, queued.size)
            val saved = queued.fold(initial) { current, transform -> transform(current) }
            assertEquals(0xFF006677.toInt(), saved.lightCustomTheme.accent)
            assertEquals(0xFFDDEEFF.toInt(), saved.lightCustomTheme.background)
        }
    }

    @Test
    fun customAppearanceAppliesPersistsAndResetsWithoutChangingDrawing() {
        val repository = SettingsRepository.create(rule.activity.application)
        val previous = runBlocking { repository.settings.first() }
        try {
            runBlocking { repository.update { it.resetAppearance().copy(theme = AppTheme.LIGHT, penWidth = 9f) } }
            openAppearance()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasContentDescription("Dark color palette: Midnight"))
            rule.onNodeWithContentDescription("Dark color palette: Midnight").performClick().assertIsSelected()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasContentDescription("Light color palette: Custom"))
            rule.onNodeWithContentDescription("Light color palette: Custom").performClick().assertIsSelected()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Accent"))
            rule.onNodeWithText("Accent").performClick()
            rule.onNodeWithTag("theme-color-hex").performTextReplacement("#zzzzzz")
            rule.onNodeWithTag("theme-color-apply").assertIsNotEnabled()
            rule.onNodeWithTag("theme-color-hex").performTextReplacement("#006677")
            closeSoftKeyboard()
            rule.onNodeWithTag("theme-color-apply").performClick()
            rule.waitUntil(5_000) { runBlocking { repository.settings.first().lightCustomTheme.accent == 0xFF006677.toInt() } }
            rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Serif"))
            rule.onNodeWithText("Serif").performClick()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("interface-text-scale"))
            rule.onNodeWithTag("interface-text-scale").performSemanticsAction(SemanticsActions.SetProgress) { it(1.25f) }
            rule.waitUntil(5_000) { runBlocking { repository.settings.first().interfaceTextScale == 1.25f } }
            rule.activityRule.scenario.recreate()
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("settings-list").fetchSemanticsNode() }.isSuccess }
            rule.onNodeWithTag("settings-list").performScrollToNode(hasContentDescription("Light color palette: Custom"))
            rule.onNodeWithContentDescription("Light color palette: Custom").assertIsSelected()
            runBlocking {
                val saved = repository.settings.first()
                assertEquals(ThemePalette.MIDNIGHT, saved.darkThemePalette)
                assertEquals(InterfaceFont.SERIF, saved.interfaceFont)
                assertEquals(0xFF006677.toInt(), saved.lightCustomTheme.accent)
            }
            rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("reset-appearance"))
            rule.onNodeWithTag("reset-appearance").performClick()
            rule.waitUntil(5_000) { runBlocking { repository.settings.first().themePalette == ThemePalette.CLASSIC } }
            assertEquals(9f, runBlocking { repository.settings.first().penWidth })
        } finally {
            runBlocking { repository.update { previous } }
        }
    }

    @Test
    fun lightModeWithBlackCustomChromeUsesVisibleSystemBarIcons() {
        val repository = SettingsRepository.create(rule.activity.application)
        val previous = runBlocking { repository.settings.first() }
        try {
            runBlocking { repository.update { it.copy(theme = AppTheme.LIGHT, themePalette = ThemePalette.CUSTOM,
                lightCustomTheme = CustomThemeColors(background = 0xFF000000.toInt(), surface = 0xFF000000.toInt())) } }
            rule.waitUntil(5_000) {
                rule.runOnIdle {
                    val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                    !controller.isAppearanceLightStatusBars && !controller.isAppearanceLightNavigationBars
                }
            }
            rule.runOnIdle {
                assertFalse(WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).isAppearanceLightStatusBars)
            }
        } finally {
            runBlocking { repository.update { previous } }
        }
    }

    private fun openAppearance() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Appearance"))
        rule.onNodeWithText("Appearance").performClick()
    }
}
