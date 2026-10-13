package com.majkeylab.seliadocs.pdf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.MAX_PDF_IMPORT_BYTES
import com.majkeylab.seliadocs.data.MAX_PDF_IMPORT_PAGES
import com.majkeylab.seliadocs.data.MAX_PDF_IMPORT_SOURCES
import com.majkeylab.seliadocs.data.PdfImportSpec
import com.majkeylab.seliadocs.data.PdfPageSpec
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    suspend fun import(notebookId: String, uri: Uri, afterPageId: String? = null, sourceName: String? = null): ImportedPdf =
        importMany(notebookId, listOf(uri), afterPageId, sourceName).single()

    suspend fun importMany(
        notebookId: String,
        uris: List<Uri>,
        afterPageId: String? = null,
        sourceName: String? = null,
    ): List<ImportedPdf> =
        withContext(Dispatchers.IO) {
            require(uris.size in 1..MAX_PDF_IMPORT_SOURCES) { "PDF import supports 1 to 100 files" }
            assets.prepare()
            val temporaryFiles = mutableListOf<File>()
            val installedFiles = mutableListOf<File>()
            var committed = false
            try {
                var totalBytes = 0L
                val staged = uris.map { uri ->
                    currentCoroutineContext().ensureActive()
                    val token = idFactory()
                    val temporary = assets.file(".pdf-import-$token.tmp")
                    val destination = assets.file("pdf-$token.pdf")
                    require(!temporary.exists() && !destination.exists()) { "PDF asset already exists" }
                    temporaryFiles += temporary
                    val digest = MessageDigest.getInstance("SHA-256")
                    val byteSize = copyBounded(uri, temporary, digest, MAX_PDF_IMPORT_BYTES - totalBytes)
                    totalBytes += byteSize
                    requirePdfHeader(temporary)
                    val name = sourceName?.trim()?.takeIf(String::isNotEmpty)?.take(255) ?: displayName(uri)
                    StagedPdf(temporary, destination, name, byteSize, digest.digest().toHex())
                }
                val documents = sandbox.inspectMany(staged.map { it.temporary })
                require(documents.sumOf { it.pages.size } <= MAX_PDF_IMPORT_PAGES) { "PDF import exceeds 2,000 pages" }
                val sources = staged.mapIndexed { index, pdf ->
                    val info = documents[index]
                    currentCoroutineContext().ensureActive()
                    if (!pdf.temporary.renameTo(pdf.destination)) throw IOException("PDF could not be installed")
                    installedFiles += pdf.destination
                    PdfImportSpec(
                        assetId = pdf.destination.name,
                        displayName = pdf.displayName,
                        byteSize = pdf.byteSize,
                        sha256 = pdf.sha256,
                        pages = info.pages.map { PdfPageSpec(it.width, it.height) },
                    )
                }
                currentCoroutineContext().ensureActive()
                val results = withContext(NonCancellable) {
                    val imported = repository.importPdfs(notebookId, sources, afterPageId)
                    // Keep ownership in sync even if the caller is cancelled as the transaction commits.
                    committed = true
                    imported
                }
                results.map { ImportedPdf(it.sourceId, it.pageIds, it.pageIds.size) }
            } finally {
                temporaryFiles.forEach(File::delete)
                if (!committed) installedFiles.forEach(File::delete)
            }
        }

    private suspend fun copyBounded(uri: Uri, destination: File, digest: MessageDigest, maxBytes: Long): Long {
        val input = resolver.openInputStream(uri) ?: throw IOException("PDF source unavailable")
        var total = 0L
        input.buffered().use { source ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) throw IOException("PDF source could not be read")
                    if (total > maxBytes - read) throw IOException("PDF import exceeds 256 MiB")
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

    fun displayName(uri: Uri, fallback: String = "Imported PDF.pdf"): String {
        val queried =
            runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        cursor.firstString(OpenableColumns.DISPLAY_NAME)
                    }
                }
                .getOrNull()
        return queried?.trim()?.takeIf(String::isNotEmpty)?.take(255) ?: fallback
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

    private data class StagedPdf(
        val temporary: File,
        val destination: File,
        val displayName: String,
        val byteSize: Long,
        val sha256: String,
    )

    private companion object {
        val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val HEX = "0123456789abcdef"
    }
}
