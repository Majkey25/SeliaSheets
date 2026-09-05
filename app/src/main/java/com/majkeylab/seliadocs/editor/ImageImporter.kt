package com.majkeylab.seliadocs.editor

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import com.majkeylab.seliadocs.data.AssetStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ImportedAsset(
    val id: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val file: File,
)

internal class ImageImporter(
    private val resolver: ContentResolver,
    private val assets: AssetStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun importImage(uri: Uri): Result<ImportedAsset> =
        withContext(Dispatchers.IO) {
            var temporary: File? = null
            runCatching {
                    val declaredMime = declaredMimeType(uri)
                    require(declaredMime in ALLOWED_MIME_TYPES) { "Unsupported image type" }
                    assets.prepare()
                    temporary = assets.file(".${idFactory()}.tmp")
                    require(!temporary.exists()) { "Asset already exists" }
                    resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Image unavailable" }
                        temporary.outputStream().use { output -> copyBounded(input, output) }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temporary.path, bounds)
                    val actualMime = bounds.outMimeType?.lowercase()
                    require(actualMime in ALLOWED_MIME_TYPES) { "Corrupt or unsupported image" }
                    require(bounds.outWidth in 1..MAX_DIMENSION && bounds.outHeight in 1..MAX_DIMENSION) {
                        "Image dimensions are unsupported"
                    }
                    require(bounds.outWidth.toLong() * bounds.outHeight * 4 <= MAX_DECODED_BYTES) {
                        "Image is too large"
                    }
                    var sample = 1
                    while (bounds.outWidth / sample > 2_048 || bounds.outHeight / sample > 2_048) {
                        sample *= 2
                    }
                    decodeOrientedImage(temporary, sample).recycle()
                    val (width, height) = orientedImageDimensions(temporary, bounds.outWidth, bounds.outHeight)
                    val id = "${idFactory()}.${extensionFor(requireNotNull(actualMime))}"
                    val destination = assets.file(id)
                    require(!destination.exists() && temporary.renameTo(destination)) {
                        "Image could not be stored"
                    }
                    ImportedAsset(id, actualMime, width, height, destination)
                }
                .also { result -> if (result.isFailure) temporary?.delete() }
        }

    private fun declaredMimeType(uri: Uri): String? =
        resolver.getType(uri)?.substringBefore(';')?.lowercase()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.'))

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            require(total <= MAX_ENCODED_BYTES) { "Image file is too large" }
            output.write(buffer, 0, read)
        }
    }

    private fun extensionFor(mimeType: String): String =
        when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heif" -> "heif"
            "image/heic" -> "heic"
            else -> error("Unsupported image type")
        }

    private companion object {
        const val MAX_DIMENSION = 16_384
        const val MAX_DECODED_BYTES = 128L * 1024 * 1024
        const val MAX_ENCODED_BYTES = 128L * 1024 * 1024
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
    }
}
