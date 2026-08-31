package com.majkeylab.seliadocs.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

internal enum class EditorTool { TYPE, PEN, PENCIL, HIGHLIGHTER, ERASER, LASSO }

internal enum class EraserMode { SEGMENT, STROKE }

private enum class GestureKind { ERASE, LASSO, MOVE }

private data class ActiveStroke(val id: InProgressStrokeId, val toolType: Int)

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
    private val gesturePoints = mutableListOf<CanvasPoint>()
    private val identity = Matrix()
    private val touchListener = OnTouchListener { _, event -> handleMotionEvent(event) }
    private var gesturePointerId: Int? = null
    private var gestureKind: GestureKind? = null
    private var gestureToolType: Int? = null
    private var pageWidth = 595f
    private var pageHeight = 842f
    private var viewportZoom = 1f

    var listener: Listener? = null
    var fingerDrawing: Boolean = false
    var tool: EditorTool = EditorTool.PEN
    var brush = InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f)

    init {
        addView(finishedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(gestureOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) inProgressView.eagerInit()
        inProgressView.addFinishedStrokesListener(this)
        setOnTouchListener(touchListener)
    }

    fun setStrokes(strokes: List<Stroke>, selected: Set<Int> = emptySet()) {
        finishedView.setStrokes(strokes, selected)
    }

    fun setPageSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        pageWidth = width.toFloat()
        pageHeight = height.toFloat()
        finishedView.setPageSize(pageWidth, pageHeight)
        gestureOverlay.setPageSize(pageWidth, pageHeight)
    }

    fun setViewportTransform(zoom: Float) {
        require(zoom > 0f && zoom.isFinite())
        viewportZoom = zoom
        updateMotionEventToViewTransform()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateMotionEventToViewTransform()
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        finishedView.addStrokes(strokes.values)
        finishedView.invalidate()
        inProgressView.removeFinishedStrokes(strokes.keys)
        strokes.values.forEach { listener?.onStrokeFinished(it) }
    }

    override fun onDetachedFromWindow() {
        setOnTouchListener(null)
        inProgressView.cancelUnfinishedStrokes()
        inProgressView.clearFinishedStrokesListeners()
        activeStrokes.clear()
        clearGesture()
        super.onDetachedFromWindow()
    }

    private fun handleMotionEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_HOVER_ENTER &&
            (event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_ERASER)
        ) {
            inProgressView.eagerInit()
            return false
        }
        predictor.record(event)
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

    private fun startInteraction(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val inputTool = event.getToolType(pointerIndex)
        val selectedTool = if (inputTool == MotionEvent.TOOL_TYPE_ERASER) EditorTool.ERASER else tool
        if (!canInteract(inputTool, selectedTool)) return false
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
            addGesturePoint(event, pointerId)
            return true
        }
        val inputToWorld = inputTransform(pageWidth, pageHeight)
        activeStrokes[pointerId] =
            ActiveStroke(
                inProgressView.startStroke(event, pointerId, brush, inputToWorld, identity),
                inputTool,
            )
        return true
    }

    private fun addToInteraction(event: MotionEvent): Boolean {
        gesturePointerId?.let { return addGesturePoint(event, it) }
        if (activeStrokes.isEmpty()) return false
        val prediction = predictor.predict()
        try {
            activeStrokes.forEach { (pointerId, stroke) ->
                inProgressView.addToStroke(event, pointerId, stroke.id, prediction)
            }
        } finally {
            prediction?.recycle()
        }
        return true
    }

    private fun finishInteraction(event: MotionEvent): Boolean {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerId == gesturePointerId) return finishGesture(event, pointerId)
        val stroke = activeStrokes.remove(pointerId) ?: return false
        val canceled = event.flags and MotionEvent.FLAG_CANCELED != 0
        if (canceled) {
            inProgressView.cancelStroke(stroke.id, event)
            listener?.onStrokeCanceled(pointerId)
        } else {
            inProgressView.finishStroke(event, pointerId, stroke.id)
        }
        if (activeStrokes.isEmpty()) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun cancelAll(event: MotionEvent?): Boolean {
        if (activeStrokes.isEmpty() && gesturePointerId == null) return false
        activeStrokes.forEach { (pointerId, stroke) ->
            inProgressView.cancelStroke(stroke.id, event)
            listener?.onStrokeCanceled(pointerId)
        }
        activeStrokes.clear()
        gesturePointerId?.let { listener?.onStrokeCanceled(it) }
        clearGesture()
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun cancelFingerStrokes(event: MotionEvent) {
        val iterator = activeStrokes.iterator()
        while (iterator.hasNext()) {
            val (pointerId, stroke) = iterator.next()
            if (stroke.toolType != MotionEvent.TOOL_TYPE_FINGER) continue
            inProgressView.cancelStroke(stroke.id, event)
            listener?.onStrokeCanceled(pointerId)
            iterator.remove()
        }
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
            when (gestureKind) {
                GestureKind.ERASE -> listener?.onEraseFinished(points)
                GestureKind.LASSO -> listener?.onLassoFinished(points)
                GestureKind.MOVE -> {
                    val first = points.firstOrNull()
                    val last = points.lastOrNull()
                    if (first != null && last != null) {
                        listener?.onMoveSelection(CanvasPoint(last.x - first.x, last.y - first.y))
                    }
                }
                else -> Unit
            }
        }
        clearGesture()
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

    private fun addGesturePoint(event: MotionEvent, pointerId: Int): Boolean {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return false
        repeat(event.historySize) { historyIndex ->
            gesturePoints +=
                CanvasPoint(
                    viewportCoordinateToPage(
                        event.getHistoricalX(index, historyIndex),
                        width.coerceAtLeast(1).toFloat(),
                        pageWidth,
                        viewportZoom,
                    ),
                    viewportCoordinateToPage(
                        event.getHistoricalY(index, historyIndex),
                        height.coerceAtLeast(1).toFloat(),
                        pageHeight,
                        viewportZoom,
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
                viewportZoom,
            ),
            viewportCoordinateToPage(
                event.getY(index),
                height.coerceAtLeast(1).toFloat(),
                pageHeight,
                viewportZoom,
            ),
        )
    }

    private fun updateMotionEventToViewTransform() {
        // Compose offsets the platform event to the transformed layer origin; only scale remains.
        inProgressView.motionEventToViewTransform =
            inputTransform(
                width.coerceAtLeast(1).toFloat(),
                height.coerceAtLeast(1).toFloat(),
            )
    }

    private fun inputTransform(targetWidth: Float, targetHeight: Float): Matrix {
        val viewWidth = width.coerceAtLeast(1).toFloat()
        val viewHeight = height.coerceAtLeast(1).toFloat()
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    targetWidth / viewWidth / viewportZoom,
                    0f,
                    0f,
                    0f,
                    targetHeight / viewHeight / viewportZoom,
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
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(49, 86, 217)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
    private var points: List<CanvasPoint> = emptyList()
    private var pageWidth = 595f
    private var pageHeight = 842f

    fun setPageSize(width: Float, height: Float) {
        pageWidth = width
        pageHeight = height
    }

    fun setPoints(values: List<CanvasPoint>) {
        points = values.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val path = Path()
        path.moveTo(points.first().x * width / pageWidth, points.first().y * height / pageHeight)
        points.drop(1).forEach { point ->
            path.lineTo(point.x * width / pageWidth, point.y * height / pageHeight)
        }
        canvas.drawPath(path, paint)
    }
}

private class FinishedInkView(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
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
        strokes.clear()
        strokes.addAll(values)
        this.selected = selected
        invalidate()
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
