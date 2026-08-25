package com.majkeylab.seliadocs.data

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

internal const val PAGE_TEXT_MARGIN = 48
internal const val PAGE_TEXT_TOP = 54
internal const val PAGE_TEXT_BOTTOM = 42
internal const val PAGE_TEXT_MAX_LENGTH = 100_000

internal fun pageTextLayout(text: String, pageWidth: Int): StaticLayout {
    val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(32, 33, 36)
            textSize = 18f
        }
    return StaticLayout.Builder.obtain(
        text,
        0,
        text.length,
        paint,
        (pageWidth - PAGE_TEXT_MARGIN * 2).coerceAtLeast(1),
    )
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.2f)
        .build()
}

internal fun pageTextFits(text: String, pageWidth: Int, pageHeight: Int): Boolean =
    text.length <= PAGE_TEXT_MAX_LENGTH &&
        pageTextLayout(text, pageWidth).height <= pageHeight - PAGE_TEXT_TOP - PAGE_TEXT_BOTTOM
