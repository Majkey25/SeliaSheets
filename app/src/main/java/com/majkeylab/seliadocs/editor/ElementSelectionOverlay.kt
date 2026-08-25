package com.majkeylab.seliadocs.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity

@Composable
internal fun ElementSelectionOverlay(
    page: PageEntity,
    element: ElementEntity,
    scaleX: Float,
    scaleY: Float,
    onPreview: (ElementTransform?) -> Unit,
    onCommit: (ElementTransform) -> Unit,
) {
    val moveDescription = stringResource(R.string.move_element)
    val resizeDescription = stringResource(R.string.resize_element)
    val rotateDescription = stringResource(R.string.rotate_element)
    var current by
        remember(element.id, element.x, element.y, element.width, element.height, element.rotation) {
            mutableStateOf(element.transform())
        }
    val cancelGesture = {
        current = element.transform()
        onPreview(null)
    }
    val width = current.width * scaleX
    val height = current.height * scaleY
    Box(
        modifier =
            Modifier
                .offset(
                    (current.x * scaleX - HANDLE_REGION).dp,
                    (current.y * scaleY - HANDLE_REGION).dp,
                )
                .size((width + HANDLE_REGION * 2f).dp, (height + HANDLE_REGION * 2f).dp)
                .rotate(current.rotation)
                .testTag("element-selection"),
    ) {
        Box(
            modifier =
                Modifier
                    .offset(HANDLE_REGION.dp, HANDLE_REGION.dp)
                    .size(width.dp, height.dp)
                    .pointerInput(element.id, scaleX, scaleY) {
                        detectDragGestures(
                            onDragCancel = cancelGesture,
                            onDragEnd = {
                                onCommit(current)
                                onPreview(null)
                            },
                        ) { change, amount ->
                            change.consume()
                            update(
                                current.copy(
                                    x = current.x + amount.x / scaleX,
                                    y = current.y + amount.y / scaleY,
                                ),
                                page,
                                onPreview,
                            ) { current = it }
                        }
                    }
                    .semantics { contentDescription = moveDescription }
                    .testTag("element-move-handle"),
        )
        Box(
            modifier =
                Modifier
                    .offset(HANDLE_REGION.dp, HANDLE_REGION.dp)
                    .size(width.dp, height.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary),
        )
        Handle(
            contentDescription = resizeDescription,
            modifier =
                Modifier
                    .offset(
                        (HANDLE_REGION + width - TOUCH_TARGET / 2f).dp,
                        (HANDLE_REGION + height - TOUCH_TARGET / 2f).dp,
                    )
                    .pointerInput(element.id, scaleX, scaleY) {
                        detectDragGestures(
                            onDragCancel = cancelGesture,
                            onDragEnd = {
                                onCommit(current)
                                onPreview(null)
                            },
                        ) { change, amount ->
                            change.consume()
                            update(
                                current.copy(
                                    width = current.width + amount.x / scaleX,
                                    height = current.height + amount.y / scaleY,
                                ),
                                page,
                                onPreview,
                            ) { current = it }
                        }
                    }
                    .testTag("element-resize-handle"),
        )
        Handle(
            contentDescription = rotateDescription,
            modifier =
                Modifier
                    .offset(
                        (HANDLE_REGION + width / 2f - TOUCH_TARGET / 2f).dp,
                        0.dp,
                    )
                    .pointerInput(element.id) {
                        detectDragGestures(
                            onDragCancel = cancelGesture,
                            onDragEnd = {
                                onCommit(current)
                                onPreview(null)
                            },
                        ) { change, amount ->
                            change.consume()
                            val proposed = current.copy(rotation = current.rotation + amount.x * 0.5f)
                            val clamped =
                                clampElementTransform(
                                    proposed,
                                    page.widthPoints.toFloat(),
                                    page.heightPoints.toFloat(),
                                ) ?: return@detectDragGestures
                            current = clamped
                            onPreview(clamped)
                        }
                    }
                    .testTag("element-rotate-handle"),
        )
    }
}

@Composable
private fun Handle(contentDescription: String, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(TOUCH_TARGET.dp)
                .semantics { this.contentDescription = contentDescription },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.size(VISUAL_HANDLE.dp),
        ) {}
    }
}

private inline fun update(
    proposed: ElementTransform,
    page: PageEntity,
    onPreview: (ElementTransform) -> Unit,
    apply: (ElementTransform) -> Unit,
) {
    val clamped =
        clampElementTransform(
            proposed,
            page.widthPoints.toFloat(),
            page.heightPoints.toFloat(),
        ) ?: return
    apply(clamped)
    onPreview(clamped)
}

private const val TOUCH_TARGET = 48f
private const val VISUAL_HANDLE = 16f
private const val HANDLE_REGION = 52f
