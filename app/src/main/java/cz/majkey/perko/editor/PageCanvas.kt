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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.majkey.perko.R
import cz.majkey.perko.data.PageEntity
import cz.majkey.perko.data.PaperTemplate

@Composable
internal fun PageCanvas(page: PageEntity?, pageNumber: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { pageTransition() },
            label = "page",
        ) { target ->
            if (target != null) {
                Paper(target, pageNumber)
            }
        }
    }
}

private fun pageTransition(): ContentTransform =
    (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
        (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(140)))

@Composable
private fun Paper(page: PageEntity, pageNumber: Int) {
    val ratio = page.widthPoints.toFloat() / page.heightPoints
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
