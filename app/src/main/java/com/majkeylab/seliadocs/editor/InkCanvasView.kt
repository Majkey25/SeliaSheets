package com.majkeylab.seliadocs.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt

internal enum class EditorTool { TYPE, PEN, PENCIL, HIGHLIGHTER, ERASER, LASSO }

internal enum class EraserMode { SEGMENT, STROKE }

private enum class GestureKind { ERASE, LASSO, MOVE }

private sealed interface PendingEdit {
    class Ink(val id: InProgressStrokeId, val input: InkInputCapture) : PendingEdit {
        var finished = false
        var stroke: Stroke? = null
    }
    class Gesture(val kind: GestureKind, val points: List<CanvasPoint>) : PendingEdit
}

private data class ActiveStroke(val edit: PendingEdit.Ink, val toolType: Int) {
    val id get() = edit.id
}

internal fun isStylusEraser(event: MotionEvent, pointerIndex: Int): Boolean {
    val inputTool = event.getToolType(pointerIndex)
    return inputTool == MotionEvent.TOOL_TYPE_ERASER ||
        (inputTool == MotionEvent.TOOL_TYPE_STYLUS &&
            event.buttonState and
            (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
}

internal class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), InProgressStrokesFinishedListener {
    internal interface Listener {
        fun onStrokeFinished(stroke: Stroke)

        fun onStrokeCanceled(pointerId: Int)

        fun onEraseFinished(points: List<CanvasPoint>) = Unit

        fun onLassoFinished(points: List<CanvasPoint>) = Unit

        fun onMoveSelection(delta: CanvasPoint) = Unit
    }

    private val finishedView = FinishedInkView(context)
    private val inProgressView = InProgressStrokesView(context)
    private val gestureOverlay = GestureOverlayView(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val activeStrokes = mutableMapOf<Int, ActiveStroke>()
    private val pendingEdits = ArrayDeque<PendingEdit>()
    private var pendingCommit: CompletableDeferred<Unit>? = null
    private val gesturePoints = mutableListOf<CanvasPoint>()
    private val identity = Matrix()
    private val liveBounds = Rect()
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportPanX = 0f
    private var viewportPanY = 0f
    private val touchListener = OnTouchListener { _, event -> handleMotionEvent(event) }
    private var gesturePointerId: Int? = null
    private var gestureKind: GestureKind? = null
    private var gestureToolType: Int? = null
    private var pageWidth = 595f
    private var pageHeight = 842f

    var listener: Listener? = null
    // This view owns finger streams for page swipes; taps use the same page transform as stylus selection.
    private val readingTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (!isEnabled || fingerDrawing || tool != EditorTool.LASSO || hasActiveInteraction() ||
                event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || event.flags and MotionEvent.FLAG_CANCELED != 0
            ) return false
            pendingEdits.addLast(PendingEdit.Gesture(GestureKind.LASSO, listOf(pagePoint(event, event.getPointerId(0)))))
            drainPendingEdits()
            return true
        }
    })
    var fingerDrawing: Boolean = false
    var tool: EditorTool = EditorTool.PEN
    var brush = InkCodec.createBrush(BrushKind.RESPONSIVE_PEN, 0xFF202124.toInt(), 4f)
    internal val hoverPreviewVisible: Boolean
        get() = gestureOverlay.hoverVisible

    init {
        addView(finishedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(gestureOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inProgressView.addFinishedStrokesListener(this)
        inProgressView.eagerInit()
        setOnTouchListener(touchListener)
    }

    fun setStrokes(strokes: List<Stroke>, selected: Set<Int> = emptySet()) {
        finishedView.setStrokes(strokes, selected)
    }

    fun finishStrokeSave(stroke: Stroke, succeeded: Boolean) {
        finishedView.finishStrokeSave(stroke, succeeded)
    }

    fun beginStrokeSave(stroke: Stroke) {
        finishedView.beginStrokeSave(stroke)
    }

    fun setPageSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        pageWidth = width.toFloat()
        pageHeight = height.toFloat()
        finishedView.setPageSize(pageWidth, pageHeight)
        gestureOverlay.setPageSize(pageWidth, pageHeight)
    }

    fun setVisibleViewport(width: Int, height: Int, panX: Float, panY: Float) {
        require(width >= 0 && height >= 0 && panX.isFinite() && panY.isFinite())
        if (viewportWidth == width && viewportHeight == height && viewportPanX == panX && viewportPanY == panY) return
        viewportWidth = width
        viewportHeight = height
        viewportPanX = panX
        viewportPanY = panY
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        liveBounds.set(0, 0, measuredWidth, measuredHeight)
        if (viewportWidth > 0 && viewportHeight > 0) {
            val x = ((measuredWidth - viewportWidth) / 2f - viewportPanX).roundToInt()
            val y = ((measuredHeight - viewportHeight) / 2f - viewportPanY).roundToInt()
            liveBounds.set(x, y, x + viewportWidth, y + viewportHeight)
        }
        inProgressView.measure(
            MeasureSpec.makeMeasureSpec(liveBounds.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(liveBounds.height(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        inProgressView.layout(liveBounds.left, liveBounds.top, liveBounds.right, liveBounds.bottom)
        // Zoom moves the viewport over the page without reallocating the live render buffers.
        val inputTransform = Matrix().apply {
            setTranslate(-liveBounds.left.toFloat(), -liveBounds.top.toFloat())
        }
        // Ink queues a render action even when its transform setter receives the same matrix.
        if (inProgressView.motionEventToViewTransform != inputTransform) {
            inProgressView.motionEventToViewTransform = inputTransform
        }
        // Separate rectangles avoid the even-odd CLEAR-path failure observed on Huawei Android 10.
        inProgressView.maskPath = Path().apply {
            val w = liveBounds.width().toFloat()
            val h = liveBounds.height().toFloat()
            val pageLeft = (-liveBounds.left).toFloat().coerceIn(0f, w)
            val pageTop = (-liveBounds.top).toFloat().coerceIn(0f, h)
            val pageRight = (width - liveBounds.left).toFloat().coerceIn(0f, w)
            val pageBottom = (height - liveBounds.top).toFloat().coerceIn(0f, h)
            addRect(0f, 0f, w, pageTop, Path.Direction.CW)
            addRect(0f, pageBottom, w, h, Path.Direction.CW)
            addRect(0f, pageTop, pageLeft, pageBottom, Path.Direction.CW)
            addRect(pageRight, pageTop, w, pageBottom, Path.Direction.CW)
        }
    }

    fun dispatchScreenMotionEvent(event: MotionEvent): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val forwarded = MotionEvent.obtain(event)
        return try {
            forwarded.offsetLocation(
                event.rawX - location[0] - event.x,
                event.rawY - location[1] - event.y,
            )
            dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val pending = pendingEdits.filterIsInstance<PendingEdit.Ink>().associateBy { it.id }
        strokes.forEach { (id, stroke) -> pending[id]?.stroke = stroke }
        finishedView.addStrokes(strokes.filterKeys { it in pending }.values)
        finishedView.invalidate()
        inProgressView.removeFinishedStrokes(strokes.keys)
        drainPendingEdits()
    }

    private fun drainPendingEdits() {
        while (pendingEdits.isNotEmpty()) {
            when (val edit = pendingEdits.first()) {
                is PendingEdit.Ink -> {
                    val stroke = edit.stroke ?: return
                    pendingEdits.removeFirst()
                    listener?.onStrokeFinished(stroke)
                }
                is PendingEdit.Gesture -> {
                    pendingEdits.removeFirst()
                    when (edit.kind) {
                        GestureKind.ERASE -> listener?.onEraseFinished(edit.points)
                        GestureKind.LASSO -> listener?.onLassoFinished(edit.points)
                        GestureKind.MOVE -> {
                            val first = edit.points.firstOrNull()
                            val last = edit.points.lastOrNull()
                            if (first != null && last != null) {
                                listener?.onMoveSelection(CanvasPoint(last.x - first.x, last.y - first.y))
                            }
                        }
                    }
                }
            }
        }
        pendingCommit?.complete(Unit)
        pendingCommit = null
    }

    suspend fun awaitPendingCommits() {
        cancelAll(null)
        if (pendingEdits.isEmpty()) return
        val completion = pendingCommit ?: CompletableDeferred<Unit>().also { pendingCommit = it }
        completion.await()
    }

    fun flushPendingCommits() {
        cancelAll(null)
        pendingEdits.filterIsInstance<PendingEdit.Ink>().forEach { edit ->
            if (edit.finished && edit.stroke == null) edit.stroke = edit.input.toStroke()
        }
        drainPendingEdits()
    }

    override fun onDetachedFromWindow() {
        setOnTouchListener(null)
        flushPendingCommits()
        inProgressView.cancelUnfinishedStrokes()
        inProgressView.clearFinishedStrokesListeners()
        activeStrokes.clear()
        clearGesture()
        gestureOverlay.setHover(null, 0f)
        super.onDetachedFromWindow()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        handleHoverEvent(event)
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            return updateBarrelTool(event).isNotEmpty() || super.onGenericMotionEvent(event)
        }
        return super.onGenericMotionEvent(event)
    }

    private fun updateBarrelTool(event: MotionEvent): Set<Int> {
        val pointerIds = activeStrokes.keys.toList() + listOfNotNull(gesturePointerId)
        val changed = mutableSetOf<Int>()
        pointerIds.forEach { pointerId ->
            val index = event.findPointerIndex(pointerId)
            if (index < 0 || event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return@forEach
            val erase = isStylusEraser(event, index) || tool == EditorTool.ERASER
            val erasing = pointerId == gesturePointerId && gestureKind == GestureKind.ERASE
            if (erase != erasing) {
                finishInteraction(event, pointerId)
                startInteraction(event, index)
                changed += pointerId
            }
        }
        return changed
    }

    private fun handleMotionEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER || event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            readingTapDetector.onTouchEvent(event)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startInteraction(event)
            MotionEvent.ACTION_MOVE -> addToInteraction(event)
            MotionEvent.ACTION_UP -> finishInteraction(event)
            MotionEvent.ACTION_CANCEL -> cancelAll(event)
            MotionEvent.ACTION_POINTER_DOWN -> startAdditionalInteraction(event)
            MotionEvent.ACTION_POINTER_UP -> finishInteraction(event) || hasActiveInteraction()
            else -> hasActiveInteraction()
        }
    }

    private fun handleHoverEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val inputTool = event.getToolType(pointerIndex)
        if (inputTool != MotionEvent.TOOL_TYPE_STYLUS && inputTool != MotionEvent.TOOL_TYPE_ERASER) {
            gestureOverlay.setHover(null, 0f)
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                inProgressView.eagerInit()
                gestureOverlay.setHover(
                    pagePoint(event, event.getPointerId(pointerIndex)),
                    maxOf(brush.size / 2f, 4f),
                )
            }
            MotionEvent.ACTION_HOVER_MOVE ->
                gestureOverlay.setHover(
                    pagePoint(event, event.getPointerId(pointerIndex)),
                    maxOf(brush.size / 2f, 4f),
                )
            MotionEvent.ACTION_HOVER_EXIT -> gestureOverlay.setHover(null, 0f)
        }
        return false
    }

    private fun startAdditionalInteraction(event: MotionEvent): Boolean {
        val inputTool = event.getToolType(event.actionIndex)
        if (inputTool == MotionEvent.TOOL_TYPE_FINGER && event.pointerCount > 1) {
            val hasStylus =
                (0 until event.pointerCount).any { index ->
                    index != event.actionIndex &&
                        (event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
                            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER)
                }
            if (!hasStylus && hasActiveInteraction()) cancelAll(event)
            return true
        }
        if (inputTool == MotionEvent.TOOL_TYPE_STYLUS || inputTool == MotionEvent.TOOL_TYPE_ERASER) {
            cancelFingerStrokes(event)
            cancelFingerGesture()
        }
        if (gesturePointerId != null) return true
        return startInteraction(event) || hasActiveInteraction()
    }

    private fun startInteraction(event: MotionEvent, pointerIndex: Int = event.actionIndex): Boolean {
        val inputTool = event.getToolType(pointerIndex)
        val selectedTool = if (isStylusEraser(event, pointerIndex)) EditorTool.ERASER else tool
        gestureOverlay.setHover(null, 0f)
        if (!canInteract(inputTool, selectedTool)) {
            return inputTool == MotionEvent.TOOL_TYPE_FINGER && selectedTool != EditorTool.TYPE
        }
        requestUnbufferedDispatch(event)
        val pointerId = event.getPointerId(pointerIndex)
        parent?.requestDisallowInterceptTouchEvent(true)
        val gesture =
            when {
                selectedTool == EditorTool.ERASER -> GestureKind.ERASE
                selectedTool == EditorTool.LASSO && finishedView.selectionContains(pagePoint(event, pointerId)) ->
                    GestureKind.MOVE
                selectedTool == EditorTool.LASSO -> GestureKind.LASSO
                else -> null
            }
        if (gesture != null) {
            gesturePointerId = pointerId
            gestureKind = gesture
            gestureToolType = inputTool
            gesturePoints.clear()
            addGesturePoint(event, pointerId, includeHistory = false)
            return true
        }
        val inputToWorld = inputTransform(pageWidth, pageHeight)
        val edit = PendingEdit.Ink(
            inProgressView.startStroke(event, pointerId, brush, inputToWorld, identity),
            InkInputCapture(event, pointerId, brush, inputToWorld, inProgressView),
        )
        pendingEdits.addLast(edit)
        activeStrokes[pointerId] =
            ActiveStroke(
                edit,
                inputTool,
            )
        return true
    }

    private fun addToInteraction(event: MotionEvent): Boolean {
        val switched = updateBarrelTool(event)
        gesturePointerId?.let { return it in switched || addGesturePoint(event, it) }
        if (activeStrokes.isEmpty()) return false
        val prediction = predictor.predict()
        try {
            activeStrokes.forEach { (pointerId, stroke) ->
                if (pointerId !in switched) {
                    stroke.edit.input.add(event, pointerId)
                    inProgressView.addToStroke(event, pointerId, stroke.id, prediction)
                }
            }
        } finally {
            prediction?.recycle()
        }
        return true
    }

    private fun finishInteraction(event: MotionEvent, pointerId: Int = event.getPointerId(event.actionIndex)): Boolean {
        if (pointerId == gesturePointerId) return finishGesture(event, pointerId)
        val stroke = activeStrokes.remove(pointerId) ?: return false
        val canceled = event.flags and MotionEvent.FLAG_CANCELED != 0
        if (canceled) {
            pendingEdits.remove(stroke.edit)
            inProgressView.cancelStroke(stroke.id, event)
            listener?.onStrokeCanceled(pointerId)
        } else {
            stroke.edit.input.add(event, pointerId, includeHistory = false)
            stroke.edit.finished = true
            inProgressView.finishStroke(event, pointerId, stroke.id)
        }
        drainPendingEdits()
        if (activeStrokes.isEmpty()) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun cancelAll(event: MotionEvent?): Boolean {
        gestureOverlay.setHover(null, 0f)
        if (activeStrokes.isEmpty() && gesturePointerId == null) return false
        activeStrokes.forEach { (pointerId, stroke) ->
            pendingEdits.remove(stroke.edit)
            inProgressView.cancelStroke(stroke.id, event)
            listener?.onStrokeCanceled(pointerId)
        }
        activeStrokes.clear()
        gesturePointerId?.let { listener?.onStrokeCanceled(it) }
        clearGesture()
        drainPendingEdits()
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun cancelFingerStrokes(event: MotionEvent) {
        val iterator = activeStrokes.iterator()
        while (iterator.hasNext()) {
            val (pointerId, stroke) = iterator.next()
            if (stroke.toolType != MotionEvent.TOOL_TYPE_FINGER) continue
            pendingEdits.remove(stroke.edit)
            inProgressView.cancelStroke(stroke.id, event)
            listener?.onStrokeCanceled(pointerId)
            iterator.remove()
        }
        drainPendingEdits()
    }

    private fun cancelFingerGesture() {
        if (gestureToolType != MotionEvent.TOOL_TYPE_FINGER) return
        gesturePointerId?.let { listener?.onStrokeCanceled(it) }
        clearGesture()
    }

    private fun finishGesture(event: MotionEvent, pointerId: Int): Boolean {
        addGesturePoint(event, pointerId)
        val points = gesturePoints.toList()
        if (event.flags and MotionEvent.FLAG_CANCELED != 0) {
            listener?.onStrokeCanceled(pointerId)
        } else {
            gestureKind?.let { pendingEdits.addLast(PendingEdit.Gesture(it, points)) }
        }
        clearGesture()
        drainPendingEdits()
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun clearGesture() {
        gesturePointerId = null
        gestureKind = null
        gestureToolType = null
        gesturePoints.clear()
        gestureOverlay.setPoints(emptyList())
    }

    private fun addGesturePoint(event: MotionEvent, pointerId: Int, includeHistory: Boolean = true): Boolean {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return false
        repeat(if (includeHistory) event.historySize else 0) { historyIndex ->
            gesturePoints +=
                CanvasPoint(
                    viewportCoordinateToPage(
                        event.getHistoricalX(index, historyIndex),
                        width.coerceAtLeast(1).toFloat(),
                        pageWidth,
                    ),
                    viewportCoordinateToPage(
                        event.getHistoricalY(index, historyIndex),
                        height.coerceAtLeast(1).toFloat(),
                        pageHeight,
                    ),
                )
        }
        gesturePoints +=
            pagePoint(event, pointerId)
        if (gestureKind == GestureKind.LASSO) gestureOverlay.setPoints(gesturePoints)
        return true
    }

    private fun pagePoint(event: MotionEvent, pointerId: Int): CanvasPoint {
        val index = event.findPointerIndex(pointerId)
        require(index >= 0)
        return CanvasPoint(
            viewportCoordinateToPage(
                event.getX(index),
                width.coerceAtLeast(1).toFloat(),
                pageWidth,
            ),
            viewportCoordinateToPage(
                event.getY(index),
                height.coerceAtLeast(1).toFloat(),
                pageHeight,
            ),
        )
    }

    private fun inputTransform(targetWidth: Float, targetHeight: Float): Matrix {
        val viewWidth = width.coerceAtLeast(1).toFloat()
        val viewHeight = height.coerceAtLeast(1).toFloat()
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    targetWidth / viewWidth,
                    0f,
                    0f,
                    0f,
                    targetHeight / viewHeight,
                    0f,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    }

    private fun canInteract(inputTool: Int, selectedTool: EditorTool): Boolean =
        selectedTool != EditorTool.TYPE &&
            (inputTool == MotionEvent.TOOL_TYPE_STYLUS ||
                inputTool == MotionEvent.TOOL_TYPE_ERASER ||
                (inputTool == MotionEvent.TOOL_TYPE_FINGER && fingerDrawing))

    private fun hasActiveInteraction(): Boolean = activeStrokes.isNotEmpty() || gesturePointerId != null
}

private class GestureOverlayView(context: Context) : View(context) {
    private val path = Path()
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(49, 86, 217)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
    private val hoverPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 32, 33, 36)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
    private var points: List<CanvasPoint> = emptyList()
    private var hoverPoint: CanvasPoint? = null
    private var hoverRadius = 0f
    private var pageWidth = 595f
    private var pageHeight = 842f
    val hoverVisible: Boolean
        get() = hoverPoint != null

    fun setPageSize(width: Float, height: Float) {
        pageWidth = width
        pageHeight = height
    }

    fun setPoints(values: List<CanvasPoint>) {
        points = values.toList()
        invalidate()
    }

    fun setHover(point: CanvasPoint?, radius: Float) {
        hoverPoint = point
        hoverRadius = radius.coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size >= 2) {
            path.rewind()
            path.moveTo(points.first().x * width / pageWidth, points.first().y * height / pageHeight)
            for (index in 1 until points.size) {
                val point = points[index]
                path.lineTo(point.x * width / pageWidth, point.y * height / pageHeight)
            }
            canvas.drawPath(path, paint)
        }
        hoverPoint?.let { point ->
            canvas.drawCircle(
                point.x * width / pageWidth,
                point.y * height / pageHeight,
                hoverRadius * minOf(width / pageWidth, height / pageHeight),
                hoverPaint,
            )
        }
    }
}

private class FinishedInkView(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
    private val pendingSaves = mutableSetOf<Stroke>()
    private var suppliedStrokes: List<Stroke> = emptyList()
    private val renderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
    private val selectionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(49, 86, 217)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
    private var selected: Set<Int> = emptySet()
    private var pageWidth = 595f
    private var pageHeight = 842f

    fun setPageSize(width: Float, height: Float) {
        pageWidth = width
        pageHeight = height
        invalidate()
    }

    fun setStrokes(values: List<Stroke>, selected: Set<Int>) {
        suppliedStrokes = values.toList()
        // The database may contain only part of a handed-off group while its saves are queued.
        if (pendingSaves.isEmpty()) applySuppliedStrokes()
        this.selected = selected
        invalidate()
    }

    fun finishStrokeSave(stroke: Stroke, succeeded: Boolean) {
        if (!pendingSaves.remove(stroke)) return
        if (!succeeded) strokes.removeAll { it === stroke }
        if (pendingSaves.isEmpty()) applySuppliedStrokes()
        invalidate()
    }

    private fun applySuppliedStrokes() {
        strokes.clear()
        strokes.addAll(suppliedStrokes)
    }

    fun beginStrokeSave(stroke: Stroke) {
        if (strokes.any { it === stroke }) pendingSaves.add(stroke)
    }

    fun addStrokes(values: Collection<Stroke>) {
        strokes.addAll(values)
    }

    fun selectionContains(point: CanvasPoint): Boolean =
        selected.any { index ->
            strokes.getOrNull(index)?.let(::strokeBounds)?.contains(point, padding = 12f) == true
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.drawWithStrokes(canvas) { scope ->
            canvas.save()
            canvas.scale(width / pageWidth, height / pageHeight)
            strokes.forEach(scope::drawStroke)
            selected.forEach { index -> strokes.getOrNull(index)?.let { drawSelection(canvas, it) } }
            canvas.restore()
        }
    }

    private fun drawSelection(canvas: Canvas, stroke: Stroke) {
        val bounds = strokeBounds(stroke) ?: return
        canvas.drawRect(
            bounds.left - 6f,
            bounds.top - 6f,
            bounds.right + 6f,
            bounds.bottom + 6f,
            selectionPaint,
        )
    }
}

private data class StrokeBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(point: CanvasPoint, padding: Float): Boolean =
        point.x in (left - padding)..(right + padding) &&
            point.y in (top - padding)..(bottom + padding)
}

private fun strokeBounds(stroke: Stroke): StrokeBounds? {
    if (stroke.inputs.size == 0) return null
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    repeat(stroke.inputs.size) { index ->
        val input = stroke.inputs[index]
        left = minOf(left, input.x)
        top = minOf(top, input.y)
        right = maxOf(right, input.x)
        bottom = maxOf(bottom, input.y)
    }
    return StrokeBounds(left, top, right, bottom)
}
