package com.majkeylab.seliadocs.editor

import android.view.MotionEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import java.util.concurrent.atomic.AtomicInteger

@Composable
internal fun ElementSelectionOverlay(
    page: PageEntity,
    element: ElementEntity,
    scaleX: Float,
    scaleY: Float,
    onPreview: (ElementTransform?) -> Unit,
    onCommit: (ElementTransform) -> Unit,
    onGestureOwnershipChange: (Boolean) -> Unit = {},
    eraserPointerId: AtomicInteger? = null,
    onBoundsChanged: (Rect?) -> Unit = {},
) {
    val moveDescription = stringResource(R.string.move_element)
    val resizeDescription = stringResource(R.string.resize_element)
    val rotateDescription = stringResource(R.string.rotate_element)
    val moveLeft = stringResource(R.string.move_left)
    val moveRight = stringResource(R.string.move_right)
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val growElement = stringResource(R.string.grow_element)
    val shrinkElement = stringResource(R.string.shrink_element)
    val rotateClockwise = stringResource(R.string.rotate_clockwise)
    val rotateCounterclockwise = stringResource(R.string.rotate_counterclockwise)
    val latestGestureOwnership = rememberUpdatedState(onGestureOwnershipChange)
    val latestBoundsChanged = rememberUpdatedState(onBoundsChanged)
    val density = LocalDensity.current
    val activeEraserPointer = eraserPointerId ?: remember(element.id) { AtomicInteger(-1) }
    var current by
        remember(element.id, element.x, element.y, element.width, element.height, element.rotation) {
            mutableStateOf(element.transform())
        }
    val cancelGesture = {
        current = element.transform()
        onPreview(null)
    }
    fun commitAccessibilityTransform(proposed: ElementTransform): Boolean {
        val clamped =
            clampElementTransform(
                proposed,
                page.widthPoints.toFloat(),
                page.heightPoints.toFloat(),
            ) ?: return false
        if (clamped == current) return false
        current = clamped
        onPreview(null)
        onCommit(clamped)
        return true
    }
    DisposableEffect(Unit) {
        onDispose {
            latestGestureOwnership.value(false)
            latestBoundsChanged.value(null)
        }
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
                .zIndex(4f)
                .onGloballyPositioned { coordinates ->
                    latestBoundsChanged.value(coordinates.boundsInRoot())
                }
                .pointerInteropFilter { event ->
                    val hasStylus =
                        (0 until event.pointerCount).any { index ->
                            event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
                        }
                    val hasEraser =
                        (0 until event.pointerCount).any { index ->
                            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
                        }
                    hasStylus || hasEraser
                }
                .pointerInput(element.id) {
                    awaitEachGesture {
                        do {
                            val down = awaitPointerEvent(PointerEventPass.Initial)
                        } while (down.changes.none { it.pressed && !it.previousPressed })
                        latestGestureOwnership.value(true)
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            latestGestureOwnership.value(false)
                        }
                    }
                }
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
                                if (activeEraserPointer.get() < 0) {
                                    onCommit(current)
                                    onPreview(null)
                                }
                            },
                        ) { change, amount ->
                            if (activeEraserPointer.get() >= 0) return@detectDragGestures
                            change.consume()
                            update(
                                current.copy(
                                    x = current.x + pointerDeltaToPage(amount.x, density.density, scaleX),
                                    y = current.y + pointerDeltaToPage(amount.y, density.density, scaleY),
                                ),
                                page,
                                onPreview,
                            ) { current = it }
                        }
                    }
                    .semantics {
                        contentDescription = moveDescription
                        customActions =
                            listOf(
                                CustomAccessibilityAction(moveLeft) {
                                    commitAccessibilityTransform(current.copy(x = current.x - MOVE_STEP))
                                },
                                CustomAccessibilityAction(moveRight) {
                                    commitAccessibilityTransform(current.copy(x = current.x + MOVE_STEP))
                                },
                                CustomAccessibilityAction(moveUp) {
                                    commitAccessibilityTransform(current.copy(y = current.y - MOVE_STEP))
                                },
                                CustomAccessibilityAction(moveDown) {
                                    commitAccessibilityTransform(current.copy(y = current.y + MOVE_STEP))
                                },
                            )
                    }
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
                                if (activeEraserPointer.get() < 0) {
                                    onCommit(current)
                                    onPreview(null)
                                }
                            },
                        ) { change, amount ->
                            if (activeEraserPointer.get() >= 0) return@detectDragGestures
                            change.consume()
                            update(
                                current.copy(
                                    width = current.width + pointerDeltaToPage(amount.x, density.density, scaleX),
                                    height = current.height + pointerDeltaToPage(amount.y, density.density, scaleY),
                                ),
                                page,
                                onPreview,
                            ) { current = it }
                        }
                    }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(growElement) {
                                    commitAccessibilityTransform(
                                        current.copy(
                                            width = current.width + RESIZE_STEP,
                                            height = current.height + RESIZE_STEP,
                                        ),
                                    )
                                },
                                CustomAccessibilityAction(shrinkElement) {
                                    commitAccessibilityTransform(
                                        current.copy(
                                            width = current.width - RESIZE_STEP,
                                            height = current.height - RESIZE_STEP,
                                        ),
                                    )
                                },
                            )
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
                                if (activeEraserPointer.get() < 0) {
                                    onCommit(current)
                                    onPreview(null)
                                }
                            },
                        ) { change, amount ->
                            if (activeEraserPointer.get() >= 0) return@detectDragGestures
                            change.consume()
                            val proposed =
                                current.copy(
                                    rotation =
                                        current.rotation +
                                            pointerDeltaToPage(amount.x, density.density, 1f) * 0.5f,
                                )
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
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(rotateClockwise) {
                                    commitAccessibilityTransform(
                                        current.copy(rotation = current.rotation + ROTATION_STEP),
                                    )
                                },
                                CustomAccessibilityAction(rotateCounterclockwise) {
                                    commitAccessibilityTransform(
                                        current.copy(rotation = current.rotation - ROTATION_STEP),
                                    )
                                },
                            )
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

internal fun pointerDeltaToPage(pointerPixels: Float, density: Float, scale: Float): Float {
    require(pointerPixels.isFinite() && density > 0f && density.isFinite() && scale > 0f && scale.isFinite())
    return pointerPixels / density / scale
}

private const val TOUCH_TARGET = 48f
private const val VISUAL_HANDLE = 16f
private const val HANDLE_REGION = 52f
private const val MOVE_STEP = 12f
private const val RESIZE_STEP = 12f
private const val ROTATION_STEP = 15f
