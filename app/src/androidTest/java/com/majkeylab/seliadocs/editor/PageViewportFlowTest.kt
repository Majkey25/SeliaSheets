package com.majkeylab.seliadocs.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PageViewportFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twoFingerPinchZoomsPage() {
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }
        val viewport = compose.onNodeWithTag("page-viewport")

        viewport.performTouchInput {
            pinch(
                start0 = center + Offset(-40f, 0f),
                end0 = center + Offset(-140f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(140f, 0f),
                durationMillis = 300,
            )
        }
        compose.waitUntil(3_000) { zoomDescription(viewport) != "Zoom 100%" }

        assertTrue(zoomDescription(viewport).removePrefix("Zoom ").removeSuffix("%").toInt() > 100)
    }

    private fun zoomDescription(viewport: androidx.compose.ui.test.SemanticsNodeInteraction): String =
        viewport.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
}
