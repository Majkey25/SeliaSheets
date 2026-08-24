package cz.majkey.perko.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import cz.majkey.perko.R
import cz.majkey.perko.data.PageEntity
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.data.StrokeEntity

@Composable
internal fun PageCanvas(
    page: PageEntity?,
    pageNumber: Int,
    strokes: List<StrokeEntity>,
    selectedStrokeIds: Set<String>,
    fingerDrawing: Boolean,
    tool: EditorTool,
    onStrokeFinished: (Stroke) -> Unit,
    onEraseFinished: (List<CanvasPoint>) -> Unit,
    onLassoFinished: (List<CanvasPoint>) -> Unit,
    onMoveSelection: (CanvasPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { pageTransition() },
            label = "page",
        ) { target ->
            if (target != null) {
                Paper(
                    target,
                    pageNumber,
                    strokes,
                    selectedStrokeIds,
                    fingerDrawing,
                    tool,
                    onStrokeFinished,
                    onEraseFinished,
                    onLassoFinished,
                    onMoveSelection,
                )
            }
        }
    }
}

private fun pageTransition(): ContentTransform =
    (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
        (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(140)))

@Composable
private fun Paper(
    page: PageEntity,
    pageNumber: Int,
    strokes: List<StrokeEntity>,
    selectedStrokeIds: Set<String>,
    fingerDrawing: Boolean,
    tool: EditorTool,
    onStrokeFinished: (Stroke) -> Unit,
    onEraseFinished: (List<CanvasPoint>) -> Unit,
    onLassoFinished: (List<CanvasPoint>) -> Unit,
    onMoveSelection: (CanvasPoint) -> Unit,
) {
    val ratio = page.widthPoints.toFloat() / page.heightPoints
    val decodedStrokes = remember(strokes) { strokes.map(StrokeEntity::toInkStroke) }
    val selected =
        remember(strokes, selectedStrokeIds) {
            strokes.mapIndexedNotNull { index, stroke -> index.takeIf { stroke.id in selectedStrokeIds } }.toSet()
        }
    val activeBrush = remember(tool) { brushFor(tool) }
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

private fun brushFor(tool: EditorTool) =
    when (tool) {
        EditorTool.PEN -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f)
        EditorTool.PENCIL -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF4A4A4A.toInt(), 2.2f)
        EditorTool.HIGHLIGHTER -> InkCodec.createBrush(BrushKind.HIGHLIGHTER, 0x66FFD54F, 22f)
        EditorTool.ERASER,
        EditorTool.LASSO,
        -> InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f)
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
