package com.majkeylab.seliadocs.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OnboardingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: SettingsRepository
    private lateinit var previous: AppSettings

    @Before fun rememberSettings() = runBlocking {
        repository = SettingsRepository.create(rule.activity)
        previous = repository.settings.first()
    }

    @After fun restoreSettings() = runBlocking { repository.update { previous } }

    @Test fun firstLaunchCompletionSurvivesRecreationAndGuideCanBeReopened() {
        runBlocking { repository.update { it.copy(onboardingComplete = false) } }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("One notebook per subject").assertIsDisplayed()
        rule.onNodeWithText("Skip").performClick()
        rule.waitUntil(5_000) { runBlocking { repository.settings.first().onboardingComplete } }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("onboarding").assertDoesNotExist()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("App & privacy").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("Getting started"))
        rule.onNodeWithText("Getting started").performClick()
        rule.onNodeWithText("One notebook per subject").assertIsDisplayed()
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithTag("settings-top-bar").assertIsDisplayed()
    }

    @Test fun completionWriteFailureDoesNotLockAccessToExistingNotes() {
        val preferences = MutableStateFlow(emptyPreferences())
        val failingStore = object : DataStore<Preferences> {
            override val data = preferences
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("Storage unavailable")
            }
        }
        rule.activity.setContent { SeliaDocsApp(settingsRepository = SettingsRepository(failingStore)) }
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithText("Settings not saved").assertIsDisplayed()
        assertEquals(null, preferences.value[booleanPreferencesKey("onboarding_complete")])
        rule.onNodeWithText("Continue for now").performClick()
        rule.onNodeWithTag("onboarding").assertDoesNotExist()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(null, preferences.value[booleanPreferencesKey("onboarding_complete")])
    }

    @Test fun recoveredSettingsReadObservesSuccessfulCompletionWithoutRestart() {
        val preferences = MutableStateFlow(emptyPreferences())
        var reads = 0
        val recoveringStore = object : DataStore<Preferences> {
            override val data = flow {
                if (reads++ == 0) throw IOException("Temporary read failure")
                emitAll(preferences)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(preferences.value).also { preferences.value = it }
        }
        rule.activity.setContent { SeliaDocsApp(settingsRepository = SettingsRepository(recoveringStore)) }
        rule.onNodeWithText("Skip").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("onboarding").assertDoesNotExist()
        assertEquals(true, preferences.value[booleanPreferencesKey("onboarding_complete")])
    }
}
