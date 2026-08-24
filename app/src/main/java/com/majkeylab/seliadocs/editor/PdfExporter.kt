package com.majkeylab.seliadocs.editor

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.NotebookContent
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

internal class PdfExporter(private val assets: AssetStore) {
    suspend fun write(content: NotebookContent, output: OutputStream) =
        withContext(Dispatchers.IO) {
            require(content.pages.isNotEmpty())
            val document = PdfDocument()
            try {
                content.pages.sortedBy(PageEntity::pageIndex).forEachIndexed { index, page ->
                    val info =
                        PdfDocument.PageInfo.Builder(
                                page.widthPoints,
                                page.heightPoints,
                                index + 1,
                            )
                            .create()
                    val pdfPage = document.startPage(info)
                    try {
                        renderPage(
                            pdfPage.canvas,
                            page,
                            content.strokes.filter { it.pageId == page.id },
                            content.elements.filter { it.pageId == page.id },
                        )
                    } finally {
                        document.finishPage(pdfPage)
                    }
                }
                document.writeTo(output)
            } finally {
                document.close()
            }
        }

    private fun renderPage(
        canvas: Canvas,
        page: PageEntity,
        strokes: List<com.majkeylab.seliadocs.data.StrokeEntity>,
        elements: List<ElementEntity>,
    ) {
        canvas.drawColor(Color.rgb(255, 254, 250))
        drawPaper(canvas, page)
        val renderer = CanvasStrokeRenderer.create()
        strokes.sortedBy { it.zIndex }.forEach { stroke ->
            renderer.draw(canvas, stroke.toInkStroke(), Matrix())
        }
        elements.sortedBy(ElementEntity::zIndex).forEach { element -> drawElement(canvas, element) }
    }

    private fun drawPaper(canvas: Canvas, page: PageEntity) {
        val template = runCatching { PaperTemplate.valueOf(page.paper) }.getOrDefault(PaperTemplate.BLANK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(213, 215, 220) }
        val spacing = 28f
        when (template) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = spacing
                while (y < page.heightPoints) {
                    canvas.drawLine(0f, y, page.widthPoints.toFloat(), y, paint)
                    y += spacing
                }
            }
            PaperTemplate.GRID -> {
                var x = spacing
                while (x < page.widthPoints) {
                    canvas.drawLine(x, 0f, x, page.heightPoints.toFloat(), paint)
                    x += spacing
                }
                var y = spacing
                while (y < page.heightPoints) {
                    canvas.drawLine(0f, y, page.widthPoints.toFloat(), y, paint)
                    y += spacing
                }
            }
            PaperTemplate.DOT -> {
                var y = spacing
                while (y < page.heightPoints) {
                    var x = spacing
                    while (x < page.widthPoints) {
                        canvas.drawCircle(x, y, 1.3f, paint)
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }

    private fun drawElement(canvas: Canvas, element: ElementEntity) {
        val kind = runCatching { ElementKind.valueOf(element.kind) }.getOrNull() ?: return
        val saved = canvas.save()
        canvas.rotate(
            element.rotation,
            element.x + element.width / 2f,
            element.y + element.height / 2f,
        )
        when (kind) {
            ElementKind.TEXT -> drawText(canvas, element.text.orEmpty(), element)
            ElementKind.MATH -> drawText(canvas, element.resultText.orEmpty(), element)
            ElementKind.IMAGE -> drawImage(canvas, element)
            ElementKind.SHAPE -> drawShape(canvas, element)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawText(canvas: Canvas, text: String, element: ElementEntity) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 33, 36); textSize = 18f }
        val width = (element.width - 16f).toInt().coerceAtLeast(1)
        val layout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setMaxLines((element.height / 22f).toInt().coerceAtLeast(1))
                .build()
        val saved = canvas.save()
        canvas.translate(element.x + 8f, element.y + 8f)
        layout.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun drawImage(canvas: Canvas, element: ElementEntity) {
        val id = element.assetId ?: return
        val file = assets.file(id)
        require(file.isFile) { "Image asset missing" }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: error("Image asset is corrupt")
        try {
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(element.x, element.y, element.x + element.width, element.y + element.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawShape(canvas: Canvas, element: ElementEntity) {
        val kind = element.shapeKind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() } ?: return
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 33, 36)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
        val bounds = RectF(element.x, element.y, element.x + element.width, element.y + element.height)
        when (kind) {
            ShapeKind.LINE -> canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), paint)
            ShapeKind.ARROW -> {
                canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), paint)
                val head = minOf(18f, element.width / 3f)
                val angle = 0.5f
                canvas.drawLine(
                    bounds.right,
                    bounds.centerY(),
                    bounds.right - head * cos(angle),
                    bounds.centerY() - head * sin(angle),
                    paint,
                )
                canvas.drawLine(
                    bounds.right,
                    bounds.centerY(),
                    bounds.right - head * cos(angle),
                    bounds.centerY() + head * sin(angle),
                    paint,
                )
            }
            ShapeKind.ELLIPSE -> canvas.drawOval(bounds, paint)
            ShapeKind.RECTANGLE -> canvas.drawRect(bounds, paint)
            ShapeKind.TRIANGLE -> {
                val path =
                    Path().apply {
                        moveTo(bounds.centerX(), bounds.top)
                        lineTo(bounds.right, bounds.bottom)
                        lineTo(bounds.left, bounds.bottom)
                        close()
                    }
                canvas.drawPath(path, paint)
            }
        }
    }
}
