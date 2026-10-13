package com.majkeylab.seliadocs.documents

import android.app.Application
import android.graphics.Color
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPointRendererCategoryTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun officeCategoryCacheRendersAllThreeAxisLabels() = runBlocking {
        checkFixture(booleanArrayOf(true, true, true))
    }

    @Test
    fun sparseOutOfOrderCacheKeepsMissingMiddleCategoryAtItsIndex() = runBlocking {
        checkFixture(booleanArrayOf(true, false, true)) { chart ->
            chart.replace(
                "<c:pt idx=\"0\"><c:v>Geometry</c:v></c:pt><c:pt idx=\"1\"><c:v>Design</c:v></c:pt><c:pt idx=\"2\"><c:v>Physics</c:v></c:pt>",
                "<c:pt idx=\"2\"><c:v>Physics</c:v></c:pt><c:pt idx=\"0\"><c:v>Geometry</c:v></c:pt>",
            )
        }
    }

    @Test
    fun multipleCategoryLevelsFailExplicitlyInsteadOfLosingHierarchy() = runBlocking {
        checkFixture(null) { chart ->
            chart.replace("</c:multiLvlStrCache>", "<c:lvl><c:pt idx=\"0\"><c:v>Sciences</c:v></c:pt></c:lvl></c:multiLvlStrCache>")
        }
    }

    private suspend fun checkFixture(expectedLabels: BooleanArray?, editChart: (String) -> String = { it }) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "presentation-categories-${System.nanoTime()}").apply { mkdirs() }
        lateinit var host: ViewGroup
        activity.scenario.onActivity { host = it.findViewById(android.R.id.content) }
        try {
            val original = File(directory, "original.pptx")
            InstrumentationRegistry.getInstrumentation().context.assets.open("presentations/lecture.pptx").use { input ->
                original.outputStream().use(input::copyTo)
            }
            val source = File(directory, "categories.pptx")
            ZipFile(original).use { input ->
                ZipOutputStream(source.outputStream()).use { output ->
                    for (entry in input.entries().asSequence()) {
                        val bytes = input.getInputStream(entry).use { it.readBytes() }
                        output.putNextEntry(ZipEntry(entry.name))
                        output.write(if (entry.name == "ppt/charts/chart1.xml") editChart(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes)
                        output.closeEntry()
                    }
                }
            }
            val metadata = PowerPointPreflight.inspect(source)
            val output = File(directory, "categories.pdf")
            if (expectedLabels == null) {
                val failure = runCatching { PowerPointRenderer.render(host, source, output, metadata) }.exceptionOrNull()
                assertTrue("Hierarchy must fail explicitly: $failure", failure is IOException && failure.message.orEmpty().contains("unsupported content"))
                assertTrue("Unsupported hierarchy must not be saved", !output.exists())
                return
            }
            PowerPointRenderer.render(host, source, output, metadata)
            val bitmap = PdfSandboxClient(app).renderPage(output, 1, 1280, 720)
            try {
                for ((index, columns) in listOf(180 until 355, 520 until 690, 855 until 1020).withIndex()) {
                    var darkPixels = 0
                    // Below the data labels; excludes blue bars, gray gridlines and the left value axis.
                    for (x in columns) for (y in 520 until 600) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) darkPixels++
                    }
                    if (expectedLabels[index]) assertTrue("Category $index label missing ($darkPixels)", darkPixels > 20)
                    else assertTrue("Sparse category $index moved a label into its gap ($darkPixels)", darkPixels < 5)
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
