package com.majkeylab.seliadocs.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> =
        store.data
            .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
            .map(::decode)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences -> encode(preferences, transform(decode(preferences)).validated()) }
    }

    private fun decode(preferences: Preferences): AppSettings =
        AppSettings(
            defaultTool = enumValue(preferences[Keys.defaultTool], DefaultTool.PEN),
            penWidth = preferences[Keys.penWidth] ?: 4f,
            highlighterWidth = preferences[Keys.highlighterWidth] ?: 22f,
            fingerDrawing = preferences[Keys.fingerDrawing] ?: false,
            defaultCoverColor =
                enumValue(preferences[Keys.defaultCoverColor], CoverColor.PERIWINKLE),
            defaultCoverPattern =
                enumValue(preferences[Keys.defaultCoverPattern], CoverPattern.SOLID),
            defaultPaper = enumValue(preferences[Keys.defaultPaper], PaperTemplate.RULED),
            defaultOrientation =
                enumValue(preferences[Keys.defaultOrientation], PageOrientation.PORTRAIT),
            theme = enumValue(preferences[Keys.theme], AppTheme.SYSTEM),
            pageTransition = preferences[Keys.pageTransition] ?: true,
        ).validated()

    private fun encode(preferences: androidx.datastore.preferences.core.MutablePreferences, value: AppSettings) {
        preferences[Keys.defaultTool] = value.defaultTool.name
        preferences[Keys.penWidth] = value.penWidth
        preferences[Keys.highlighterWidth] = value.highlighterWidth
        preferences[Keys.fingerDrawing] = value.fingerDrawing
        preferences[Keys.defaultCoverColor] = value.defaultCoverColor.name
        preferences[Keys.defaultCoverPattern] = value.defaultCoverPattern.name
        preferences[Keys.defaultPaper] = value.defaultPaper.name
        preferences[Keys.defaultOrientation] = value.defaultOrientation.name
        preferences[Keys.theme] = value.theme.name
        preferences[Keys.pageTransition] = value.pageTransition
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private object Keys {
        val defaultTool = stringPreferencesKey("default_tool")
        val penWidth = floatPreferencesKey("pen_width")
        val highlighterWidth = floatPreferencesKey("highlighter_width")
        val fingerDrawing = booleanPreferencesKey("finger_drawing")
        val defaultCoverColor = stringPreferencesKey("default_cover_color")
        val defaultCoverPattern = stringPreferencesKey("default_cover_pattern")
        val defaultPaper = stringPreferencesKey("default_paper")
        val defaultOrientation = stringPreferencesKey("default_orientation")
        val theme = stringPreferencesKey("theme")
        val pageTransition = booleanPreferencesKey("page_transition")
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun create(context: Context): SettingsRepository =
            instance
                ?: synchronized(this) {
                    instance
                        ?: SettingsRepository(
                                PreferenceDataStoreFactory.create {
                                    context.applicationContext.preferencesDataStoreFile("perko_settings")
                                },
                            )
                            .also { instance = it }
                }
    }
}
