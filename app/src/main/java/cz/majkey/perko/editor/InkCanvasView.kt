package cz.majkey.perko.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
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

internal class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), InProgressStrokesFinishedListener {
    internal interface Listener {
        fun onStrokeFinished(stroke: Stroke)

        fun onStrokeCanceled(pointerId: Int)
    }

    private val finishedView = FinishedInkView(context)
    private val inProgressView = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val activeStrokes = mutableMapOf<Int, InProgressStrokeId>()
    private val identity = Matrix()
    private var pageWidth = 595f
    private var pageHeight = 842f

    var listener: Listener? = null
    var fingerDrawing: Boolean = false
    var brush = InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f)

    init {
        addView(finishedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgressView.addFinishedStrokesListener(this)
        inProgressView.eagerInit()
        setOnTouchListener { _, event -> handleMotionEvent(event) }
    }

    fun setStrokes(strokes: List<Stroke>) {
        finishedView.setStrokes(strokes)
    }

    fun setPageSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        pageWidth = width.toFloat()
        pageHeight = height.toFloat()
        finishedView.setPageSize(pageWidth, pageHeight)
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        finishedView.addStrokes(strokes.values)
        finishedView.invalidate()
        inProgressView.removeFinishedStrokes(strokes.keys)
        strokes.values.forEach { listener?.onStrokeFinished(it) }
    }

    override fun onDetachedFromWindow() {
        inProgressView.removeFinishedStrokesListener(this)
        super.onDetachedFromWindow()
    }

    private fun handleMotionEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startStroke(event)
            MotionEvent.ACTION_MOVE -> addToStrokes(event)
            MotionEvent.ACTION_UP -> finishStroke(event)
            MotionEvent.ACTION_CANCEL -> cancelAll(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelAll(event)
                false
            }
            else -> activeStrokes.isNotEmpty()
        }
    }

    private fun startStroke(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        if (!canDraw(event.getToolType(pointerIndex))) return false
        requestUnbufferedDispatch(event)
        val pointerId = event.getPointerId(pointerIndex)
        val inputToWorld =
            Matrix().apply {
                setScale(
                    pageWidth / width.coerceAtLeast(1),
                    pageHeight / height.coerceAtLeast(1),
                )
            }
        activeStrokes[pointerId] =
            inProgressView.startStroke(event, pointerId, brush, inputToWorld, identity)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun addToStrokes(event: MotionEvent): Boolean {
        if (activeStrokes.isEmpty()) return false
        val prediction = predictor.predict()
        try {
            activeStrokes.forEach { (pointerId, strokeId) ->
                inProgressView.addToStroke(event, pointerId, strokeId, prediction)
            }
        } finally {
            prediction?.recycle()
        }
        return true
    }

    private fun finishStroke(event: MotionEvent): Boolean {
        val pointerId = event.getPointerId(event.actionIndex)
        val strokeId = activeStrokes.remove(pointerId) ?: return false
        val canceled = event.flags and MotionEvent.FLAG_CANCELED != 0
        if (canceled) {
            inProgressView.cancelStroke(strokeId, event)
            listener?.onStrokeCanceled(pointerId)
        } else {
            inProgressView.finishStroke(event, pointerId, strokeId)
        }
        if (activeStrokes.isEmpty()) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun cancelAll(event: MotionEvent?): Boolean {
        if (activeStrokes.isEmpty()) return false
        activeStrokes.forEach { (pointerId, strokeId) ->
            inProgressView.cancelStroke(strokeId, event)
            listener?.onStrokeCanceled(pointerId)
        }
        activeStrokes.clear()
        parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    private fun canDraw(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            (fingerDrawing && toolType == MotionEvent.TOOL_TYPE_FINGER)
}

private class FinishedInkView(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
    private val renderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
    private var pageWidth = 595f
    private var pageHeight = 842f

    fun setPageSize(width: Float, height: Float) {
        pageWidth = width
        pageHeight = height
        invalidate()
    }

    fun setStrokes(values: List<Stroke>) {
        strokes.clear()
        strokes.addAll(values)
        invalidate()
    }

    fun addStrokes(values: Collection<Stroke>) {
        strokes.addAll(values)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.drawWithStrokes(canvas) { scope ->
            canvas.scale(width / pageWidth, height / pageHeight)
            strokes.forEach(scope::drawStroke)
        }
    }
}
