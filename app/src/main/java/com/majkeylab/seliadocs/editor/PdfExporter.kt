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
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.NotebookContent
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PAGE_TEXT_BOTTOM
import com.majkeylab.seliadocs.data.PAGE_TEXT_MARGIN
import com.majkeylab.seliadocs.data.PAGE_TEXT_TOP
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.PdfSourceEntity
import com.majkeylab.seliadocs.data.pageTextLayout
import com.majkeylab.seliadocs.pdf.fitPdfRenderSize
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_IMAGE_DECODE_DIMENSION = 4_096
private const val MAX_IMAGE_DECODE_PIXELS = 16L * 1_024 * 1_024

internal fun imageSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
    var sample = 1
    while (width / sample / 2 >= targetWidth && height / sample / 2 >= targetHeight) sample *= 2
    while (
        ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) >
            MAX_IMAGE_DECODE_PIXELS
    ) {
        sample *= 2
    }
    return sample
}

internal suspend fun writePdfToDestination(
    cacheDir: File,
    render: suspend (OutputStream) -> Unit,
    openDestination: () -> OutputStream?,
    deleteDestination: () -> Unit,
) {
    var temporaryPdf: File? = null
    var destinationOpened = false
    try {
        temporaryPdf = File.createTempFile("seliasheets-", ".pdf", cacheDir)
        temporaryPdf.outputStream().use { render(it) }
        val destination = openDestination() ?: error("PDF destination unavailable")
        destinationOpened = true
        destination.use { output -> temporaryPdf.inputStream().use { it.copyTo(output) } }
    } catch (failure: Throwable) {
        if (destinationOpened) {
            try {
                deleteDestination()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    } finally {
        temporaryPdf?.delete()
    }
}

internal class PdfExporter(private val assets: AssetStore) {
    suspend fun write(
        content: NotebookContent,
        output: OutputStream,
        renderPdfPage: suspend (PdfSourceEntity, PageEntity, Int, Int) -> android.graphics.Bitmap? =
            { _, _, _, _ -> null },
    ) {
        require(content.pages.isNotEmpty())
        val pdfSources = content.pdfSources.associateBy(PdfSourceEntity::id)
        val strokesByPage = content.strokes.groupBy { it.pageId }
        val elementsByPage = content.elements.groupBy { it.pageId }
        val blocksByPage = content.blocks.groupBy { it.pageId }
        val document = PdfDocument()
        var documentFailure: Throwable? = null
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
                var background: android.graphics.Bitmap? = null
                var pageFailure: Throwable? = null
                try {
                    background =
                        page.pdfSourceId?.let(pdfSources::get)?.let { source ->
                            val size = fitPdfRenderSize(page.widthPoints, page.heightPoints)
                            renderPdfPage(source, page, size.width, size.height)
                        }
                    renderPage(
                        pdfPage.canvas,
                        page,
                        strokesByPage[page.id].orEmpty(),
                        elementsByPage[page.id].orEmpty(),
                        blocksByPage[page.id].orEmpty(),
                        background,
                    )
                } catch (failure: Throwable) {
                    pageFailure = failure
                    throw failure
                } finally {
                    var cleanupFailure = runCatching { document.finishPage(pdfPage) }.exceptionOrNull()
                    runCatching { background?.recycle() }.exceptionOrNull()?.let { recycleFailure ->
                        cleanupFailure?.addSuppressed(recycleFailure) ?: run { cleanupFailure = recycleFailure }
                    }
                    cleanupFailure?.let { failure ->
                        pageFailure?.addSuppressed(failure) ?: throw failure
                    }
                }
            }
            document.writeTo(output)
        } catch (failure: Throwable) {
            documentFailure = failure
            throw failure
        } finally {
            runCatching(document::close).exceptionOrNull()?.let { cleanupFailure ->
                documentFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun renderPage(
        canvas: Canvas,
        page: PageEntity,
        strokes: List<com.majkeylab.seliadocs.data.StrokeEntity>,
        elements: List<ElementEntity>,
        blocks: List<BlockEntity>,
        pdfBackground: android.graphics.Bitmap?,
    ) {
        canvas.drawColor(Color.rgb(255, 254, 250))
        drawPaper(canvas, page)
        pdfBackground?.let { bitmap ->
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(0f, 0f, page.widthPoints.toFloat(), page.heightPoints.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
        drawPageText(canvas, page, blocks)
        elements.sortedBy(ElementEntity::zIndex).forEach { element -> drawElement(canvas, element) }
        val renderer = CanvasStrokeRenderer.create()
        strokes.sortedBy { it.zIndex }.forEach { stroke ->
            renderer.draw(canvas, stroke.toInkStroke(), Matrix())
        }
    }

    private fun drawPageText(canvas: Canvas, page: PageEntity, blocks: List<BlockEntity>) {
        val text = blocks.sortedBy(BlockEntity::orderIndex).joinToString("\n") { it.text.orEmpty() }
        if (text.isEmpty()) return
        val layout = pageTextLayout(text, page.widthPoints)
        require(layout.height <= page.heightPoints - PAGE_TEXT_TOP - PAGE_TEXT_BOTTOM) {
            "Page text exceeds printable area"
        }
        val saved = canvas.save()
        canvas.translate(PAGE_TEXT_MARGIN.toFloat(), PAGE_TEXT_TOP.toFloat())
        layout.draw(canvas)
        canvas.restoreToCount(saved)
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Image asset is corrupt")
        val targetWidth =
            minOf(
                ceil(element.width).toInt().coerceAtLeast(1),
                canvas.width.coerceAtLeast(1),
                MAX_IMAGE_DECODE_DIMENSION,
            )
        val targetHeight =
            minOf(
                ceil(element.height).toInt().coerceAtLeast(1),
                canvas.height.coerceAtLeast(1),
                MAX_IMAGE_DECODE_DIMENSION,
            )
        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    imageSampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        targetWidth,
                        targetHeight,
                    )
            }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Image asset is corrupt")
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
