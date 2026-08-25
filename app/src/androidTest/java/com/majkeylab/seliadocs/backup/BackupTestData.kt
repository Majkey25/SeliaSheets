package com.majkeylab.seliadocs.backup

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.majkeylab.seliadocs.data.StrokePayload
import com.majkeylab.seliadocs.editor.BrushKind
import com.majkeylab.seliadocs.editor.InkCodec
import java.io.ByteArrayOutputStream

internal fun validTestStrokePayload(): StrokePayload {
    val encoded =
        InkCodec.encode(
            Stroke(
                InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF000000.toInt(), 3f),
                MutableStrokeInputBatch()
                    .add(InputToolType.STYLUS, 10f, 10f, 0L, 0.01f, 0.7f, 0f, 0f)
                    .add(InputToolType.STYLUS, 80f, 80f, 16L, 0.01f, 0.7f, 0f, 0f),
            ),
        )
    return StrokePayload(
        encoded.brushKind.name,
        encoded.colorArgb,
        encoded.size,
        encoded.epsilon,
        encoded.inputs,
    )
}

internal fun testPng(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    bitmap.setPixel(0, 0, color)
    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        output.toByteArray()
    }
}

internal fun testPdf(pageCount: Int): ByteArray {
    require(pageCount > 0)
    val document = PdfDocument()
    repeat(pageCount) { index ->
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
        document.finishPage(page)
    }
    return ByteArrayOutputStream().use { output ->
        document.writeTo(output)
        document.close()
        output.toByteArray()
    }
}
