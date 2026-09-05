package com.majkeylab.seliadocs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.library.NotebookTemplate

@Composable
internal fun NotebookPreview(
    coverColor: CoverColor,
    coverPattern: CoverPattern,
    paper: PaperTemplate,
    orientation: PageOrientation,
    title: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val description =
        stringResource(
            R.string.notebook_preview_description,
            paperLabel(paper),
            orientationLabel(orientation),
        )
    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = coverColorValue(coverColor),
            shape = RoundedCornerShape(if (compact) 7.dp else 11.dp),
            shadowElevation = if (compact) 1.dp else 5.dp,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.62f).fillMaxHeight(0.92f),
        ) {
            Box {
                CoverPatternIllustration(coverPattern, Modifier.fillMaxSize())
                BindingIllustration(
                    Modifier.align(Alignment.CenterStart).width(if (compact) 18.dp else 28.dp).fillMaxHeight(),
                )
                Surface(
                    color = Color(0xFFE9A092),
                    shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .width(if (compact) 28.dp else 42.dp)
                            .height(if (compact) 14.dp else 20.dp),
                ) {}
                Surface(
                    color = Color(0xFFFDFBF7),
                    shape = RoundedCornerShape(4.dp),
                    modifier =
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.72f)
                            .padding(bottom = if (compact) 10.dp else 16.dp),
                ) {
                    Text(
                        text = title.ifBlank { stringResource(R.string.untitled_notebook) },
                        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Surface(
            color = Color(0xFFFFFEFA),
            shape = RoundedCornerShape(3.dp),
            border = BorderStroke(1.dp, Color(0xFFC9C6BF)),
            shadowElevation = if (compact) 1.dp else 3.dp,
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxWidth(if (orientation == PageOrientation.LANDSCAPE) 0.58f else 0.46f)
                    .fillMaxHeight(if (orientation == PageOrientation.LANDSCAPE) 0.58f else 0.78f),
        ) {
            PaperIllustration(paper, Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun TemplatePreview(
    template: NotebookTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = templateLabel(template)
    val description = stringResource(R.string.notebook_template_option, label)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            modifier
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                )
                .semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            NotebookPreview(
                coverColor = template.coverColor,
                coverPattern = template.coverPattern,
                paper = template.paper,
                orientation = template.orientation,
                title = label,
                compact = true,
                modifier = Modifier.fillMaxWidth().height(92.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                templateUseCase(template),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PaperPreview(
    paper: PaperTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = paperLabel(paper)
    val description = stringResource(R.string.paper_option, label)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            modifier.selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton)
                .semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = Color(0xFFFFFEFA),
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, Color(0xFFC9C6BF)),
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {
                PaperIllustration(paper, Modifier.fillMaxSize())
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun CoverPatternPreview(
    pattern: CoverPattern,
    coverColor: CoverColor,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = modifier.selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = coverColorValue(coverColor),
                shape = RoundedCornerShape(5.dp),
                modifier = Modifier.fillMaxWidth().height(68.dp),
            ) {
                Box {
                    CoverPatternIllustration(pattern, Modifier.fillMaxSize())
                    BindingIllustration(Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight())
                }
            }
            Text(patternLabel(pattern), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun OrientationPreview(
    orientation: PageOrientation,
    paper: PaperTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = orientationLabel(orientation)
    val description = stringResource(R.string.orientation_option, label)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            modifier.selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton)
                .semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color(0xFFFFFEFA),
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, Color(0xFFC9C6BF)),
                    modifier =
                        if (orientation == PageOrientation.PORTRAIT) {
                            Modifier.width(46.dp).height(66.dp)
                        } else {
                            Modifier.width(76.dp).height(50.dp)
                        },
                ) {
                    PaperIllustration(paper, Modifier.fillMaxSize())
                }
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PaperIllustration(paper: PaperTemplate, modifier: Modifier) {
    Canvas(modifier) {
        val color = Color(0xFFD5D7DC)
        val spacing = size.minDimension / 5f
        when (paper) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = spacing
                while (y < size.height) {
                    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    y += spacing
                }
            }
            PaperTemplate.GRID -> {
                var x = spacing
                while (x < size.width) {
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    x += spacing
                }
                var y = spacing
                while (y < size.height) {
                    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    y += spacing
                }
            }
            PaperTemplate.DOT -> {
                var y = spacing
                while (y < size.height) {
                    var x = spacing
                    while (x < size.width) {
                        drawCircle(color, radius = 1.2.dp.toPx(), center = Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }
}

@Composable
private fun CoverPatternIllustration(pattern: CoverPattern, modifier: Modifier) {
    Canvas(modifier) {
        when (pattern) {
            CoverPattern.SOLID -> Unit
            CoverPattern.BAND ->
                drawRect(
                    Color.White.copy(alpha = 0.17f),
                    topLeft = Offset(size.width * 0.62f, 0f),
                    size = Size(size.width * 0.16f, size.height),
                )
            CoverPattern.CORNERS -> {
                drawCircle(Color(0xFFE9A092).copy(alpha = 0.55f), size.minDimension * 0.22f, Offset(size.width, 0f))
                drawCircle(Color.White.copy(alpha = 0.18f), size.minDimension * 0.18f, Offset(0f, size.height))
            }
            CoverPattern.GRID -> {
                val spacing = size.minDimension / 5f
                var x = spacing
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.16f), Offset(x, 0f), Offset(x, size.height))
                    x += spacing
                }
                var y = spacing
                while (y < size.height) {
                    drawLine(Color.White.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y))
                    y += spacing
                }
            }
        }
    }
}

@Composable
private fun BindingIllustration(modifier: Modifier) {
    Canvas(modifier) {
        repeat(4) { index ->
            val y = size.height * (0.2f + index * 0.2f)
            drawArc(
                color = Color(0xFFFDFBF7),
                startAngle = 70f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = Offset(-size.width * 0.2f, y - size.width * 0.35f),
                size = Size(size.width * 0.85f, size.width * 0.7f),
                style = Stroke(width = size.width * 0.2f, cap = StrokeCap.Round),
            )
        }
    }
}

internal fun coverColorValue(value: CoverColor): Color =
    when (value) {
        CoverColor.PERIWINKLE -> Color(0xFFA0B1D7)
        CoverColor.GRAPHITE -> Color(0xFFB7B5B2)
        CoverColor.SAGE -> Color(0xFFB7C4AF)
        CoverColor.SALMON -> Color(0xFFE9A092)
        CoverColor.SAND -> Color(0xFFD9C7AA)
    }

@Composable
internal fun templateLabel(value: NotebookTemplate): String =
    stringResource(
        when (value) {
            NotebookTemplate.RULED_NOTES -> R.string.template_ruled_notes
            NotebookTemplate.GRID_NOTEBOOK -> R.string.template_grid_notebook
            NotebookTemplate.DOTTED_JOURNAL -> R.string.template_dotted_journal
            NotebookTemplate.BLANK_SKETCHBOOK -> R.string.template_blank_sketchbook
        },
    )

@Composable
private fun templateUseCase(value: NotebookTemplate): String =
    stringResource(
        when (value) {
            NotebookTemplate.RULED_NOTES -> R.string.template_ruled_notes_hint
            NotebookTemplate.GRID_NOTEBOOK -> R.string.template_grid_notebook_hint
            NotebookTemplate.DOTTED_JOURNAL -> R.string.template_dotted_journal_hint
            NotebookTemplate.BLANK_SKETCHBOOK -> R.string.template_blank_sketchbook_hint
        },
    )

@Composable
internal fun paperLabel(value: PaperTemplate): String =
    stringResource(
        when (value) {
            PaperTemplate.BLANK -> R.string.paper_blank
            PaperTemplate.RULED -> R.string.paper_ruled
            PaperTemplate.GRID -> R.string.paper_grid
            PaperTemplate.DOT -> R.string.paper_dot
        },
    )

@Composable
internal fun orientationLabel(value: PageOrientation): String =
    stringResource(if (value == PageOrientation.PORTRAIT) R.string.portrait else R.string.landscape)

@Composable
internal fun patternLabel(value: CoverPattern): String =
    stringResource(
        when (value) {
            CoverPattern.SOLID -> R.string.pattern_solid
            CoverPattern.BAND -> R.string.pattern_band
            CoverPattern.CORNERS -> R.string.pattern_corners
            CoverPattern.GRID -> R.string.pattern_grid
        },
    )
