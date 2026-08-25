package com.majkeylab.seliadocs.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class PdfPageSize(val width: Int, val height: Int)

internal data class PdfDocumentInfo(val pages: List<PdfPageSize>, val sandboxUid: Int)

internal class PdfSandboxClient(context: Context) {
    private val application = context.applicationContext

    suspend fun inspect(file: File): PdfDocumentInfo =
        withService { service ->
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    val result = service.inspect(descriptor)
                    result.requireSuccess()
                    val count = result.getInt(PdfProtocol.PAGE_COUNT)
                    val widths = result.getIntArray(PdfProtocol.PAGE_WIDTHS) ?: throw IOException("PDF widths missing")
                    val heights = result.getIntArray(PdfProtocol.PAGE_HEIGHTS) ?: throw IOException("PDF heights missing")
                    val sandboxUid = result.getInt(PdfProtocol.SANDBOX_UID)
                    require(count in 1..PdfProtocol.MAX_PAGES && widths.size == count && heights.size == count)
                    require(sandboxUid > 0)
                    PdfDocumentInfo(
                        pages = List(count) { index -> PdfPageSize(widths[index], heights[index]) },
                        sandboxUid = sandboxUid,
                    )
                }
            }
        }

    suspend fun renderPage(file: File, pageIndex: Int, width: Int, height: Int): Bitmap =
        withService { service ->
            withContext(Dispatchers.IO) {
                val root = File(application.cacheDir, "pdf-render")
                require((root.isDirectory || root.mkdirs()) && root.canWrite())
                val output = File.createTempFile("page-", ".png", root)
                try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                        ParcelFileDescriptor.open(
                            output,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_WRITE_ONLY,
                        ).use { destination ->
                            service.renderPage(source, pageIndex, width, height, destination).requireSuccess()
                        }
                    }
                    BitmapFactory.decodeFile(output.path) ?: throw IOException("Rendered PDF page is invalid")
                } finally {
                    output.delete()
                }
            }
        }

    private suspend fun <T> withService(block: suspend (IPdfRenderService) -> T): T {
        val bound = bind()
        return try {
            block(bound.service)
        } finally {
            runCatching { application.unbindService(bound.connection) }
        }
    }

    private suspend fun bind(): BoundService =
        suspendCancellableCoroutine { continuation ->
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (!continuation.isActive) return
                        val service = IPdfRenderService.Stub.asInterface(binder)
                        if (service == null) {
                            runCatching { application.unbindService(this) }
                            continuation.resumeWithException(IOException("PDF sandbox unavailable"))
                        } else {
                            continuation.resume(BoundService(service, this))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit

                    override fun onNullBinding(name: ComponentName?) {
                        if (continuation.isActive) {
                            runCatching { application.unbindService(this) }
                            continuation.resumeWithException(IOException("PDF sandbox returned no binder"))
                        }
                    }
                }
            val intent = Intent(application, PdfRenderService::class.java)
            if (!application.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                continuation.resumeWithException(IOException("PDF sandbox could not be started"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { application.unbindService(connection) } }
        }

    private fun android.os.Bundle.requireSuccess() {
        if (!getBoolean(PdfProtocol.SUCCESS)) {
            throw IOException(getString(PdfProtocol.ERROR) ?: PdfProtocol.ERROR_INVALID)
        }
    }

    private data class BoundService(
        val service: IPdfRenderService,
        val connection: ServiceConnection,
    )
}
