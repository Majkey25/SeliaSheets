package com.majkeylab.seliadocs.editor

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.StrokeEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PageCanvas(
    page: PageEntity?,
    pageNumber: Int,
    strokes: List<StrokeEntity>,
    elements: List<ElementEntity>,
    selectedStrokeIds: Set<String>,
    fingerDrawing: Boolean,
    tool: EditorTool,
    penWidth: Float,
    highlighterWidth: Float,
    pageTransitionEnabled: Boolean,
    onStrokeFinished: (Stroke) -> Unit,
    onEraseFinished: (List<CanvasPoint>) -> Unit,
    onLassoFinished: (List<CanvasPoint>) -> Unit,
    onMoveSelection: (CanvasPoint) -> Unit,
    assetFile: (String) -> File,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { pageTransition(pageTransitionEnabled) },
            label = "page",
        ) { target ->
            if (target != null) {
                Paper(
                    target,
                    pageNumber,
                    strokes,
                    elements,
                    selectedStrokeIds,
                    fingerDrawing,
                    tool,
                    penWidth,
                    highlighterWidth,
                    onStrokeFinished,
                    onEraseFinished,
                    onLassoFinished,
                    onMoveSelection,
                    assetFile,
                )
            }
        }
    }
}

private fun pageTransition(enabled: Boolean): ContentTransform =
    if (enabled) {
        (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(140)))
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }

@Composable
private fun Paper(
    page: PageEntity,
    pageNumber: Int,
    strokes: List<StrokeEntity>,
    elements: List<ElementEntity>,
    selectedStrokeIds: Set<String>,
    fingerDrawing: Boolean,
    tool: EditorTool,
    penWidth: Float,
    highlighterWidth: Float,
    onStrokeFinished: (Stroke) -> Unit,
    onEraseFinished: (List<CanvasPoint>) -> Unit,
    onLassoFinished: (List<CanvasPoint>) -> Unit,
    onMoveSelection: (CanvasPoint) -> Unit,
    assetFile: (String) -> File,
) {
    val ratio = page.widthPoints.toFloat() / page.heightPoints
    val decodedStrokes = remember(strokes) { strokes.map(StrokeEntity::toInkStroke) }
    val selected =
        remember(strokes, selectedStrokeIds) {
            strokes.mapIndexedNotNull { index, stroke -> index.takeIf { stroke.id in selectedStrokeIds } }.toSet()
        }
    val activeBrush = remember(tool, penWidth, highlighterWidth) { brushFor(tool, penWidth, highlighterWidth) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val availableRatio = maxWidth / maxHeight
        val paperModifier =
            if (availableRatio > ratio) {
                Modifier.height(maxHeight * 0.94f).aspectRatio(ratio)
            } else {
                Modifier.width(maxWidth * 0.94f).aspectRatio(ratio)
            }
        Surface(
            color = Color(0xFFFFFEFA),
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 4.dp,
            modifier = paperModifier,
        ) {
            Box {
                PaperPattern(page.paper, Modifier.fillMaxSize())
                ElementLayer(page, elements, assetFile)
                AndroidView(
                    factory = { context -> InkCanvasView(context) },
                    update = { view ->
                        view.setPageSize(page.widthPoints, page.heightPoints)
                        view.fingerDrawing = fingerDrawing
                        view.tool = tool
                        view.brush = activeBrush
                        view.listener =
                            object : InkCanvasView.Listener {
                                override fun onStrokeFinished(stroke: Stroke) {
                                    onStrokeFinished(stroke)
                                }

                                override fun onStrokeCanceled(pointerId: Int) = Unit

                                override fun onEraseFinished(points: List<CanvasPoint>) {
                                    onEraseFinished(points)
                                }

                                override fun onLassoFinished(points: List<CanvasPoint>) {
                                    onLassoFinished(points)
                                }

                                override fun onMoveSelection(delta: CanvasPoint) {
                                    onMoveSelection(delta)
                                }
                            }
                        view.setStrokes(decodedStrokes, selected)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = stringResource(R.string.page_number, pageNumber),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF7A7770),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun ElementLayer(
    page: PageEntity,
    elements: List<ElementEntity>,
    assetFile: (String) -> File,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scaleX = maxWidth.value / page.widthPoints
        val scaleY = maxHeight.value / page.heightPoints
        elements.forEach { element ->
            key(element.id) {
                val modifier =
                    Modifier
                        .offset((element.x * scaleX).dp, (element.y * scaleY).dp)
                        .width((element.width * scaleX).dp)
                        .height((element.height * scaleY).dp)
                        .rotate(element.rotation)
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
                        StoredImage(assetFile(id), modifier)
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
private fun StoredImage(file: File, modifier: Modifier) {
    val bitmap by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path, file.lastModified()) {
            value = withContext(Dispatchers.IO) { decodePreview(file)?.asImageBitmap() }
        }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.inserted_image),
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
        )
    }
}

private fun decodePreview(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 2_048 || bounds.outHeight / sample > 2_048) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun brushFor(tool: EditorTool, penWidth: Float, highlighterWidth: Float) =
    when (tool) {
        EditorTool.PEN -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), penWidth)
        EditorTool.PENCIL ->
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF4A4A4A.toInt(), penWidth * 0.55f)
        EditorTool.HIGHLIGHTER ->
            InkCodec.createBrush(BrushKind.HIGHLIGHTER, 0x66FFD54F, highlighterWidth)
        EditorTool.ERASER,
        EditorTool.LASSO,
        -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), penWidth)
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
