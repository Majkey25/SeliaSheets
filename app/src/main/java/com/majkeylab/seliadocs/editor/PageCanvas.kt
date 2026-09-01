package com.majkeylab.seliadocs.editor

import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PAGE_TEXT_BOTTOM
import com.majkeylab.seliadocs.data.PAGE_TEXT_MARGIN
import com.majkeylab.seliadocs.data.PAGE_TEXT_MAX_LENGTH
import com.majkeylab.seliadocs.data.PAGE_TEXT_TOP
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.StrokeEntity
import com.majkeylab.seliadocs.data.pageTextFits
import com.majkeylab.seliadocs.recognition.ImageOcrRegion
import com.majkeylab.seliadocs.recognition.matchingImageOcrRegions
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class CanvasPageFrame(
    val page: PageEntity?,
    val pageNumber: Int,
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
    val blocks: List<BlockEntity>,
    val ocrSearchHighlight: OcrSearchHighlight?,
)

@Composable
internal fun PageCanvas(
    page: PageEntity?,
    pageNumber: Int,
    pageCount: Int,
    strokes: List<StrokeEntity>,
    elements: List<ElementEntity>,
    blocks: List<BlockEntity>,
    selectedStrokeIds: Set<String>,
    selectedElementId: String?,
    smartShapePreviewId: String? = null,
    ocrSearchHighlight: OcrSearchHighlight? = null,
    fingerDrawing: Boolean,
    tool: EditorTool,
    penWidth: Float,
    highlighterWidth: Float,
    penColorArgb: Int = 0xFF202124.toInt(),
    highlighterColorArgb: Int = 0x66FFD54F,
    pageTransitionEnabled: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onStrokeFinished: (String, Stroke) -> Unit,
    onEraseFinished: (String, List<CanvasPoint>) -> Unit,
    onSelectContent: (String, List<CanvasPoint>) -> Unit,
    onMoveSelection: (String, CanvasPoint) -> Unit,
    onPageTextChanged: (String, String) -> Unit,
    onCommitElementTransform: (ElementTransform) -> Unit,
    onSelectElement: (String) -> Unit = {},
    assetFile: (String) -> File,
    onPageTextDraftChanged: (String, TextFieldValue) -> Boolean = { _, _ -> true },
    initialPageTextDraft: TextFieldValue? = null,
    pageTextInputEnabled: Boolean = true,
    loadPdfPage: suspend (String, Int, Int) -> androidx.compose.ui.graphics.ImageBitmap? = { _, _, _ -> null },
    initialViewport: PageViewport = PageViewport(),
    modifier: Modifier = Modifier,
) {
    val frame = CanvasPageFrame(page, pageNumber, strokes, elements, blocks, ocrSearchHighlight)
    val currentPageId = rememberUpdatedState(page?.id)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = frame,
            transitionSpec = {
                pageTransition(
                    pageTransitionEnabled,
                    pageTransitionDirection(initialState.pageNumber, targetState.pageNumber),
                )
            },
            contentKey = { it.page?.id },
            label = "page",
        ) { target ->
            target.page?.let { targetPage ->
                Paper(
                    targetPage,
                    target.pageNumber,
                    pageCount,
                    { currentPageId.value == targetPage.id },
                    target.strokes,
                    target.elements,
                    target.blocks,
                    selectedStrokeIds,
                    selectedElementId,
                    smartShapePreviewId,
                    target.ocrSearchHighlight,
                    fingerDrawing,
                    tool,
                    penWidth,
                    highlighterWidth,
                    penColorArgb,
                    highlighterColorArgb,
                    onPreviousPage,
                    onNextPage,
                    onStrokeFinished,
                    onEraseFinished,
                    onSelectContent,
                    onMoveSelection,
                    onPageTextChanged,
                    onCommitElementTransform,
                    onSelectElement,
                    assetFile,
                    onPageTextDraftChanged,
                    initialPageTextDraft,
                    pageTextInputEnabled,
                    loadPdfPage,
                    initialViewport,
                )
            }
        }
    }
}

internal fun pageTransitionDirection(fromPage: Int, toPage: Int): Int = toPage.compareTo(fromPage)

private fun pageTransition(enabled: Boolean, direction: Int): ContentTransform =
    if (enabled) {
        (slideInHorizontally(tween(220)) { direction * it / 5 } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(220)) { -direction * it / 5 } + fadeOut(tween(140)))
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }

