package com.majkeylab.seliadocs.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementSelectionFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectionHandlesMeetTouchTargetAndMoveCommitsOnce() {
        var committed: ElementTransform? = null
        var commits = 0
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
                    )
                }
            }
        }

        rule.onNodeWithTag("element-selection").assertIsDisplayed()
        rule.onNodeWithTag("element-move-handle").assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("element-resize-handle").assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("element-rotate-handle").assertWidthIsAtLeast(48.dp)

        rule.onNodeWithTag("element-move-handle").performTouchInput {
            swipe(center, center + Offset(24f, 20f), durationMillis = 250)
        }

        rule.runOnIdle {
            assertTrue(requireNotNull(committed).x > element().x)
            assertTrue(requireNotNull(committed).y > element().y)
            assertTrue(commits == 1)
        }
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
