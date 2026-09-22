package com.majkeylab.seliadocs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate

@Composable
internal fun OnboardingScreen(onFinish: () -> Unit, saving: Boolean = false) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val title = when (step) {
        0 -> R.string.intro_subjects_title
        1 -> R.string.intro_input_title
        else -> R.string.intro_backup_title
    }
    val body = when (step) {
        0 -> R.string.intro_subjects_body
        1 -> R.string.intro_input_body
        else -> R.string.intro_backup_body
    }
    BackHandler(step > 0 && !saving) { step-- }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).testTag("onboarding")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                TextButton(onClick = onFinish, enabled = !saving) { Text(stringResource(R.string.intro_skip)) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 720.dp
                val description: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                        horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        IntroIllustration(step, Modifier.weight(1f).height(280.dp))
                        Box(Modifier.weight(1f)) { description() }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        IntroIllustration(step, Modifier.fillMaxWidth().height(220.dp))
                        description()
                    }
                }
            }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 480.dp).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) {
                        TextButton(onClick = { step-- }, enabled = !saving) { Text(stringResource(R.string.back)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.intro_page, step + 1, 3), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                }
                Button(onClick = { if (step == 2) onFinish() else step++ }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (step == 2) R.string.intro_start else R.string.intro_next))
                }
            }
        }
    }
}

@Composable
private fun IntroIllustration(step: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (step) {
            0 -> NotebookPreview(CoverColor.PERIWINKLE, CoverPattern.BAND, PaperTemplate.RULED,
                PageOrientation.PORTRAIT, stringResource(R.string.intro_preview_subject), false,
                Modifier.widthIn(max = 380.dp).fillMaxSize())
            1 -> Surface(color = Color(0xFFFFFEFA), contentColor = Color(0xFF202124),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 380.dp).fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.intro_preview_notes), style = MaterialTheme.typography.titleMedium)
                    Canvas(Modifier.weight(1f).fillMaxWidth()) {
                        repeat(3) { line ->
                            val y = (line + 1) * size.height / 4f
                            drawLine(Color(0xFFD4D1CB), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        drawLine(Color(0xFF3156D9), Offset(size.width * .1f, size.height * .28f),
                            Offset(size.width * .78f, size.height * .7f), 4.dp.toPx(), StrokeCap.Round)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        listOf(R.drawable.ic_stylus to R.string.tool_pen, R.drawable.ic_highlighter to R.string.tool_highlighter,
                            R.drawable.ic_text_fields to R.string.tool_type).forEach { (icon, label) ->
                            Icon(painterResource(icon), stringResource(label), Modifier.size(28.dp))
                        }
                    }
                }
            }
            else -> {
                val label = stringResource(R.string.intro_preview_backup)
                Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Icon(painterResource(R.drawable.ic_notebook), null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                    Icon(painterResource(R.drawable.ic_redo), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_backup), null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
