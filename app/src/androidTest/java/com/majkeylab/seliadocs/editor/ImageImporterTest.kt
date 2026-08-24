package com.majkeylab.seliadocs.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AssetStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
