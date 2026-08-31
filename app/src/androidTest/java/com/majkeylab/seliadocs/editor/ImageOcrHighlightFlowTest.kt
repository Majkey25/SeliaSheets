package com.majkeylab.seliadocs.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.recognition.ImageOcrRegion
import com.majkeylab.seliadocs.recognition.encodeImageOcrRegions
import java.io.File
import org.junit.Rule
import org.junit.Test

class ImageOcrHighlightFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun matchingImageRegionIsRendered() {
        val imageFile = File(compose.activity.cacheDir, "ocr-highlight.png")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            val element =
                ElementEntity(
                    id = "image",
                    pageId = "page",
                    zIndex = 0,
                    kind = ElementKind.IMAGE.name,
                    x = 50f,
                    y = 100f,
                    width = 400f,
                    height = 300f,
                    rotation = 0f,
                    text = "Organic chemistry",
                    assetId = "asset",
                    shapeKind = null,
                    expression = null,
                    resultText = null,
                    ocrRegions =
                        encodeImageOcrRegions(
                            listOf(ImageOcrRegion("Organic chemistry", 0.1f, 0.2f, 0.8f, 0.5f)),
                        ),
                )
            compose.setContent {
                PageCanvas(
                    page = PageEntity("page", "notebook", 0, PaperTemplate.BLANK.name, 595, 842),
                    pageNumber = 1,
                    pageCount = 1,
                    strokes = emptyList(),
                    elements = listOf(element),
                    blocks = emptyList(),
                    selectedStrokeIds = emptySet(),
                    selectedElementId = null,
                    ocrSearchHighlight = OcrSearchHighlight("image", "chemistry"),
                    fingerDrawing = false,
                    tool = EditorTool.TYPE,
                    penWidth = 4f,
                    highlighterWidth = 16f,
                    pageTransitionEnabled = false,
                    onPreviousPage = {},
                    onNextPage = {},
                    onStrokeFinished = { _, _ -> },
                    onEraseFinished = { _, _ -> },
                    onSelectContent = { _, _ -> },
                    onMoveSelection = { _, _ -> },
                    onPageTextChanged = { _, _ -> },
                    onCommitElementTransform = {},
                    assetFile = { imageFile },
                )
            }

            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("ocr-highlight-image").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("ocr-highlight-image").assertIsDisplayed()
        } finally {
            imageFile.delete()
        }
    }
}
