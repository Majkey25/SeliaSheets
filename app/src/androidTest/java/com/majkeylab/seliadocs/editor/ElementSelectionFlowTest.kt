package com.majkeylab.seliadocs.editor

import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementSelectionFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectionHandlesMeetTouchTargetAndMoveCommitsOnce() {
        var committed: ElementTransform? = null
        var commits = 0
        val ownership = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(595.dp, 842.dp)) {
                    ElementSelectionOverlay(
                        page = PageEntity("page", "notebook", 0, "BLANK", 595, 842),
                        element = element(),
                        scaleX = 1f,
                        scaleY = 1f,
                        onPreview = {},
                        onCommit = {
                            committed = it
                            commits++
                        },
                        onGestureOwnershipChange = ownership::add,
                    )
                }
            }
        }

        rule.onNodeWithTag("element-selection").assertIsDisplayed()
        rule.onNodeWithTag("element-move-handle").assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("element-resize-handle").assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("element-rotate-handle").assertWidthIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Move selected element").assertIsDisplayed()
        rule.onNodeWithContentDescription("Resize selected element").assertIsDisplayed()
        rule.onNodeWithContentDescription("Rotate selected element").assertIsDisplayed()

        rule.onNodeWithTag("element-move-handle").performTouchInput {
            swipe(center, center + Offset(48f, 0f), durationMillis = 250)
        }

        rule.runOnIdle {
            val dragPixels = 48f - ViewConfiguration.get(rule.activity).scaledTouchSlop
            assertTrue(rule.density.density > 1f)
            assertEquals(element().x + dragPixels / rule.density.density, requireNotNull(committed).x, 0.1f)
            assertEquals(element().y, requireNotNull(committed).y, 0.1f)
            assertEquals(1, commits)
            assertEquals(listOf(true, false), ownership)
        }
    }

    @Test
    fun contextBarExposesElementActions() {
        var duplicated = false
        var broughtForward = false
        var deleted = false
        rule.setContent {
            MaterialTheme {
                ElementContextBar(
                    onDuplicate = { duplicated = true },
                    onBringForward = { broughtForward = true },
                    onDelete = { deleted = true },
                )
            }
        }

        rule.onNodeWithTag("element-context-bar").assertIsDisplayed()
        rule.onNodeWithText("Duplicate").performClick()
        rule.onNodeWithText("Bring forward").performClick()
        rule.onNodeWithText("Delete element").performClick()

        rule.runOnIdle {
            assertTrue(duplicated)
            assertTrue(broughtForward)
            assertTrue(deleted)
        }
    }

    @Test
    fun rotatedResizeUsesElementLocalAxes() {
        var committed: ElementTransform? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(595.dp, 842.dp)) {
                    ElementSelectionOverlay(
                        page = PageEntity("page", "notebook", 0, "BLANK", 595, 842),
                        element = element().copy(x = 180f, y = 220f, rotation = 90f),
                        scaleX = 1f,
                        scaleY = 1f,
                        onPreview = {},
                        onCommit = { committed = it },
                    )
                }
            }
        }

        rule.onNodeWithTag("element-resize-handle").performTouchInput {
            swipe(center, center + Offset(0f, 36f), durationMillis = 250)
        }

        rule.runOnIdle {
            val transform = requireNotNull(committed)
            assertTrue("Expected local width growth, got $transform", transform.width > element().width)
        }
    }

    @Test
    fun accessibilityActionsCommitOnlyChangedClampedTransforms() {
        val committed = mutableListOf<ElementTransform>()
        var previews = 0
        val context = rule.activity
        val selected = element().copy(x = 6f, y = 0f, width = 30f, height = 30f, rotation = 0f)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(595.dp, 842.dp)) {
                    ElementSelectionOverlay(
                        page = PageEntity("page", "notebook", 0, "BLANK", 595, 842),
                        element = selected,
                        scaleX = 1f,
                        scaleY = 1f,
                        onPreview = { previews++ },
                        onCommit = { committed += it },
                    )
                }
            }
        }

        assertTrue(performAction(context.getString(R.string.move_element), context.getString(R.string.move_left)))
        assertFalse(performAction(context.getString(R.string.move_element), context.getString(R.string.move_left)))
        assertTrue(performAction(context.getString(R.string.resize_element), context.getString(R.string.shrink_element)))
        assertFalse(performAction(context.getString(R.string.resize_element), context.getString(R.string.shrink_element)))

        rule.runOnIdle {
            assertEquals(2, committed.size)
            assertEquals(0f, committed[0].x)
            assertEquals(24f, committed[1].width)
            assertEquals(24f, committed[1].height)
            assertEquals(2, previews)
        }
    }

    @Test
    fun accessibilityRotateWrapsTransform() {
        val committed = mutableListOf<ElementTransform>()
        val context = rule.activity
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(595.dp, 842.dp)) {
                    ElementSelectionOverlay(
                        page = PageEntity("page", "notebook", 0, "BLANK", 595, 842),
                        element = element().copy(rotation = 345f),
                        scaleX = 1f,
                        scaleY = 1f,
                        onPreview = {},
                        onCommit = { committed += it },
                    )
                }
            }
        }

        assertTrue(
            performAction(
                context.getString(R.string.rotate_element),
                context.getString(R.string.rotate_clockwise),
            ),
        )

        rule.runOnIdle {
            assertEquals(1, committed.size)
            assertEquals(0f, committed.single().rotation)
        }
    }

    @Test
    fun pointerDeltasUseDpAtDensityAboveOne() {
        assertEquals(20f, pointerDeltaToPage(80f, density = 2f, scale = 2f), 0.001f)
        assertEquals(40f, pointerDeltaToPage(80f, density = 2f, scale = 1f), 0.001f)
    }

    @Test
    fun accessibilitySelectsUnselectedElementThenCommitsTransform() {
        val context = rule.activity
        val committed = mutableListOf<ElementTransform>()
        var selectedId by mutableStateOf<String?>(null)
        val selected = element()
        rule.setContent {
            SeliaDocsTheme {
                PageCanvas(
                    page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                    pageNumber = 1,
                    pageCount = 1,
                    strokes = emptyList(),
                    elements = listOf(selected),
                    blocks = emptyList(),
                    selectedStrokeIds = emptySet(),
                    selectedElementId = selectedId,
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
                    onCommitElementTransform = { committed += it },
                    onSelectElement = { selectedId = it },
                    assetFile = { File(it) },
                )
            }
        }

        assertTrue(
            performAction(
                rule.onNodeWithTag("element-${selected.id}"),
                context.getString(R.string.select_element),
            ),
        )
        rule.onNodeWithTag("element-selection").assertIsDisplayed()
        assertTrue(
            performAction(
                rule.onNodeWithContentDescription(context.getString(R.string.move_element)),
                context.getString(R.string.move_left),
            ),
        )
        rule.runOnIdle { assertEquals(1, committed.size) }
    }

    private fun performAction(description: String, label: String): Boolean =
        performAction(rule.onNodeWithContentDescription(description), label)

    private fun performAction(node: SemanticsNodeInteraction, label: String): Boolean {
        val actions: List<CustomAccessibilityAction> =
            node.fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        val action = actions.firstOrNull { it.label == label }
        assertTrue("Missing action: $label", action != null)
        var handled = false
        rule.runOnIdle { handled = requireNotNull(action?.action).invoke() }
        return handled
    }

    private fun element() =
        ElementEntity(
            id = "element",
            pageId = "page",
            zIndex = 0,
            kind = "TEXT",
            x = 20f,
            y = 30f,
            width = 100f,
            height = 60f,
            rotation = 0f,
            text = "Physics",
            assetId = null,
            shapeKind = null,
            expression = null,
            resultText = null,
        )
}
