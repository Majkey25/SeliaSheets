package com.majkeylab.seliadocs.editor

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind

internal fun hitShapePath(eraser: List<CanvasPoint>, radius: Float, element: ElementEntity): Boolean {
    if (element.kind != ElementKind.SHAPE.name || eraser.isEmpty()) return false
    val kind = element.shapeKind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() } ?: return false
    val width = element.width
    val height = element.height
    val centerY = height / 2f
    val outline = Path()
    when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> {
            outline.moveTo(0f, centerY)
            outline.lineTo(width, centerY)
        }
        ShapeKind.ELLIPSE -> outline.addOval(0f, 0f, width, height, Path.Direction.CW)
        ShapeKind.RECTANGLE -> outline.addRect(0f, 0f, width, height, Path.Direction.CW)
        ShapeKind.TRIANGLE -> {
            outline.moveTo(width / 2f, 0f)
            outline.lineTo(width, height)
            outline.lineTo(0f, height)
            outline.close()
        }
    }
    if (kind == ShapeKind.ARROW) {
        val head = minOf(18f, width / 3f)
        outline.moveTo(width - head, centerY - head * 0.55f)
        outline.lineTo(width, centerY)
        outline.lineTo(width - head, centerY + head * 0.55f)
    }
    val paintedShape = Path()
    val shapePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = element.strokeWidth ?: 3f
        strokeJoin = Paint.Join.MITER
        strokeMiter = 4f
        strokeCap = Paint.Cap.BUTT
    }
    check(shapePaint.getFillPath(outline, paintedShape)) { "Shape eraser outline could not be expanded" }
    paintedShape.transform(Matrix().apply {
        postRotate(element.rotation, width / 2f, centerY)
        postTranslate(element.x, element.y)
    })
    val footprint = Path()
    if (eraser.all { it == eraser.first() }) {
        footprint.addCircle(eraser.first().x, eraser.first().y, radius, Path.Direction.CW)
    } else {
        val trail = Path().apply {
            moveTo(eraser.first().x, eraser.first().y)
            for (index in 1 until eraser.size) lineTo(eraser[index].x, eraser[index].y)
        }
        val eraserPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        check(eraserPaint.getFillPath(trail, footprint)) { "Shape eraser footprint could not be expanded" }
    }
    check(paintedShape.op(footprint, Path.Op.INTERSECT)) { "Shape eraser intersection failed" }
    return !paintedShape.isEmpty
}
