package cz.majkey.perko.settings

import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.data.CoverColor
import cz.majkey.perko.data.CoverPattern

internal enum class DefaultTool { PEN, PENCIL, HIGHLIGHTER }

internal enum class AppTheme { SYSTEM, LIGHT, DARK }

internal data class AppSettings(
    val defaultTool: DefaultTool = DefaultTool.PEN,
    val penWidth: Float = 4f,
    val highlighterWidth: Float = 22f,
    val fingerDrawing: Boolean = false,
    val defaultCoverColor: CoverColor = CoverColor.PERIWINKLE,
    val defaultCoverPattern: CoverPattern = CoverPattern.SOLID,
    val defaultPaper: PaperTemplate = PaperTemplate.RULED,
    val defaultOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val theme: AppTheme = AppTheme.SYSTEM,
    val pageTransition: Boolean = true,
) {
    fun validated(): AppSettings =
        copy(
            penWidth = penWidth.coerceIn(2f, 12f),
            highlighterWidth = highlighterWidth.coerceIn(8f, 40f),
        )
}

internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"