@Composable
private fun Paper(
    page: PageEntity,
    pageNumber: Int,
    pageCount: Int,
    isCurrentPage: () -> Boolean,
    strokes: List<StrokeEntity>,
    elements: List<ElementEntity>,
    blocks: List<BlockEntity>,
    selectedStrokeIds: Set<String>,
    selectedElementId: String?,
    smartShapePreviewId: String?,
    ocrSearchHighlight: OcrSearchHighlight?,
    fingerDrawing: Boolean,
    tool: EditorTool,
    penWidth: Float,
    highlighterWidth: Float,
    penColorArgb: Int,
    highlighterColorArgb: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onStrokeFinished: (String, Stroke) -> Unit,
    onEraseFinished: (String, List<CanvasPoint>) -> Unit,
    onSelectContent: (String, List<CanvasPoint>) -> Unit,
    onMoveSelection: (String, CanvasPoint) -> Unit,
    onPageTextChanged: (String, String) -> Unit,
    onCommitElementTransform: (ElementTransform) -> Unit,
    onSelectElement: (String) -> Unit,
    assetFile: (String) -> File,
    onPageTextDraftChanged: (String, TextFieldValue) -> Boolean,
    initialPageTextDraft: TextFieldValue?,
    pageTextInputEnabled: Boolean,
    loadPdfPage: suspend (String, Int, Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    initialViewport: PageViewport,
) {
    val ratio = page.widthPoints.toFloat() / page.heightPoints
    val decodedStrokes = remember(strokes) { strokes.map(StrokeEntity::toInkStroke) }
    val selected =
        remember(strokes, selectedStrokeIds) {
            strokes.mapIndexedNotNull { index, stroke -> index.takeIf { stroke.id in selectedStrokeIds } }.toSet()
        }
    val activeBrush =
        remember(tool, penWidth, highlighterWidth, penColorArgb, highlighterColorArgb) {
            brushFor(tool, penWidth, highlighterWidth, penColorArgb, highlighterColorArgb)
        }
    val selectedElement = elements.firstOrNull { it.id == selectedElementId }
    var viewportZoom by remember(page.id) { mutableFloatStateOf(initialViewport.zoom) }
    var viewportPanX by remember(page.id) { mutableFloatStateOf(initialViewport.panX) }
    var viewportPanY by remember(page.id) { mutableFloatStateOf(initialViewport.panY) }
    var previewTransform by
        remember(
            selectedElement?.id,
            selectedElement?.x,
            selectedElement?.y,
            selectedElement?.width,
            selectedElement?.height,
            selectedElement?.rotation,
        ) {
            mutableStateOf<ElementTransform?>(null)
        }
    var overlayGestureOwned by remember(page.id, selectedElement?.id) { mutableStateOf(false) }
    val overlayGestureOwnedState = rememberUpdatedState(overlayGestureOwned)
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(24.dp).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val availableRatio = maxWidth / maxHeight
        val paperWidth: androidx.compose.ui.unit.Dp
        val paperHeight: androidx.compose.ui.unit.Dp
        if (availableRatio > ratio) {
            paperHeight = maxHeight * 0.94f
            paperWidth = paperHeight * ratio
        } else {
            paperWidth = maxWidth * 0.94f
            paperHeight = paperWidth / ratio
        }
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val paperWidthPx = with(density) { paperWidth.toPx() }
        val paperHeightPx = with(density) { paperHeight.toPx() }
        val zoomDescription = stringResource(R.string.zoom_level, (viewportZoom * 100).roundToInt())
        val hostView = LocalView.current
        val nativeReleased = remember(page.id) { AtomicBoolean(false) }
        val inkCanvas = remember(page.id) { AtomicReference<InkCanvasView>() }
        val eraserPointerId = remember(page.id) { AtomicInteger(-1) }
        val eraserEpoch = remember(page.id) { AtomicInteger() }
        val selectionBounds = remember(page.id) { AtomicReference<Rect?>(null) }
        val viewportModifier =
            Modifier
                .fillMaxSize()
                .testTag("page-viewport")
                .semantics { stateDescription = zoomDescription }
                .pointerInput(page.id, fingerDrawing, paperWidthPx, paperHeightPx) {
                    if (!fingerDrawing) {
                        detectTapGestures(
                            onDoubleTap = {
                                val fitted = fitPageWidth(viewportWidthPx, paperWidthPx)
                                viewportZoom = fitted.zoom
                                viewportPanX = 0f
                                viewportPanY = 0f
                            },
                        )
                    }
                }
                .pointerInput(page.id, fingerDrawing, tool, paperWidthPx, paperHeightPx) {
                    awaitEachGesture {
                        val arbiter = PageGestureArbiter()
                        var startCentroid: Offset? = null
                        var lastCentroid: Offset? = null
                        var initialTouchSpan: Float? = null
                        var pinchOwned = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val hasStylus =
                                event.changes.any { change ->
                                    change.pressed &&
                                        (change.type == PointerType.Stylus || change.type == PointerType.Eraser)
                                }
                            val touchChanges = event.changes.filter { it.type == PointerType.Touch }
                            val touches = touchChanges.filter { it.pressed }
                            val requiredTouches = if (fingerDrawing) 2 else 1
                            val endingTouches = touchChanges.filter { it.pressed || it.previousPressed }
                            val newPointerDownAfterOwnership =
                                startCentroid != null && touchChanges.any { it.pressed && !it.previousPressed }
                            if (startCentroid == null && touches.size == requiredTouches) {
                                val centroid =
                                    touches.fold(Offset.Zero) { total, change -> total + change.position } /
                                        touches.size.toFloat()
                                startCentroid = centroid
                                lastCentroid = centroid
                            } else if (startCentroid != null && endingTouches.size == requiredTouches) {
                                lastCentroid =
                                    endingTouches.fold(Offset.Zero) { total, change -> total + change.position } /
                                        endingTouches.size.toFloat()
                            }
                            if (endingTouches.size == 2) {
                                val touchSpan = (endingTouches[0].position - endingTouches[1].position).getDistance()
                                val initialSpan = initialTouchSpan
                                if (initialSpan == null) {
                                    initialTouchSpan = touchSpan
                                } else if (kotlin.math.abs(touchSpan - initialSpan) > viewConfiguration.touchSlop) {
                                    pinchOwned = true
                                }
                            }
                            val overlayOwned = overlayGestureOwnedState.value && touches.size < 2
                            val gestureOwned = overlayOwned || (tool == EditorTool.TYPE && touches.isNotEmpty())
                            if (
                                canUpdatePageViewport(
                                    hasStylus = hasStylus,
                                    overlayOwned = overlayOwned,
                                    touchCount = touches.size,
                                    fingerDrawing = fingerDrawing,
                                )
                            ) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (
                                    (pinchOwned || viewportZoom > 1f) &&
                                        (zoomChange != 1f || panChange.x != 0f || panChange.y != 0f)
                                ) {
                                    val focus = event.calculateCentroid(useCurrent = true)
                                    val updated =
                                        updatePageViewport(
                                            current = PageViewport(viewportZoom, viewportPanX, viewportPanY),
                                            zoomChange = zoomChange,
                                            gesturePanX = panChange.x,
                                            gesturePanY = panChange.y,
                                            focusFromCenterX = focus.x - viewportWidthPx / 2f,
                                            focusFromCenterY = focus.y - viewportHeightPx / 2f,
                                            viewportWidth = viewportWidthPx,
                                            viewportHeight = viewportHeightPx,
                                            pageWidth = paperWidthPx,
                                            pageHeight = paperHeightPx,
                                        )
                                    viewportZoom = updated.zoom
                                    viewportPanX = updated.panX
                                    viewportPanY = updated.panY
                                    event.changes.filter { it.type == PointerType.Touch }.forEach { it.consume() }
                                }
                            }
                            val start = startCentroid
                            val end = lastCentroid
                            val finished = event.changes.none { it.pressed }
                            if (finished) awaitPointerEvent(PointerEventPass.Final)
                            val turn =
                                arbiter.onGesture(
                                    pageWidth = paperWidthPx,
                                    horizontal = if (start != null && end != null) end.x - start.x else 0f,
                                    vertical = if (start != null && end != null) end.y - start.y else 0f,
                                    pointerCount = touches.size,
                                    fingerDrawing = fingerDrawing,
                                    zoomed = viewportZoom > 1f,
                                    scaleChanged = pinchOwned,
                                    stylusOwned = hasStylus,
                                    gestureOwned = gestureOwned,
                                    newPointerDownAfterOwnership = newPointerDownAfterOwnership,
                                    canceled = finished && !nativeReleased.get(),
                                    finished = finished,
                                    currentPage = pageNumber - 1,
                                    pageCount = pageCount,
                                )
                            if (isCurrentPage()) {
                                when (turn) {
                                    PageTurn.NONE -> Unit
                                    PageTurn.PREVIOUS -> onPreviousPage()
                                    PageTurn.NEXT -> onNextPage()
                                }
                            }
                            if (finished) break
                        }
                    }
                }
                .motionEventSpy { event ->
                    val pointerIndex = event.actionIndex
                    val eraserAction = isStylusEraser(event, pointerIndex)
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && !eraserAction) {
                        eraserEpoch.incrementAndGet()
                        eraserPointerId.set(-1)
                    }
                    if (
                        (event.actionMasked == MotionEvent.ACTION_DOWN ||
                            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
                        eraserAction
                    ) {
                        val location = IntArray(2)
                        hostView.getLocationOnScreen(location)
                        val point =
                            Offset(
                                event.getRawX(pointerIndex) - location[0],
                                event.getRawY(pointerIndex) - location[1],
                        )
                        if (selectionBounds.get()?.contains(point) == true) {
                            eraserEpoch.incrementAndGet()
                            eraserPointerId.set(event.getPointerId(pointerIndex))
                        }
                    }
                    val activeEraserPointer = eraserPointerId.get()
                    val activeEraserEpoch = eraserEpoch.get()
                    if (activeEraserPointer >= 0) {
                        inkCanvas.get()?.dispatchScreenMotionEvent(event)
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> nativeReleased.set(false)
                        MotionEvent.ACTION_UP -> nativeReleased.set(true)
                    }
                    if (
                        activeEraserPointer >= 0 &&
                        (event.actionMasked == MotionEvent.ACTION_CANCEL ||
                            ((event.actionMasked == MotionEvent.ACTION_UP ||
                                event.actionMasked == MotionEvent.ACTION_POINTER_UP) &&
                                event.getPointerId(pointerIndex) == activeEraserPointer))
                    ) {
                        hostView.post {
                            if (eraserEpoch.get() == activeEraserEpoch) {
                                eraserPointerId.compareAndSet(activeEraserPointer, -1)
                            }
                        }
                    }
                }
        Box(viewportModifier, contentAlignment = Alignment.Center) {
            Surface(
                color = Color(0xFFFFFEFA),
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 4.dp,
                modifier =
                    Modifier
                        .testTag("page-paper")
                        .requiredWidth(paperWidth * viewportZoom)
                        .requiredHeight(paperHeight * viewportZoom)
                        .offset {
                            IntOffset(viewportPanX.roundToInt(), viewportPanY.roundToInt())
                        },
            ) {
                BoxWithConstraints {
                val scaleX = maxWidth.value / page.widthPoints
                val scaleY = maxHeight.value / page.heightPoints
                PaperPattern(page.paper, Modifier.fillMaxSize())
                PdfPageLayer(page, loadPdfPage)
                PageTextLayer(
                    page = page,
                    blocks = blocks,
                    active = tool == EditorTool.TYPE,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    onTextChanged = onPageTextChanged,
                    onDraftChanged = onPageTextDraftChanged,
                    initialDraft = initialPageTextDraft,
                    inputEnabled = pageTextInputEnabled,
                )
                ElementLayer(
                    page,
                    elements,
                    selectedElementId,
                    smartShapePreviewId,
                    ocrSearchHighlight,
                    previewTransform,
                    assetFile,
                    onSelectElement,
                )
                AndroidView(
                    factory = { context -> InkCanvasView(context).also(inkCanvas::set) },
                    update = { view ->
                        view.setPageSize(page.widthPoints, page.heightPoints)
                        view.fingerDrawing = fingerDrawing
                        view.tool = tool
                        view.brush = activeBrush
                        view.listener =
                            object : InkCanvasView.Listener {
                                override fun onStrokeFinished(stroke: Stroke) {
                                    onStrokeFinished(page.id, stroke)
                                }

                                override fun onStrokeCanceled(pointerId: Int) = Unit

                                override fun onEraseFinished(points: List<CanvasPoint>) {
                                    onEraseFinished(page.id, points)
                                }

                                override fun onLassoFinished(points: List<CanvasPoint>) {
                                    onSelectContent(page.id, points)
                                }

                                override fun onMoveSelection(delta: CanvasPoint) {
                                    onMoveSelection(page.id, delta)
                                }
                            }
                        view.setStrokes(decodedStrokes, selected)
                    },
                    modifier = Modifier.fillMaxSize().zIndex(2f),
                )
                if (tool == EditorTool.LASSO && selectedElement != null) {
                    ElementSelectionOverlay(
                        page = page,
                        element = selectedElement,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        onPreview = { previewTransform = it },
                        onCommit = onCommitElementTransform,
                        onGestureOwnershipChange = { overlayGestureOwned = it },
                        eraserPointerId = eraserPointerId,
                        onBoundsChanged = selectionBounds::set,
                    )
                }
                Text(
                    text = stringResource(R.string.page_number, pageNumber),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF7A7770),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).zIndex(5f),
                )
            }
        }
    }
}

}

