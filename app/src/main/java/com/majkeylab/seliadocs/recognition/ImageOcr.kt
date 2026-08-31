package com.majkeylab.seliadocs.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class ImageOcrRegion(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class ImageOcrResult(
    val text: String,
    val regions: List<ImageOcrRegion>,
)

internal suspend fun recognizeImage(file: File): ImageOcrResult {
    require(file.isFile)
    val bitmap = withContext(Dispatchers.IO) { decodeForOcr(file) }
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        withContext(NonCancellable) {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
            ImageOcrResult(
                text = result.text.trim().take(MAX_OCR_TEXT_LENGTH),
                regions =
                    result.textBlocks
                        .asSequence()
                        .flatMap { block -> block.lines.asSequence() }
                        .mapNotNull { line ->
                            val bounds = line.boundingBox ?: return@mapNotNull null
                            ImageOcrRegion(
                                text = line.text.trim().take(MAX_OCR_REGION_TEXT_LENGTH),
                                left = bounds.left.toFloat().coerceIn(0f, bitmap.width.toFloat()) / bitmap.width,
                                top = bounds.top.toFloat().coerceIn(0f, bitmap.height.toFloat()) / bitmap.height,
                                right = bounds.right.toFloat().coerceIn(0f, bitmap.width.toFloat()) / bitmap.width,
                                bottom = bounds.bottom.toFloat().coerceIn(0f, bitmap.height.toFloat()) / bitmap.height,
                            ).takeIf(ImageOcrRegion::isValid)
                        }.take(MAX_OCR_REGION_COUNT)
                        .toList(),
            )
        }
    } finally {
        recognizer.close()
        bitmap.recycle()
    }
}

internal fun encodeImageOcrRegions(regions: List<ImageOcrRegion>): String =
    buildString {
        for (region in regions.asSequence().filter(ImageOcrRegion::isValid).take(MAX_OCR_REGION_COUNT)) {
            val encodedText =
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(region.text.trim().take(MAX_OCR_REGION_TEXT_LENGTH).toByteArray(Charsets.UTF_8))
            val record =
                listOf(encodedText, region.left, region.top, region.right, region.bottom).joinToString(",")
            if (length + record.length + 1 > MAX_OCR_REGION_DATA_LENGTH) break
            if (isNotEmpty()) append('\n')
            append(record)
        }
    }

internal fun decodeImageOcrRegions(encoded: String?): List<ImageOcrRegion> =
    encoded.orEmpty().lineSequence().take(MAX_OCR_REGION_COUNT).mapNotNull { record ->
        val fields = record.split(',')
        if (fields.size != OCR_REGION_FIELD_COUNT) return@mapNotNull null
        runCatching {
            ImageOcrRegion(
                text = String(Base64.getUrlDecoder().decode(fields[0]), Charsets.UTF_8),
                left = fields[1].toFloat(),
                top = fields[2].toFloat(),
                right = fields[3].toFloat(),
                bottom = fields[4].toFloat(),
            )
        }.getOrNull()?.takeIf(ImageOcrRegion::isValid)
    }.toList()

internal fun matchingImageOcrRegions(encoded: String?, query: String): List<ImageOcrRegion> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return emptyList()
    return decodeImageOcrRegions(encoded).filter { region -> region.text.contains(normalized, ignoreCase = true) }
}

private fun ImageOcrRegion.isValid(): Boolean =
    text.isNotBlank() &&
        text.length <= MAX_OCR_REGION_TEXT_LENGTH &&
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
        left < right && top < bottom

private fun decodeForOcr(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth / sample > MAX_OCR_DIMENSION || bounds.outHeight / sample > MAX_OCR_DIMENSION) {
        sample *= 2
    }
    return requireNotNull(
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }),
    )
}

private const val MAX_OCR_DIMENSION = 2_048
private const val MAX_OCR_TEXT_LENGTH = 10_000
private const val MAX_OCR_REGION_TEXT_LENGTH = 500
private const val MAX_OCR_REGION_COUNT = 1_000
internal const val MAX_OCR_REGION_DATA_LENGTH = 100_000
private const val OCR_REGION_FIELD_COUNT = 5
