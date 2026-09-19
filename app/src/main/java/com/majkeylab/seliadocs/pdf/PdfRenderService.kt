package com.majkeylab.seliadocs.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileOutputStream
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

class PdfRenderService : Service() {
    private val binder =
        object : IPdfRenderService.Stub() {
            override fun searchText(pdf: ParcelFileDescriptor, pageIndex: Int, query: String?): Bundle = runCatching {
                pdf.use { descriptor ->
                    require(pageIndex >= 0) { PdfProtocol.ERROR_INVALID }
                    val term = normalizePdfSearchQuery(requireNotNull(query) { PdfProtocol.ERROR_INVALID })
                    if (Build.VERSION.SDK_INT < 35) throw UnsupportedOperationException(PdfProtocol.ERROR_SEARCH_UNSUPPORTED)
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount in 1..PdfProtocol.MAX_PAGES) { PdfProtocol.ERROR_LIMIT }
                        require(pageIndex in 0 until renderer.pageCount) { PdfProtocol.ERROR_INVALID }
                        renderer.openPage(pageIndex).use { page -> searchPageText(page, term) }
                    }
                }
            }.getOrElse(::failure)

            override fun inspect(pdf: ParcelFileDescriptor): Bundle =
                runCatching {
                        pdf.use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                val count = renderer.pageCount
                                require(count in 1..PdfProtocol.MAX_PAGES) { PdfProtocol.ERROR_LIMIT }
                                val widths = IntArray(count)
                                val heights = IntArray(count)
                                repeat(count) { index ->
                                    renderer.openPage(index).use { page ->
                                        require(page.width > 0 && page.height > 0) { PdfProtocol.ERROR_INVALID }
                                        widths[index] = page.width
                                        heights[index] = page.height
                                    }
                                }
                                Bundle().apply {
                                    putBoolean(PdfProtocol.SUCCESS, true)
                                    putInt(PdfProtocol.PAGE_COUNT, count)
                                    putIntArray(PdfProtocol.PAGE_WIDTHS, widths)
                                    putIntArray(PdfProtocol.PAGE_HEIGHTS, heights)
                                    putInt(PdfProtocol.SANDBOX_UID, Process.myUid())
                                }
                            }
                        }
                    }
                    .getOrElse(::failure)

            override fun selectText(
                pdf: ParcelFileDescriptor,
                pageIndex: Int,
                startX: Float,
                startY: Float,
                endX: Float,
                endY: Float,
            ): Bundle = runCatching {
                pdf.use { descriptor ->
                    validatePdfSelectionCoordinates(startX, startY, endX, endY)
                    if (Build.VERSION.SDK_INT < 35) {
                        throw UnsupportedOperationException(PdfProtocol.ERROR_SELECTION_UNSUPPORTED)
                    }
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount in 1..PdfProtocol.MAX_PAGES) { PdfProtocol.ERROR_LIMIT }
                        require(pageIndex in 0 until renderer.pageCount) { PdfProtocol.ERROR_INVALID }
                        renderer.openPage(pageIndex).use { page ->
                            val selection = selectPageText(page, startX, startY, endX, endY)
                            Bundle().apply {
                                putBoolean(PdfProtocol.SUCCESS, true)
                                putBoolean(PdfProtocol.SELECTION_FOUND, selection != null)
                                if (selection != null) {
                                    putString(PdfProtocol.SELECTION_TEXT, selection.text)
                                    val bounds = FloatArray(selection.bounds.size * 4)
                                    selection.bounds.forEachIndexed { index, rectangle ->
                                        bounds[index * 4] = rectangle.left
                                        bounds[index * 4 + 1] = rectangle.top
                                        bounds[index * 4 + 2] = rectangle.right
                                        bounds[index * 4 + 3] = rectangle.bottom
                                    }
                                    putFloatArray(PdfProtocol.SELECTION_BOUNDS, bounds)
                                }
                            }
                        }
                    }
                }
            }.getOrElse(::failure)

            override fun renderPage(
                pdf: ParcelFileDescriptor,
                pageIndex: Int,
                width: Int,
                height: Int,
                output: ParcelFileDescriptor,
            ): Bundle =
                runCatching {
                        pdf.use { pdfDescriptor ->
                            output.use { outputDescriptor ->
                                require(
                                    width in 1..PdfProtocol.MAX_RENDER_DIMENSION &&
                                        height in 1..PdfProtocol.MAX_RENDER_DIMENSION &&
                                        width.toLong() * height <= PdfProtocol.MAX_RENDER_PIXELS,
                                ) { PdfProtocol.ERROR_LIMIT }
                                PdfRenderer(pdfDescriptor).use { renderer ->
                                    require(pageIndex in 0 until renderer.pageCount) { PdfProtocol.ERROR_INVALID }
                                    renderer.openPage(pageIndex).use { page ->
                                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                        try {
                                            bitmap.eraseColor(Color.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            FileOutputStream(outputDescriptor.fileDescriptor).use { stream ->
                                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                                            }
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                }
                            }
                        }
                        Bundle().apply { putBoolean(PdfProtocol.SUCCESS, true) }
                    }
                    .getOrElse(::failure)
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun failure(error: Throwable): Bundle =
        Bundle().apply {
            putBoolean(PdfProtocol.SUCCESS, false)
            putString(
                PdfProtocol.ERROR,
                when (error.message) {
                    PdfProtocol.ERROR_LIMIT -> PdfProtocol.ERROR_LIMIT
                    PdfProtocol.ERROR_SELECTION_UNSUPPORTED -> PdfProtocol.ERROR_SELECTION_UNSUPPORTED
                    PdfProtocol.ERROR_SEARCH_UNSUPPORTED -> PdfProtocol.ERROR_SEARCH_UNSUPPORTED
                    else -> PdfProtocol.ERROR_INVALID
                },
            )
        }
}

@RequiresApi(35)
private fun searchPageText(page: PdfRenderer.Page, query: String): Bundle {
    require(page.width > 0 && page.height > 0) { PdfProtocol.ERROR_INVALID }
    val matches = if (query.isEmpty()) emptyList() else page.searchText(query)
    require(matches.size <= PdfProtocol.MAX_SEARCH_MATCHES) { PdfProtocol.ERROR_LIMIT }
    var rectangleCount = 0
    val rectangles = matches.map { match ->
        require(match.bounds.isNotEmpty() && match.bounds.size <= PdfProtocol.MAX_SELECTION_BOUNDS - rectangleCount) { PdfProtocol.ERROR_LIMIT }
        rectangleCount += match.bounds.size
        match.bounds.map { rect ->
            require(listOf(rect.left, rect.top, rect.right, rect.bottom).all(Float::isFinite)) { PdfProtocol.ERROR_INVALID }
            PdfTextBounds((rect.left / page.width).coerceIn(0f, 1f), (rect.top / page.height).coerceIn(0f, 1f),
                (rect.right / page.width).coerceIn(0f, 1f), (rect.bottom / page.height).coerceIn(0f, 1f))
        }
    }
    val coordinates = FloatArray(rectangleCount * 4)
    var offset = 0
    rectangles.forEach { bounds -> bounds.forEach { rect ->
        coordinates[offset++] = rect.left
        coordinates[offset++] = rect.top
        coordinates[offset++] = rect.right
        coordinates[offset++] = rect.bottom
    } }
    return Bundle().apply {
        putBoolean(PdfProtocol.SUCCESS, true)
        putBoolean(PdfProtocol.SEARCH_HAS_TEXT, matches.isNotEmpty() || page.textContents.any { it.text.isNotBlank() })
        putIntArray(PdfProtocol.SEARCH_MATCH_COUNTS, rectangles.map { it.size }.toIntArray())
        putFloatArray(PdfProtocol.SEARCH_BOUNDS, coordinates)
    }
}

@RequiresApi(35)
private fun selectPageText(
    page: PdfRenderer.Page,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
): PdfTextSelection? {
    require(page.width > 0 && page.height > 0) { PdfProtocol.ERROR_INVALID }
    val selected = page.selectContent(
        SelectionBoundary(Point((startX * page.width).roundToInt(), (startY * page.height).roundToInt())),
        SelectionBoundary(Point((endX * page.width).roundToInt(), (endY * page.height).roundToInt())),
    ) ?: return null
    require(selected.selectedTextContents.size <= PdfProtocol.MAX_SELECTION_BOUNDS) { PdfProtocol.ERROR_LIMIT }
    val text = StringBuilder()
    val bounds = mutableListOf<PdfTextBounds>()
    var rectangleCount = 0
    for (content in selected.selectedTextContents) {
        require(content.text.length <= PdfProtocol.MAX_SELECTION_TEXT) { PdfProtocol.ERROR_LIMIT }
        val part = content.text.trim()
        if (part.isEmpty()) continue
        require(text.length + part.length + (if (text.isEmpty()) 0 else 1) <= PdfProtocol.MAX_SELECTION_TEXT) {
            PdfProtocol.ERROR_LIMIT
        }
        if (text.isNotEmpty()) text.append('\n')
        text.append(part)
        require(content.bounds.size <= PdfProtocol.MAX_SELECTION_BOUNDS - rectangleCount) { PdfProtocol.ERROR_LIMIT }
        rectangleCount += content.bounds.size
        for (rectangle in content.bounds) {
            require(listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom).all(Float::isFinite)) {
                PdfProtocol.ERROR_INVALID
            }
            val left = (rectangle.left / page.width).coerceIn(0f, 1f)
            val top = (rectangle.top / page.height).coerceIn(0f, 1f)
            val right = (rectangle.right / page.width).coerceIn(0f, 1f)
            val bottom = (rectangle.bottom / page.height).coerceIn(0f, 1f)
            if (left < right && top < bottom) bounds += PdfTextBounds(left, top, right, bottom)
        }
    }
    if (text.isBlank()) return null
    require(bounds.isNotEmpty()) { PdfProtocol.ERROR_INVALID }
    return PdfTextSelection(text.toString(), bounds, isOcr = false)
}
