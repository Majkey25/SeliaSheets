package com.majkeylab.seliadocs.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsWriteFailureTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun failedWriteShowsErrorAndRetrySavesOnlyAfterStorageRecovers() {
        val failWrites = AtomicBoolean(true)
        val preferences = MutableStateFlow(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = preferences

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites.get()) throw IOException("Storage unavailable")
                return transform(preferences.value).also { preferences.value = it }
            }
        }
        val repository = SettingsRepository(store)
        rule.activity.setContent { SeliaDocsApp(settingsRepository = repository) }
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Interface & export").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Dark"))
        rule.onNodeWithText("Dark").performClick()

        rule.onNodeWithText("Settings not saved").assertIsDisplayed()
        assertEquals(null, preferences.value[stringPreferencesKey("theme")])
        rule.onNodeWithText("Retry").performClick()
        rule.onNodeWithText("Settings not saved").assertIsDisplayed()

        failWrites.set(false)
        rule.onNodeWithText("Retry").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            preferences.value[stringPreferencesKey("theme")] == AppTheme.DARK.name
        }
        rule.onNodeWithText("Settings not saved").assertDoesNotExist()
        rule.onNodeWithText("Dark").assertIsSelected()

        failWrites.set(true)
        rule.onNodeWithText("Light").performClick()
        rule.onNodeWithText("Dismiss").performClick()
        rule.onNodeWithText("Settings not saved").assertDoesNotExist()
        rule.onNodeWithText("Dark").assertIsSelected()
        assertEquals(AppTheme.DARK.name, preferences.value[stringPreferencesKey("theme")])

        failWrites.set(false)
        rule.onNodeWithText("Light").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            preferences.value[stringPreferencesKey("theme")] == AppTheme.LIGHT.name
        }
        rule.onNodeWithText("Light").assertIsSelected()
    }
}