@Composable
private fun PdfPageLayer(
    page: PageEntity,
    loadPdfPage: suspend (String, Int, Int) -> androidx.compose.ui.graphics.ImageBitmap?,
) {
    if (page.pdfSourceId == null || page.pdfPageIndex == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceIn(1, 4_096)
        val height = with(density) { maxHeight.roundToPx() }.coerceIn(1, 4_096)
        val bitmap by
            produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, page.id, width, height) {
                value = runCatching { loadPdfPage(page.id, width, height) }.getOrNull()
            }
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.imported_pdf_page, page.pageIndex + 1),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PageTextLayer(
    page: PageEntity,
    blocks: List<BlockEntity>,
    active: Boolean,
    scaleX: Float,
    scaleY: Float,
    onTextChanged: (String, String) -> Unit,
    onDraftChanged: (String, TextFieldValue) -> Boolean,
    initialDraft: TextFieldValue?,
    inputEnabled: Boolean,
) {
    val storedText = blocks.singleOrNull()?.text.orEmpty()
    var draft by remember(page.id) { mutableStateOf(initialDraft ?: TextFieldValue(storedText)) }
    var lastObservedStoredText by remember(page.id) { mutableStateOf(storedText) }
    var pageFull by remember(page.id) { mutableStateOf(false) }
    val focusRequester = remember(page.id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestText by rememberUpdatedState(draft.text)
    val latestStoredText by rememberUpdatedState(storedText)
    val latestCallback by rememberUpdatedState(onTextChanged)
    val description = stringResource(R.string.page_text_description, page.pageIndex + 1)
    val modifier =
        Modifier
            .fillMaxSize()
            .zIndex(if (active) 3f else 0f)
            .padding(
                start = (PAGE_TEXT_MARGIN * scaleX).dp,
                top = (PAGE_TEXT_TOP * scaleY).dp,
                end = (PAGE_TEXT_MARGIN * scaleX).dp,
                bottom = (PAGE_TEXT_BOTTOM * scaleY).dp,
            )
            .testTag("page-text")
            .semantics { contentDescription = description }
    val textStyle =
        TextStyle(
            color = Color(0xFF202124),
            fontSize = (18f * scaleY).coerceAtLeast(10f).sp,
            lineHeight = (26f * scaleY).coerceAtLeast(14f).sp,
        )

    LaunchedEffect(storedText) {
        val previousStoredText = lastObservedStoredText
        lastObservedStoredText = storedText
        if (storedText == draft.text) return@LaunchedEffect
        if (draft.text == previousStoredText) {
            val updatedDraft =
                TextFieldValue(
                    text = storedText,
                    selection =
                        TextRange(
                            draft.selection.start.coerceAtMost(storedText.length),
                            draft.selection.end.coerceAtMost(storedText.length),
                        ),
                )
            if (onDraftChanged(page.id, updatedDraft)) draft = updatedDraft
        }
    }
    LaunchedEffect(draft.text) {
        if (draft.text == storedText) return@LaunchedEffect
        delay(450)
        val completed =
            if (draft.composition == null && draft.selection.collapsed && draft.selection.end == draft.text.length) {
                completeTrailingMath(draft.text)
            } else {
                draft.text
            }
        if (completed != draft.text) {
            val updated = TextFieldValue(completed, selection = TextRange(completed.length))
            if (onDraftChanged(page.id, updated)) draft = updated
            return@LaunchedEffect
        }
        onTextChanged(page.id, draft.text)
    }
    LaunchedEffect(active, page.id) {
        if (active) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            if (draft.text != storedText) {
                onTextChanged(page.id, draft.text)
            }
            focusManager.clearFocus()
        }
    }
    DisposableEffect(page.id, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && latestText != latestStoredText) {
                    latestCallback(page.id, latestText)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (latestText != latestStoredText) {
                latestCallback(page.id, latestText)
            }
        }
    }

    if (active) {
        BasicTextField(
            value = draft,
            onValueChange = { value ->
                val fits =
                    value.text.length <= PAGE_TEXT_MAX_LENGTH &&
                        pageTextFits(value.text, page.widthPoints, page.heightPoints)
                pageFull = !fits
                if (fits && onDraftChanged(page.id, value)) {
                    draft = value
                }
            },
            enabled = inputEnabled,
            textStyle = textStyle,
            cursorBrush = SolidColor(Color(0xFF3156D9)),
            modifier = modifier.focusRequester(focusRequester),
            decorationBox = { field ->
                Box {
                    if (draft.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.page_text_hint),
                            style = textStyle,
                            color = Color(0xFF8A8780),
                        )
                    }
                    if (pageFull) {
                        Text(
                            text = stringResource(R.string.page_text_full),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                    field()
                }
            },
        )
    } else if (draft.text.isNotEmpty()) {
        Text(text = draft.text, style = textStyle, modifier = modifier)
    }
}

