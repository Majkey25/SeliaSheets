package com.majkeylab.seliadocs.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
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
            )
        }
        val saved = repository.settings.first()
        assertEquals(AppTheme.DARK, saved.theme)
        assertEquals(CoverColor.SAGE, saved.defaultCoverColor)
        assertEquals(CoverPattern.GRID, saved.defaultCoverPattern)
        file.delete()
    }
}
