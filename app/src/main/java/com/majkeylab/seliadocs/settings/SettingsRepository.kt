package com.majkeylab.seliadocs.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.recognition.RecognitionLanguage
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen

internal class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = flow {
        var hasValue = false
        store.data.retryWhen { error, _ ->
            if (error !is IOException) return@retryWhen false
            // Keep the last settings during an outage, and observe writes again after recovery.
            if (!hasValue) emit(androidx.datastore.preferences.core.emptyPreferences())
            delay(1_000)
            true
        }.collect { preferences ->
            hasValue = true
            emit(decode(preferences))
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences -> encode(preferences, transform(decode(preferences)).validated()) }
    }

    private fun decode(preferences: Preferences): AppSettings =
        AppSettings(
            defaultTool = enumValue(preferences[Keys.defaultTool], DefaultTool.PEN),
            penWidth = preferences[Keys.penWidth] ?: 4f,
            highlighterWidth = preferences[Keys.highlighterWidth] ?: 22f,
            penColorArgb = preferences[Keys.penColorArgb] ?: 0xFF202124.toInt(),
            highlighterColorArgb = preferences[Keys.highlighterColorArgb] ?: 0x66FFD54F,
            fingerDrawing = preferences[Keys.fingerDrawing] ?: false,
            defaultCoverColor =
                enumValue(preferences[Keys.defaultCoverColor], CoverColor.PERIWINKLE),
            defaultCoverPattern =
                enumValue(preferences[Keys.defaultCoverPattern], CoverPattern.SOLID),
            defaultPaper = enumValue(preferences[Keys.defaultPaper], PaperTemplate.RULED),
            defaultOrientation =
                enumValue(preferences[Keys.defaultOrientation], PageOrientation.PORTRAIT),
            theme = enumValue(preferences[Keys.theme], AppTheme.SYSTEM),
            themePalette = enumValue(preferences[Keys.themePalette], ThemePalette.CLASSIC),
            darkThemePalette = preferences[Keys.darkThemePalette]?.let { enumValue(it, ThemePalette.CLASSIC) },
            lightCustomTheme = decodeCustomTheme(preferences, "light", CustomThemeColors()),
            darkCustomTheme = decodeCustomTheme(preferences, "dark", CustomThemeColors.Dark),
            interfaceFont = enumValue(preferences[Keys.interfaceFont], InterfaceFont.SYSTEM),
            interfaceTextScale = preferences[Keys.interfaceTextScale] ?: 1f,
            interfaceCorners = enumValue(preferences[Keys.interfaceCorners], InterfaceCorners.STANDARD),
            onboardingComplete = preferences[Keys.onboardingComplete] ?: false,
            pageTransition = preferences[Keys.pageTransition] ?: false,
            shapeAssist = preferences[Keys.shapeAssist] ?: true,
            imageOcr = preferences[Keys.imageOcr] ?: true,
            handwritingRecognition = preferences[Keys.handwritingRecognition] ?: false,
            recognitionLanguage = enumValue(preferences[Keys.recognitionLanguage], RecognitionLanguage.CZECH),
        ).validated()

    private fun encode(preferences: androidx.datastore.preferences.core.MutablePreferences, value: AppSettings) {
        preferences[Keys.defaultTool] = value.defaultTool.name
        preferences[Keys.penWidth] = value.penWidth
        preferences[Keys.highlighterWidth] = value.highlighterWidth
        preferences[Keys.penColorArgb] = value.penColorArgb
        preferences[Keys.highlighterColorArgb] = value.highlighterColorArgb
        preferences[Keys.fingerDrawing] = value.fingerDrawing
        preferences[Keys.defaultCoverColor] = value.defaultCoverColor.name
        preferences[Keys.defaultCoverPattern] = value.defaultCoverPattern.name
        preferences[Keys.defaultPaper] = value.defaultPaper.name
        preferences[Keys.defaultOrientation] = value.defaultOrientation.name
        preferences[Keys.theme] = value.theme.name
        preferences[Keys.themePalette] = value.themePalette.name
        value.darkThemePalette?.let { preferences[Keys.darkThemePalette] = it.name } ?: preferences.remove(Keys.darkThemePalette)
        encodeCustomTheme(preferences, "light", value.lightCustomTheme)
        encodeCustomTheme(preferences, "dark", value.darkCustomTheme)
        preferences[Keys.interfaceFont] = value.interfaceFont.name
        preferences[Keys.interfaceTextScale] = value.interfaceTextScale
        preferences[Keys.interfaceCorners] = value.interfaceCorners.name
        preferences[Keys.onboardingComplete] = value.onboardingComplete
        preferences[Keys.pageTransition] = value.pageTransition
        preferences[Keys.shapeAssist] = value.shapeAssist
        preferences[Keys.imageOcr] = value.imageOcr
        preferences[Keys.handwritingRecognition] = value.handwritingRecognition
        preferences[Keys.recognitionLanguage] = value.recognitionLanguage.name
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private fun decodeCustomTheme(preferences: Preferences, mode: String, defaults: CustomThemeColors) = CustomThemeColors(
        accent = preferences[intPreferencesKey("custom_${mode}_accent")] ?: defaults.accent,
        background = preferences[intPreferencesKey("custom_${mode}_background")] ?: defaults.background,
        surface = preferences[intPreferencesKey("custom_${mode}_surface")] ?: defaults.surface,
        text = preferences[intPreferencesKey("custom_${mode}_text")] ?: defaults.text,
        secondary = preferences[intPreferencesKey("custom_${mode}_secondary")] ?: defaults.secondary,
        outline = preferences[intPreferencesKey("custom_${mode}_outline")] ?: defaults.outline,
        error = preferences[intPreferencesKey("custom_${mode}_error")] ?: defaults.error,
    )

    private fun encodeCustomTheme(preferences: androidx.datastore.preferences.core.MutablePreferences, mode: String, value: CustomThemeColors) {
        preferences[intPreferencesKey("custom_${mode}_accent")] = value.accent
        preferences[intPreferencesKey("custom_${mode}_background")] = value.background
        preferences[intPreferencesKey("custom_${mode}_surface")] = value.surface
        preferences[intPreferencesKey("custom_${mode}_text")] = value.text
        preferences[intPreferencesKey("custom_${mode}_secondary")] = value.secondary
        preferences[intPreferencesKey("custom_${mode}_outline")] = value.outline
        preferences[intPreferencesKey("custom_${mode}_error")] = value.error
    }

    private object Keys {
        val defaultTool = stringPreferencesKey("default_tool")
        val penWidth = floatPreferencesKey("pen_width")
        val highlighterWidth = floatPreferencesKey("highlighter_width")
        val penColorArgb = intPreferencesKey("pen_color_argb")
        val highlighterColorArgb = intPreferencesKey("highlighter_color_argb")
        val fingerDrawing = booleanPreferencesKey("finger_drawing")
        val defaultCoverColor = stringPreferencesKey("default_cover_color")
        val defaultCoverPattern = stringPreferencesKey("default_cover_pattern")
        val defaultPaper = stringPreferencesKey("default_paper")
        val defaultOrientation = stringPreferencesKey("default_orientation")
        val theme = stringPreferencesKey("theme")
        val themePalette = stringPreferencesKey("theme_palette")
        val darkThemePalette = stringPreferencesKey("dark_theme_palette")
        val interfaceFont = stringPreferencesKey("interface_font")
        val interfaceTextScale = floatPreferencesKey("interface_text_scale")
        val interfaceCorners = stringPreferencesKey("interface_corners")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val pageTransition = booleanPreferencesKey("page_transition")
        val shapeAssist = booleanPreferencesKey("shape_assist")
        val imageOcr = booleanPreferencesKey("image_ocr")
        val handwritingRecognition = booleanPreferencesKey("handwriting_recognition")
        val recognitionLanguage = stringPreferencesKey("recognition_language")
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
