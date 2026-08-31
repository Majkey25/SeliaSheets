package com.majkeylab.seliadocs.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun recognizeImageText(file: File): String {
    require(file.isFile)
    val bitmap = withContext(Dispatchers.IO) { decodeForOcr(file) }
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        withContext(NonCancellable) {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask().text.trim().take(MAX_OCR_TEXT_LENGTH)
        }
    } finally {
        recognizer.close()
        bitmap.recycle()
    }
}

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
