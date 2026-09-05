package com.majkeylab.seliadocs.editor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.StrokeEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InkSelectionOverlayTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun boundsIncludeOnlySelectedInkAndHalfBrushWidth() {
        val selected = strokeEntity("selected", 10f, 20f, 30f, 40f, size = 4f)
        val ignored = strokeEntity("ignored", 300f, 400f, 320f, 420f, size = 20f)

        assertEquals(
            Rect(8f, 18f, 32f, 42f),
            strokeSelectionBounds(listOf(selected, ignored), setOf(selected.id)),
        )
    }

    @Test
    fun resizeHandlePreviewsAndCommitsOnce() {
        val previews = mutableListOf<InkSelectionTransform>()
        val commits = mutableListOf<InkSelectionTransform>()
        val ownership = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp)) {
                    InkSelectionOverlay(
                        bounds = Rect(80f, 80f, 180f, 140f),
                        scaleX = 1f,
                        scaleY = 1f,
                        onPreview = { it?.let(previews::add) },
                        onCommit = commits::add,
                        onGestureOwnershipChange = ownership::add,
                    )
                }
            }
        }

        rule.onNodeWithTag("ink-resize-handle")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .performTouchInput {
                swipe(center, center + Offset(48f, 48f), durationMillis = 250)
            }

        rule.runOnIdle {
            assertTrue(previews.any { it.scale > 1f && it.rotationDegrees == 0f })
            assertEquals(1, commits.size)
            assertTrue(commits.single().scale > 1f)
            assertEquals(listOf(true, false), ownership)
        }
    }

    @Test
    fun selectedInkShowsHandlesAndCommitsAccessibleRotation() {
        val context = rule.activity
        val stroke = strokeEntity("selected", 50f, 50f, 100f, 100f)
        val commits = mutableListOf<InkSelectionTransform>()
        rule.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = listOf(stroke),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = setOf(stroke.id),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.LASSO,
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
                onCommitInkTransform = commits::add,
                assetFile = { File(it) },
            )
        }

        rule.onNodeWithTag("ink-resize-handle").assertIsDisplayed()
        rule.onNodeWithTag("ink-rotate-handle").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        val rotate =
            rule.onNodeWithContentDescription(context.getString(R.string.rotate_selected_ink))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == context.getString(R.string.rotate_clockwise) }
        rule.runOnIdle { assertTrue(requireNotNull(rotate.action).invoke()) }
        rule.runOnIdle {
            assertEquals(1, commits.size)
            assertEquals(1f, commits.single().scale)
            assertEquals(15f, commits.single().rotationDegrees)
        }
    }

    @Test
    fun disposingOverlayReleasesGestureOwnership() {
        var visible by mutableStateOf(true)
        val ownership = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                if (visible) {
                    InkSelectionOverlay(
                        bounds = Rect(80f, 80f, 180f, 140f),
                        scaleX = 1f,
                        scaleY = 1f,
                        onPreview = {},
                        onCommit = {},
                        onGestureOwnershipChange = ownership::add,
                    )
                }
            }
        }

        rule.runOnIdle { visible = false }

        rule.runOnIdle { assertEquals(listOf(false), ownership) }
    }

    @Test
    fun eachAccessibleCommitStartsFromIdentity() {
        val context = rule.activity
        val commits = mutableListOf<InkSelectionTransform>()
        rule.setContent {
            MaterialTheme {
                InkSelectionOverlay(
                    bounds = Rect(80f, 80f, 180f, 140f),
                    scaleX = 1f,
                    scaleY = 1f,
                    onPreview = {},
                    onCommit = commits::add,
                )
            }
        }

        performAction(
            context.getString(R.string.rotate_selected_ink),
            context.getString(R.string.rotate_clockwise),
        )
        performAction(
            context.getString(R.string.scale_selected_ink),
            context.getString(R.string.scale_up),
        )

        rule.runOnIdle {
            assertEquals(InkSelectionTransform(rotationDegrees = 15f), commits[0])
            assertEquals(InkSelectionTransform(scale = 1.1f), commits[1])
        }
    }

    private fun performAction(description: String, label: String) {
        val action =
            rule.onNodeWithContentDescription(description)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == label }
        rule.runOnIdle { assertTrue(requireNotNull(action.action).invoke()) }
    }

    private fun strokeEntity(
        id: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        size: Float = 4f,
    ): StrokeEntity {
        val inputs =
            MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, startX, startY, 0L, 0.01f, 0.5f, 0f, 0f)
                add(InputToolType.STYLUS, endX, endY, 16L, 0.01f, 0.5f, 0f, 0f)
            }
        val encoded =
            InkCodec.encode(
                Stroke(
                    InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), size),
                    inputs,
                ),
            )
        return StrokeEntity(
            id,
            "page",
            0,
            encoded.brushKind.name,
            encoded.colorArgb,
            encoded.size,
            encoded.epsilon,
            encoded.inputs,
        )
    }
}
