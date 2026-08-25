package com.majkeylab.seliadocs.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileOutputStream

class PdfRenderService : Service() {
    private val binder =
        object : IPdfRenderService.Stub() {
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

            override fun renderPage(
                pdf: ParcelFileDescriptor,
                pageIndex: Int,
                width: Int,
                height: Int,
                output: ParcelFileDescriptor,
            ): Bundle =
                runCatching {
                        output.use { outputDescriptor ->
                            require(
                                width in 1..PdfProtocol.MAX_RENDER_DIMENSION &&
                                    height in 1..PdfProtocol.MAX_RENDER_DIMENSION &&
                                    width.toLong() * height <= PdfProtocol.MAX_RENDER_PIXELS,
                            ) { PdfProtocol.ERROR_LIMIT }
                            pdf.use { pdfDescriptor ->
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
                if (error.message == PdfProtocol.ERROR_LIMIT) PdfProtocol.ERROR_LIMIT else PdfProtocol.ERROR_INVALID,
            )
        }
}
