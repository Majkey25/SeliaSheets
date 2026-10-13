package com.majkeylab.seliadocs.editor

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.majkeylab.seliadocs.data.AssetStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ImportedAsset(
    val id: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val file: File,
)

internal val IMAGE_MIME_EXTENSIONS = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/heif" to "heif",
    "image/heic" to "heic",
    "image/bmp" to "bmp",
    "image/gif" to "gif",
)

internal fun validateStillImageDecode(file: File, sampleSize: Int) {
    // Unlike BitmapFactory, ImageDecoder rejects incomplete pixel data by default.
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize(sampleSize)
    }.recycle()
}

internal class ImageImporter(
    private val resolver: ContentResolver,
    private val assets: AssetStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun importImage(uri: Uri): Result<ImportedAsset> {
        var imported: ImportedAsset? = null
        return runCatching {
            withContext(Dispatchers.IO) {
                assets.prepare()
                val temporary = assets.file(".${idFactory()}.tmp")
                require(!temporary.exists()) { "Asset already exists" }
                try {
                    resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Image unavailable" }
                        temporary.outputStream().use { output -> copyBounded(input, output) }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temporary.path, bounds)
                    val actualMime = bounds.outMimeType?.lowercase()
                    require(actualMime in IMAGE_MIME_EXTENSIONS.keys) { "Corrupt or unsupported image" }
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
                    validateStillImageDecode(temporary, sample)
                    val (width, height) = orientedImageDimensions(temporary, bounds.outWidth, bounds.outHeight)
                    val id = "${idFactory()}.${IMAGE_MIME_EXTENSIONS.getValue(requireNotNull(actualMime))}"
                    val destination = assets.file(id)
                    currentCoroutineContext().ensureActive()
                    require(!destination.exists() && temporary.renameTo(destination)) {
                        "Image could not be stored"
                    }
                    ImportedAsset(id, actualMime, width, height, destination).also { imported = it }
                } finally {
                    temporary.delete()
                }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) {
                // The IO result may be discarded before its caller receives asset ownership.
                withContext(NonCancellable + Dispatchers.IO) { imported?.file?.delete() }
                throw failure
            }
        }
    }

    private suspend fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            if (read == 0) throw IOException("Image source could not be read")
            total += read
            require(total <= MAX_ENCODED_BYTES) { "Image file is too large" }
            output.write(buffer, 0, read)
        }
    }

    private companion object {
        const val MAX_DIMENSION = 16_384
        const val MAX_DECODED_BYTES = 128L * 1024 * 1024
        const val MAX_ENCODED_BYTES = 128L * 1024 * 1024
    }
}
