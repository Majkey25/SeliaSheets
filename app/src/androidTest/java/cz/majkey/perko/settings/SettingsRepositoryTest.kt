package cz.majkey.perko.settings

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
        repository.update { it.copy(theme = AppTheme.DARK) }
        assertEquals(AppTheme.DARK, repository.settings.first().theme)
        file.delete()
    }
}
