package com.majkeylab.seliadocs.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.PageOrientation

@Composable
internal fun NotebookOpeningCover(notebook: NotebookEntity?, enabled: Boolean, onFinished: (String) -> Unit) {
    if (notebook == null) return
    val progress = remember(notebook.id) { Animatable(0f) }
    var visible by remember(notebook.id) { mutableStateOf(true) }
    val finished = rememberUpdatedState(onFinished)
    val animate = enabled && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(notebook.id, animate) {
        if (animate) progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        visible = false
        finished.value(notebook.id)
    }
    if (animate && visible) {
        NotebookOpeningVisual(notebook, { progress.value }, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** Paint-only sibling of the editor. No Surface or pointer modifiers may consume the first pen stroke. */
@Composable
internal fun NotebookOpeningVisual(notebook: NotebookEntity, progress: () -> Float, modifier: Modifier = Modifier) {
    val color = runCatching { CoverColor.valueOf(notebook.coverColor) }.getOrDefault(CoverColor.PERIWINKLE)
    val pattern = runCatching { CoverPattern.valueOf(notebook.coverPattern) }.getOrDefault(CoverPattern.SOLID)
    val landscape = notebook.orientation == PageOrientation.LANDSCAPE.name
    val ratio = if (landscape) 842f / 595f else 595f / 842f
    BoxWithConstraints(modifier.clearAndSetSemantics { this[SemanticsProperties.TestTag] = "notebook-opening-decoration" },
        contentAlignment = Alignment.Center) {
        val coverWidth = minOf(maxWidth * 0.86f, maxHeight * 0.86f * ratio)
        if (coverWidth <= 0.dp) return@BoxWithConstraints
        Box(
            Modifier.width(coverWidth).aspectRatio(ratio)
                .graphicsLayer {
                    val fraction = progress().takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
                    transformOrigin = TransformOrigin(0f, .5f)
                    rotationY = -88f * fraction
                    cameraDistance = coverWidth.toPx() * 4f
                    alpha = 1f - fraction
                }
                .clip(RoundedCornerShape(12.dp))
                .background(coverColorValue(color)),
        ) {
            CoverPatternIllustration(pattern, Modifier.fillMaxSize())
            BindingIllustration(Modifier.width(28.dp).fillMaxHeight())
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.72f).padding(bottom = 32.dp)
                .background(Color(0xFFFDFBF7), RoundedCornerShape(6.dp)).padding(16.dp)) {
                Text(notebook.title, style = MaterialTheme.typography.titleLarge, color = Color(0xFF202124),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