@Composable
private fun ElementLayer(
    page: PageEntity,
    elements: List<ElementEntity>,
    selectedElementId: String?,
    smartShapePreviewId: String?,
    ocrSearchHighlight: OcrSearchHighlight?,
    previewTransform: ElementTransform?,
    assetFile: (String) -> File,
    onSelectElement: (String) -> Unit,
) {
    val selectElement = stringResource(R.string.select_element)
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(1f)) {
        val scaleX = maxWidth.value / page.widthPoints
        val scaleY = maxHeight.value / page.heightPoints
        elements.forEach { element ->
            key(element.id) {
                val transform =
                    previewTransform?.takeIf { element.id == selectedElementId }
                        ?: element.transform()
                val modifier =
                    Modifier
                        .offset((transform.x * scaleX).dp, (transform.y * scaleY).dp)
                        .width((transform.width * scaleX).dp)
                        .height((transform.height * scaleY).dp)
                        .testTag("element-${element.id}")
                        .semantics {
                            customActions =
                                listOf(
                                    androidx.compose.ui.semantics.CustomAccessibilityAction(
                                        selectElement,
                                    ) {
                                        onSelectElement(element.id)
                                        true
                                    },
                                )
                        }
                        .then(
                            if (element.id == smartShapePreviewId) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(4.dp),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .rotate(transform.rotation)
                when (runCatching { ElementKind.valueOf(element.kind) }.getOrNull()) {
                    ElementKind.TEXT,
                    ElementKind.MATH,
                    -> Surface(
                        color = Color(0xE6FFFEFA),
                        shape = RoundedCornerShape(4.dp),
                        modifier = modifier,
                    ) {
                        Text(
                            text = element.resultText ?: element.text.orEmpty(),
                            color = Color(0xFF202124),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    ElementKind.IMAGE -> element.assetId?.let { id ->
                        val highlightedRegions =
                            remember(element.ocrRegions, ocrSearchHighlight) {
                                if (ocrSearchHighlight?.elementId == element.id) {
                                    matchingImageOcrRegions(element.ocrRegions, ocrSearchHighlight.query)
                                } else {
                                    emptyList()
                                }
                            }
                        StoredImage(assetFile(id), modifier, highlightedRegions, element.id)
                    }
                    ElementKind.SHAPE -> CleanShape(element, modifier)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun CleanShape(element: ElementEntity, modifier: Modifier) {
    val kind = element.shapeKind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() } ?: return
    Canvas(modifier) {
        val color = Color(0xFF202124)
        val stroke = DrawStroke(width = 3.dp.toPx())
        val inset = 3.dp.toPx()
        when (kind) {
            ShapeKind.LINE,
            ShapeKind.ARROW,
            -> {
                val start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f)
                val end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f)
                drawLine(color, start, end, strokeWidth = stroke.width)
                if (kind == ShapeKind.ARROW) {
                    val head = minOf(18.dp.toPx(), size.width / 3f)
                    drawLine(
                        color,
                        end,
                        androidx.compose.ui.geometry.Offset(end.x - head, end.y - head * 0.55f),
                        strokeWidth = stroke.width,
                    )
                    drawLine(
                        color,
                        end,
                        androidx.compose.ui.geometry.Offset(end.x - head, end.y + head * 0.55f),
                        strokeWidth = stroke.width,
                    )
                }
            }
            ShapeKind.ELLIPSE -> drawOval(color, style = stroke)
            ShapeKind.RECTANGLE ->
                drawRect(
                    color,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    style = stroke,
                )
            ShapeKind.TRIANGLE -> {
                val path =
                    Path().apply {
                        moveTo(size.width / 2f, inset)
                        lineTo(size.width - inset, size.height - inset)
                        lineTo(inset, size.height - inset)
                        close()
                    }
                drawPath(path, color, style = stroke)
            }
        }
    }
}

@Composable
private fun StoredImage(
    file: File,
    modifier: Modifier,
    highlightedRegions: List<ImageOcrRegion>,
    elementId: String,
) {
    val bitmap by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path, file.lastModified()) {
            value = withContext(Dispatchers.IO) { decodePreview(file)?.asImageBitmap() }
        }
    bitmap?.let { image ->
        Box(modifier.clip(RoundedCornerShape(4.dp))) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.inserted_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (highlightedRegions.isNotEmpty()) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize().testTag("ocr-highlight-$elementId")) {
                    highlightedRegions.forEach { region ->
                        val rect =
                            fittedImageRegionRect(
                                region,
                                size.width,
                                size.height,
                                image.width.toFloat(),
                                image.height.toFloat(),
                            )
                        drawRect(
                            color = color.copy(alpha = 0.24f),
                            topLeft = rect.topLeft,
                            size = rect.size,
                        )
                        drawRect(
                            color = color,
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = DrawStroke(2.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

internal fun fittedImageRegionRect(
    region: ImageOcrRegion,
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Rect {
    require(containerWidth > 0f && containerHeight > 0f && imageWidth > 0f && imageHeight > 0f)
    val scale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val fittedWidth = imageWidth * scale
    val fittedHeight = imageHeight * scale
    val offsetX = (containerWidth - fittedWidth) / 2f
    val offsetY = (containerHeight - fittedHeight) / 2f
    return Rect(
        left = offsetX + region.left * fittedWidth,
        top = offsetY + region.top * fittedHeight,
        right = offsetX + region.right * fittedWidth,
        bottom = offsetY + region.bottom * fittedHeight,
    )
}

private fun decodePreview(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 2_048 || bounds.outHeight / sample > 2_048) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun brushFor(
    tool: EditorTool,
    penWidth: Float,
    highlighterWidth: Float,
    penColorArgb: Int,
    highlighterColorArgb: Int,
) =
    when (tool) {
        EditorTool.TYPE,
        EditorTool.PEN -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, penColorArgb, penWidth)
        EditorTool.PENCIL ->
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, penColorArgb, penWidth * 0.55f)
        EditorTool.HIGHLIGHTER ->
            InkCodec.createBrush(BrushKind.HIGHLIGHTER, highlighterColorArgb, highlighterWidth)
        EditorTool.ERASER,
        EditorTool.LASSO,
        -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, penColorArgb, penWidth)
    }

@Composable
private fun PaperPattern(value: String, modifier: Modifier) {
    val template = runCatching { PaperTemplate.valueOf(value) }.getOrDefault(PaperTemplate.BLANK)
    Canvas(modifier) {
        val lineColor = Color(0xFFD5D7DC)
        val spacing = 28.dp.toPx()
        when (template) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = spacing
                while (y < size.height) {
                    drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y))
                    y += spacing
                }
            }
            PaperTemplate.GRID -> {
                var x = spacing
                while (x < size.width) {
                    drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height))
                    x += spacing
                }
                var y = spacing
                while (y < size.height) {
                    drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y))
                    y += spacing
                }
            }
            PaperTemplate.DOT -> {
                var y = spacing
                while (y < size.height) {
                    var x = spacing
                    while (x < size.width) {
                        drawCircle(lineColor, radius = 1.3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }
}
