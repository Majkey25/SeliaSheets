package com.majkeylab.seliadocs.settings

import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.recognition.RecognitionLanguage

internal enum class DefaultTool { PEN, PENCIL, HIGHLIGHTER }

internal enum class AppTheme { SYSTEM, LIGHT, DARK }

internal data class AppSettings(
    val defaultTool: DefaultTool = DefaultTool.PEN,
    val penWidth: Float = 4f,
    val highlighterWidth: Float = 22f,
    val penColorArgb: Int = 0xFF202124.toInt(),
    val highlighterColorArgb: Int = 0x66FFD54F,
    val fingerDrawing: Boolean = false,
    val defaultCoverColor: CoverColor = CoverColor.PERIWINKLE,
    val defaultCoverPattern: CoverPattern = CoverPattern.SOLID,
    val defaultPaper: PaperTemplate = PaperTemplate.RULED,
    val defaultOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val theme: AppTheme = AppTheme.SYSTEM,
    val pageTransition: Boolean = true,
    val shapeAssist: Boolean = true,
    val handwritingRecognition: Boolean = false,
    val recognitionLanguage: RecognitionLanguage = RecognitionLanguage.CZECH,
) {
    fun validated(): AppSettings =
        copy(
            penWidth = penWidth.coerceIn(2f, 12f),
            highlighterWidth = highlighterWidth.coerceIn(8f, 40f),
        )
}

internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"
