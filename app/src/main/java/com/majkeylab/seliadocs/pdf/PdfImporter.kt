package com.majkeylab.seliadocs.pdf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.PdfImportResult
import com.majkeylab.seliadocs.data.PdfPageSpec
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ImportedPdf(
    val sourceId: String,
    val pageIds: List<String>,
    val pageCount: Int,
)

internal class PdfImporter(
    private val resolver: ContentResolver,
    private val assets: AssetStore,
    private val repository: SeliaDocsRepository,
    private val sandbox: PdfSandboxClient,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(notebookId: String, uri: Uri): ImportedPdf =
        withContext(Dispatchers.IO) {
            assets.prepare()
            val token = idFactory()
            val temporary = assets.file(".pdf-import-$token.tmp")
            val destination = assets.file("pdf-$token.pdf")
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val byteSize = copyBounded(uri, temporary, digest)
                requirePdfHeader(temporary)
                val info = sandbox.inspect(temporary)
                if (!temporary.renameTo(destination)) throw IOException("PDF could not be installed")
                val result: PdfImportResult =
                    repository.importPdf(
                        notebookId = notebookId,
                        assetId = destination.name,
                        displayName = displayName(uri),
                        byteSize = byteSize,
                        sha256 = digest.digest().toHex(),
                        pages = info.pages.map { PdfPageSpec(it.width, it.height) },
                    )
                committed = true
                ImportedPdf(result.sourceId, result.pageIds, info.pages.size)
            } finally {
                temporary.delete()
                if (!committed) destination.delete()
            }
        }

    private fun copyBounded(uri: Uri, destination: File, digest: MessageDigest): Long {
        val input = resolver.openInputStream(uri) ?: throw IOException("PDF source unavailable")
        var total = 0L
        input.buffered().use { source ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (total > MAX_PDF_BYTES - read) throw IOException("PDF is too large")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        }
        if (total == 0L) throw IOException("PDF is empty")
        return total
    }

    private fun requirePdfHeader(file: File) {
        val header = ByteArray(PDF_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        if (read != header.size || !header.contentEquals(PDF_HEADER)) throw IOException("Invalid PDF header")
    }

    private fun displayName(uri: Uri): String {
        val queried =
            runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        cursor.firstString(OpenableColumns.DISPLAY_NAME)
                    }
                }
                .getOrNull()
        return queried?.trim()?.takeIf(String::isNotEmpty)?.take(255) ?: "Imported PDF.pdf"
    }

    private fun Cursor.firstString(column: String): String? {
        if (!moveToFirst()) return null
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX[value ushr 4]
            output[index * 2 + 1] = HEX[value and 0x0f]
        }
        return output.concatToString()
    }

    private companion object {
        val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_PDF_BYTES = 256L * 1024 * 1024
        const val HEX = "0123456789abcdef"
    }
}
