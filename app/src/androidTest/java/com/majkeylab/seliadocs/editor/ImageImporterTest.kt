package com.majkeylab.seliadocs.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AssetStore
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImporterTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var importer: ImageImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "image-import-test-${System.nanoTime()}")
        importer = ImageImporter(context.contentResolver, AssetStore(root), idFactory = { "asset" })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun pngIsCopiedToPrivateStorage() = runTest {
        val source = File(context.cacheDir, "valid-${System.nanoTime()}.png")
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        val asset = importer.importImage(Uri.fromFile(source)).getOrThrow()

        assertEquals("image/png", asset.mimeType)
        assertEquals(32, asset.width)
        assertEquals(24, asset.height)
        assertTrue(asset.file.canonicalPath.startsWith(root.canonicalPath))
        assertTrue(asset.file.isFile)
        source.delete()
    }

    @Test
    fun corruptImageLeavesNoPrivateFile() = runTest {
        val source = File(context.cacheDir, "corrupt-${System.nanoTime()}.png")
        source.writeText("not an image")

        val result = importer.importImage(Uri.fromFile(source))

        assertTrue(result.isFailure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        source.delete()
    }

    @Test
    fun pngWithGenericOrMissingFileTypeUsesDecodedFormat() = runTest {
        for (suffix in listOf(".bin", "")) {
            val source = File(context.cacheDir, "opaque-${System.nanoTime()}$suffix")
            Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            try {
                val asset = importer.importImage(Uri.fromFile(source)).getOrThrow()
                assertEquals("image/png", asset.mimeType)
                assertEquals(32, asset.width)
                assertEquals(24, asset.height)
                assertTrue(asset.file.isFile)
                assertTrue(asset.file.delete())
            } finally {
                source.delete()
            }
        }
    }

    @Test
    fun cancellationAfterDecodeIsRethrownAndRemovesStagingFile() = runTest {
        val source = File(context.cacheDir, "cancel-${System.nanoTime()}.png")
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val cancellation = CancellationException("Image import cancelled")
        var ids = 0
        val cancellingImporter = ImageImporter(context.contentResolver, AssetStore(root), idFactory = {
            if (++ids == 2) throw cancellation
            "asset"
        })
        try {
            val failure = runCatching { cancellingImporter.importImage(Uri.fromFile(source)) }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test
    fun gifWithPngFileNameRemainsUnsupported() = runTest {
        val source = File(context.cacheDir, "unsupported-${System.nanoTime()}.png")
        source.writeBytes(Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            assertEquals("image/gif", bounds.outMimeType)
            assertTrue(importer.importImage(Uri.fromFile(source)).isFailure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test
    fun exifRotationIsAppliedToImportedDimensionsAndPixels() = runTest {
        val source = File(context.cacheDir, "rotated-${System.nanoTime()}.jpg")
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val asset = importer.importImage(Uri.fromFile(source)).getOrThrow()
        val decoded = decodeOrientedImage(asset.file, 1)
        try {
            assertEquals(24, asset.width)
            assertEquals(32, asset.height)
            assertEquals(24, decoded.width)
            assertEquals(32, decoded.height)
        } finally {
            decoded.recycle()
            source.delete()
        }
    }
}
