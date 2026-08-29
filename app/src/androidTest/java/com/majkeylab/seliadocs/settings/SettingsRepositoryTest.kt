package com.majkeylab.seliadocs.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
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
            )
        }
        firstJob.cancelAndJoin()

        val reopened = SettingsRepository(PreferenceDataStoreFactory.create { file })
        val settings = reopened.settings.first()

        assertEquals(true, settings.handwritingRecognition)
        assertEquals(RecognitionLanguage.ENGLISH, settings.recognitionLanguage)
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
        store.edit { preferences -> preferences[floatPreferencesKey("pen_width")] = 99f }
        val repository = SettingsRepository(store)

        assertEquals(12f, repository.settings.first().penWidth)
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
