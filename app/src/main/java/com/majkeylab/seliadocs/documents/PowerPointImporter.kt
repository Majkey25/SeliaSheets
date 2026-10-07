package com.majkeylab.seliadocs.documents

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import com.majkeylab.seliadocs.pdf.ImportedPdf
import com.majkeylab.seliadocs.pdf.PdfImporter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val POWERPOINT_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

internal class PowerPointImporter(
    private val context: Context,
    private val pdfImporter: PdfImporter,
) {
    // The caller holds LibraryMutationGate, as for ordinary PDF import.
    suspend fun import(notebookId: String, uri: Uri, afterPageId: String?, renderHost: ViewGroup): List<ImportedPdf> = withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "presentation-import-").toFile()
        val source = File(directory, "source.pptx")
        val converted = File(directory, "slides.pdf")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Presentation source unavailable")
            input.use { stream ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("Presentation source could not be read")
                        if (count > PowerPointPreflight.MAX_INPUT_BYTES - total) throw IOException("PPTX exceeds 32 MiB. Export it as PDF first.")
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            }
            val metadata = PowerPointPreflight.inspect(source)
            currentCoroutineContext().ensureActive()
            val chunks = PowerPointRenderer.render(renderHost, source, converted, metadata)
            currentCoroutineContext().ensureActive()
            val title = pdfImporter.displayName(uri, "PowerPoint slides").take(240).substringBeforeLast('.').takeIf(String::isNotBlank) ?: "PowerPoint slides"
            pdfImporter.importMany(notebookId, chunks.map(Uri::fromFile), afterPageId, "$title.pdf")
        } finally {
            directory.deleteRecursively()
        }
    }
}
