package com.majkeylab.seliadocs.documents

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPointLargeDeckTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun maximumOrdinaryDeckRendersAllOneHundredSlides(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var host: ViewGroup
        var childCount = 0
        activity.scenario.onActivity {
            host = it.findViewById(android.R.id.content)
            childCount = host.childCount
        }
        val directory = File(app.cacheDir, "presentation-large-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "stress.pptx")
            InstrumentationRegistry.getInstrumentation().context.assets.open("presentations/stress.pptx").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val metadata = PowerPointPreflight.inspect(source)
            assertEquals(100, metadata.slideCount)
            val output = File(directory, "slides.pdf")
            val started = SystemClock.elapsedRealtime()
            val chunks = PowerPointRenderer.render(host, source, output, metadata)
            val duration = SystemClock.elapsedRealtime() - started
            val evidence = File(requireNotNull(app.getExternalFilesDir(null)), "presentation-qa").apply { mkdirs() }
            File(evidence, "stress-metrics.txt").writeText("conversion_ms=$duration\nchunks=${chunks.size}\nbytes=${chunks.sumOf(File::length)}\n")
            assertEquals(100, PdfSandboxClient(app).inspectMany(chunks).sumOf { it.pages.size })
            assertTrue("Writer must release snapshots between batches", chunks.size > 1)
            assertTrue(chunks.sumOf(File::length) in 1..(256L * 1024 * 1024))
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            var slide = 0
            try {
                for (chunk in chunks) {
                    PdfRenderer(ParcelFileDescriptor.open(chunk, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                        for (index in 0 until pdf.pageCount) {
                            bitmap.eraseColor(Color.WHITE)
                            pdf.openPage(index).use { page -> page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            val pixel = bitmap.getPixel(50, 290)
                            if (slide % 2 == 0) assertTrue("Slide ${slide + 1} missing its green shape", Color.green(pixel) > 120 && Color.blue(pixel) < 130)
                            else assertTrue("Slide ${slide + 1} missing its blue shape", Color.blue(pixel) > 130 && Color.green(pixel) < 120)
                            slide++
                        }
                    }
                }
            } finally {
                bitmap.recycle()
            }
            assertEquals(100, slide)
            activity.scenario.onActivity { assertEquals(childCount, host.childCount) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
