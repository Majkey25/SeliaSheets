package com.majkeylab.seliadocs.editor

import android.view.MotionEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.majkeylab.seliadocs.R
import java.util.concurrent.atomic.AtomicInteger

internal data class InkSelectionTransform(
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
)

@Composable
internal fun InkSelectionOverlay(
    bounds: Rect,
    scaleX: Float,
    scaleY: Float,
    onPreview: (InkSelectionTransform?) -> Unit,
    onCommit: (InkSelectionTransform) -> Unit,
    onMove: (CanvasPoint) -> Unit = {},
    onGestureOwnershipChange: (Boolean) -> Unit = {},
    eraserPointerId: AtomicInteger? = null,
    onBoundsChanged: (Rect?) -> Unit = {},
) {
    if (bounds.width <= 0f || bounds.height <= 0f || scaleX <= 0f || scaleY <= 0f) return
    val scaleDescription = stringResource(R.string.scale_selected_ink)
    val rotateDescription = stringResource(R.string.rotate_selected_ink)
    val moveDescription = stringResource(R.string.move_selected_ink)
    val moveLeft = stringResource(R.string.move_left)
    val moveRight = stringResource(R.string.move_right)
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val scaleUp = stringResource(R.string.scale_up)
    val scaleDown = stringResource(R.string.scale_down)
    val rotateClockwise = stringResource(R.string.rotate_clockwise)
    val rotateCounterclockwise = stringResource(R.string.rotate_counterclockwise)
    val density = LocalDensity.current
    val latestGestureOwnership = rememberUpdatedState(onGestureOwnershipChange)
    val latestBoundsChanged = rememberUpdatedState(onBoundsChanged)
    val activeEraserPointer = eraserPointerId ?: remember(bounds) { AtomicInteger(-1) }
    var current by remember(bounds, scaleX, scaleY) { mutableStateOf(InkSelectionTransform()) }
    var moveDelta by remember(bounds, scaleX, scaleY) { mutableStateOf(CanvasPoint(0f, 0f)) }
    DisposableEffect(Unit) {
        onDispose {
            latestGestureOwnership.value(false)
            latestBoundsChanged.value(null)
        }
    }
    val commitAccessibility: (InkSelectionTransform) -> Boolean = { proposed ->
        val clamped = proposed.copy(scale = proposed.scale.coerceIn(MIN_SCALE, MAX_SCALE))
        if (clamped == current) {
            false
        } else {
            current = InkSelectionTransform()
            onPreview(null)
            onCommit(clamped)
            true
        }
    }
    val cancel = {
        current = InkSelectionTransform()
        onPreview(null)
        onGestureOwnershipChange(false)
    }
    val finish = {
        val committed = current
        current = InkSelectionTransform()
        onPreview(null)
        onGestureOwnershipChange(false)
        if (committed != InkSelectionTransform()) onCommit(committed)
    }
    val width = bounds.width * scaleX * current.scale
    val height = bounds.height * scaleY * current.scale
    val left = bounds.center.x - bounds.width * current.scale / 2f
    val top = bounds.center.y - bounds.height * current.scale / 2f
    Box(
        modifier =
            Modifier
                .offset((left * scaleX - HANDLE_REGION).dp, (top * scaleY - HANDLE_REGION).dp)
                .size((width + HANDLE_REGION * 2f).dp, (height + HANDLE_REGION * 2f).dp)
                .rotate(current.rotationDegrees)
                .zIndex(4f)
                .onGloballyPositioned { coordinates ->
                    latestBoundsChanged.value(coordinates.boundsInRoot())
                }
                .pointerInteropFilter { event ->
                    (0 until event.pointerCount).any { index ->
                        val tool = event.getToolType(index)
                        tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                    }
                }
                .testTag("ink-selection-overlay"),
    ) {
        Box(
            Modifier
                .offset(HANDLE_REGION.dp, HANDLE_REGION.dp)
                .size(width.dp, height.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary)
                .pointerInput(bounds, scaleX, scaleY) {
                    detectDragGestures(
                        onDragStart = {
                            if (activeEraserPointer.get() < 0) onGestureOwnershipChange(true)
                        },
                        onDragCancel = {
                            moveDelta = CanvasPoint(0f, 0f)
                            onGestureOwnershipChange(false)
                        },
                        onDragEnd = {
                            val committed = moveDelta
                            moveDelta = CanvasPoint(0f, 0f)
                            onGestureOwnershipChange(false)
                            if (
                                activeEraserPointer.get() < 0 &&
                                (committed.x != 0f || committed.y != 0f)
                            ) {
                                onMove(committed)
                            }
                        },
                    ) { change, amount ->
                        if (activeEraserPointer.get() >= 0) return@detectDragGestures
                        change.consume()
                        moveDelta =
                            CanvasPoint(
                                moveDelta.x + pointerDeltaToPage(amount.x, density.density, scaleX),
                                moveDelta.y + pointerDeltaToPage(amount.y, density.density, scaleY),
                            )
                    }
                }
                .semantics {
                    contentDescription = moveDescription
                    customActions =
                        listOf(
                            CustomAccessibilityAction(moveLeft) {
                                onMove(CanvasPoint(-MOVE_STEP, 0f))
                                true
                            },
                            CustomAccessibilityAction(moveRight) {
                                onMove(CanvasPoint(MOVE_STEP, 0f))
                                true
                            },
                            CustomAccessibilityAction(moveUp) {
                                onMove(CanvasPoint(0f, -MOVE_STEP))
                                true
                            },
                            CustomAccessibilityAction(moveDown) {
                                onMove(CanvasPoint(0f, MOVE_STEP))
                                true
                            },
                        )
                }
                .testTag("ink-move-handle"),
        )
        InkHandle(
            contentDescription = scaleDescription,
            modifier =
                Modifier
                    .offset(
                        (HANDLE_REGION + width - TOUCH_TARGET / 2f).dp,
                        (HANDLE_REGION + height - TOUCH_TARGET / 2f).dp,
                    )
                    .pointerInput(bounds, scaleX, scaleY) {
                        detectDragGestures(
                            onDragStart = {
                                if (activeEraserPointer.get() < 0) onGestureOwnershipChange(true)
                            },
                            onDragCancel = cancel,
                            onDragEnd = { if (activeEraserPointer.get() < 0) finish() else cancel() },
                        ) { change, amount ->
                            if (activeEraserPointer.get() >= 0) return@detectDragGestures
                            change.consume()
                            val widthPixels = with(density) { width.dp.toPx() }
                            val heightPixels = with(density) { height.dp.toPx() }
                            val diagonalSquared = widthPixels * widthPixels + heightPixels * heightPixels
                            if (diagonalSquared <= 0f) return@detectDragGestures
                            val progress =
                                (amount.x * widthPixels + amount.y * heightPixels) / diagonalSquared
                            val proposed =
                                current.copy(
                                    scale = (current.scale * (1f + progress)).coerceIn(MIN_SCALE, MAX_SCALE),
                                )
                            if (proposed != current) {
                                current = proposed
                                onPreview(proposed)
                            }
                        }
                    }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(scaleUp) {
                                    commitAccessibility(current.copy(scale = current.scale * SCALE_STEP))
                                },
                                CustomAccessibilityAction(scaleDown) {
                                    commitAccessibility(current.copy(scale = current.scale / SCALE_STEP))
                                },
                            )
                    }
                    .testTag("ink-resize-handle"),
        )
        InkHandle(
            contentDescription = rotateDescription,
            modifier =
                Modifier
                    .offset(
                        (HANDLE_REGION + width / 2f - TOUCH_TARGET / 2f).dp,
                        0.dp,
                    )
                    .pointerInput(bounds) {
                        detectDragGestures(
                            onDragStart = {
                                if (activeEraserPointer.get() < 0) onGestureOwnershipChange(true)
                            },
                            onDragCancel = cancel,
                            onDragEnd = { if (activeEraserPointer.get() < 0) finish() else cancel() },
                        ) { change, amount ->
                            if (activeEraserPointer.get() >= 0) return@detectDragGestures
                            change.consume()
                            val proposed =
                                current.copy(
                                    rotationDegrees =
                                        current.rotationDegrees + amount.x / density.density * 0.5f,
                                )
                            current = proposed
                            onPreview(proposed)
                        }
                    }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(rotateClockwise) {
                                    commitAccessibility(
                                        current.copy(rotationDegrees = current.rotationDegrees + ROTATION_STEP),
                                    )
                                },
                                CustomAccessibilityAction(rotateCounterclockwise) {
                                    commitAccessibility(
                                        current.copy(rotationDegrees = current.rotationDegrees - ROTATION_STEP),
                                    )
                                },
                            )
                    }
                    .testTag("ink-rotate-handle"),
        )
    }
}

@Composable
private fun InkHandle(contentDescription: String, modifier: Modifier) {
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

private const val TOUCH_TARGET = 48f
private const val VISUAL_HANDLE = 16f
private const val HANDLE_REGION = 52f
private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 4f
private const val SCALE_STEP = 1.1f
private const val ROTATION_STEP = 15f
private const val MOVE_STEP = 12f
