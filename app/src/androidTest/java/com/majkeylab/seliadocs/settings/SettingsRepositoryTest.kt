package com.majkeylab.seliadocs.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.recognition.RecognitionLanguage

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    @Test
    fun readRecoveryKeepsTheLastSettingsInsteadOfReopeningOnboarding() = runTest {
        val preferences = preferencesOf(booleanPreferencesKey("onboarding_complete") to true)
        var attempts = 0
        val store = object : DataStore<Preferences> {
            override val data = flow {
                val failAfterEmission = attempts++ == 0
                emit(preferences)
                if (failAfterEmission) throw IOException("Temporary read failure")
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(preferences)
        }
        val observed = SettingsRepository(store).settings.take(2).toList()
        assertEquals(listOf(true, true), observed.map { it.onboardingComplete })
        assertEquals(2, attempts)
    }

    @Test
    fun appearanceAndOnboardingPersistWithoutReplacingLegacyMotionChoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "appearance-${System.nanoTime()}.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        try {
            val repository = SettingsRepository(store)
            assertEquals(false, repository.settings.first().pageTransition)
            assertEquals(false, repository.settings.first().onboardingComplete)
            assertEquals(ThemePalette.CLASSIC, repository.settings.first().themePalette)
            store.edit { it[booleanPreferencesKey("page_transition")] = true }
            repository.update { it.copy(themePalette = ThemePalette.FOREST, onboardingComplete = true) }
            val stored = repository.settings.first()
            assertEquals(true, stored.pageTransition)
            assertEquals(ThemePalette.FOREST, stored.themePalette)
            assertEquals(true, stored.onboardingComplete)
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun recognitionDefaultsToDisabledCzech() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create { file })

        val settings = repository.settings.first()

        assertEquals(false, settings.handwritingRecognition)
        assertEquals(RecognitionLanguage.CZECH, settings.recognitionLanguage)
        file.delete()
    }

    @Test
    fun recognitionSettingsPersistAfterStoreRecreation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.update {
            it.copy(
                handwritingRecognition = true,
                recognitionLanguage = RecognitionLanguage.ENGLISH,
                imageOcr = false,
            )
        }
        firstJob.cancelAndJoin()

        val reopened = SettingsRepository(PreferenceDataStoreFactory.create { file })
        val settings = reopened.settings.first()

        assertEquals(true, settings.handwritingRecognition)
        assertEquals(RecognitionLanguage.ENGLISH, settings.recognitionLanguage)
        assertEquals(false, settings.imageOcr)
        file.delete()
    }

    @Test
    fun independentRapidTransformsPreserveBrushAndRecognitionSettings() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create { file })

        coroutineScope {
            launch { repository.update { it.copy(penWidth = 9f) } }
            launch {
                repository.update {
                    it.copy(
                        handwritingRecognition = true,
                        recognitionLanguage = RecognitionLanguage.ENGLISH,
                    )
                }
            }
        }
        val settings = repository.settings.first()

        assertEquals(9f, settings.penWidth)
        assertEquals(true, settings.handwritingRecognition)
        assertEquals(RecognitionLanguage.ENGLISH, settings.recognitionLanguage)
        file.delete()
    }

    @Test
    fun invalidWidthIsClampedAndThemePersists() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create { file }
        store.edit { preferences ->
            preferences[floatPreferencesKey("pen_width")] = 99f
            preferences[floatPreferencesKey("highlighter_width")] = -1f
        }
        val repository = SettingsRepository(store)

        assertEquals(32f, repository.settings.first().penWidth)
        assertEquals(4f, repository.settings.first().highlighterWidth)
        repository.update {
            it.copy(
                theme = AppTheme.DARK,
                defaultCoverColor = CoverColor.SAGE,
                defaultCoverPattern = CoverPattern.GRID,
                shapeAssist = false,
            )
        }
        val saved = repository.settings.first()
        assertEquals(AppTheme.DARK, saved.theme)
        assertEquals(CoverColor.SAGE, saved.defaultCoverColor)
        assertEquals(CoverPattern.GRID, saved.defaultCoverPattern)
        assertEquals(false, saved.shapeAssist)
        repository.update {
            it.copy(
                penColorArgb = 0xFF3156D9.toInt(),
                highlighterColorArgb = 0x66F48FB1,
            )
        }
        val colors = repository.settings.first()
        assertEquals(0xFF3156D9.toInt(), colors.penColorArgb)
        assertEquals(0x66F48FB1, colors.highlighterColorArgb)
        file.delete()
    }
}
