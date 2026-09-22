package com.majkeylab.seliadocs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.settings.ThemePalette

@Composable
internal fun ThemePalettePicker(
    selected: ThemePalette,
    onSelect: (ThemePalette) -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemePalette.entries.forEach { palette ->
            val colors = themeColorScheme(darkTheme, palette)
            val isSelected = palette == selected
            val name = stringResource(
                when (palette) {
                    ThemePalette.CLASSIC -> R.string.theme_palette_classic
                    ThemePalette.OCEAN -> R.string.theme_palette_ocean
                    ThemePalette.FOREST -> R.string.theme_palette_forest
                    ThemePalette.ROSE -> R.string.theme_palette_rose
                    ThemePalette.AMBER -> R.string.theme_palette_amber
                    ThemePalette.GRAPHITE -> R.string.theme_palette_graphite
                },
            )
            val description = stringResource(R.string.theme_palette_option, name)
            val selectionState = stringResource(
                if (isSelected) R.string.selection_state_selected else R.string.selection_state_not_selected,
            )
            Surface(
                color = colors.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.primary else colors.outline),
                modifier = Modifier.width(148.dp).heightIn(min = 48.dp)
                    .selectable(selected = isSelected, onClick = { onSelect(palette) }, role = Role.RadioButton)
                    .semantics {
                        contentDescription = description
                        stateDescription = selectionState
                    },
            ) {
                Column(Modifier.padding(10.dp)) {
                    AppChromePreview(colors)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.primary,
                                unselectedColor = colors.onSurfaceVariant,
                            ),
                            modifier = Modifier.size(24.dp),
                        )
                        Text(name, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppChromePreview(colors: ColorScheme) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.primaryContainer, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.ic_stylus), null, Modifier.size(14.dp), tint = colors.onPrimaryContainer)
            Box(Modifier.width(40.dp).height(3.dp).background(colors.onPrimaryContainer, CircleShape))
            Spacer(Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_more_vert), null, Modifier.size(12.dp), tint = colors.onPrimaryContainer)
        }
        Box(Modifier.fillMaxWidth().height(52.dp).background(colors.background)) {
            Row(Modifier.fillMaxSize().padding(7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) {
                    Column(
                        modifier = Modifier.weight(1f).height(32.dp).background(colors.surfaceVariant, RoundedCornerShape(3.dp)).padding(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.fillMaxWidth(0.7f).height(3.dp).background(colors.onSurfaceVariant, CircleShape))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.outline, CircleShape))
                    }
                }
            }
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(14.dp), tint = colors.onPrimary)
            }
        }
    }
}
