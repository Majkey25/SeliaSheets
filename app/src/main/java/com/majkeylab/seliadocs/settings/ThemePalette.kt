package com.majkeylab.seliadocs.settings

internal enum class ThemePalette {
    CLASSIC, OCEAN, FOREST, ROSE, AMBER, GRAPHITE, PAPER, LAVENDER, COFFEE, MIDNIGHT, HIGH_CONTRAST, MATERIAL, CUSTOM,
}

internal enum class InterfaceFont { SYSTEM, SERIF, MONOSPACE }

internal enum class InterfaceCorners { STANDARD, SOFT, SQUARE }

internal val INTERFACE_TEXT_SCALE_RANGE = 0.9f..1.4f

internal data class CustomThemeColors(
    val accent: Int = 0xFF3156D9.toInt(),
    val background: Int = 0xFFF0F3F5.toInt(),
    val surface: Int = 0xFFF9FAFB.toInt(),
    val text: Int = 0xFF1A1C1E.toInt(),
    val secondary: Int = 0xFF505E6A.toInt(),
    val outline: Int = 0xFF69757E.toInt(),
    val error: Int = 0xFFB3261E.toInt(),
) {
    fun validated() = copy(
        accent = accent or 0xFF000000.toInt(),
        background = background or 0xFF000000.toInt(),
        surface = surface or 0xFF000000.toInt(),
        text = text or 0xFF000000.toInt(),
        secondary = secondary or 0xFF000000.toInt(),
        outline = outline or 0xFF000000.toInt(),
        error = error or 0xFF000000.toInt(),
    )

    companion object {
        val Dark = CustomThemeColors(
            accent = 0xFFAFC6FF.toInt(), background = 0xFF161A1D.toInt(), surface = 0xFF22272B.toInt(),
            text = 0xFFE2E7EC.toInt(), secondary = 0xFFBDC7D0.toInt(), outline = 0xFF9BA4AD.toInt(), error = 0xFFFFB4AB.toInt(),
        )
    }
}
