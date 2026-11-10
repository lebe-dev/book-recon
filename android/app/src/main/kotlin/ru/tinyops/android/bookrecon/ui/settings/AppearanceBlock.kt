package ru.tinyops.android.bookrecon.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.ui.theme.colorSchemeFor

/** Color theme grid and the dark mode choice. */
@Composable
internal fun AppearanceBlock(settings: Settings, actions: SettingsActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        ThemeGrid(settings.colorTheme, actions.onColorThemeChange)
        Text(
            stringResource(R.string.settings_dark_mode),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DarkMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == settings.darkMode,
                    onClick = { actions.onDarkModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                    label = { Text(stringResource(mode.label()), maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun ThemeGrid(selected: ColorTheme, onSelect: (ColorTheme) -> Unit) {
    // The swatches show each theme in the mode the app is in now.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorTheme.entries.chunked(THEMES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { theme ->
                    ThemeSwatch(
                        theme = theme,
                        dark = dark,
                        selected = theme == selected,
                        onClick = { onSelect(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: ColorTheme, dark: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = remember(theme, dark) { colorSchemeFor(theme, dark) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.surfaceContainerHighest else Color.Transparent,
        border = BorderStroke(2.dp, if (selected) colors.primary else Color.Transparent),
        modifier = modifier
            .height(96.dp)
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            0.5f to scheme.primary,
                            0.5f to scheme.primaryContainer,
                        ),
                    ),
            ) {
                if (selected) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(26.dp).clip(CircleShape).background(scheme.primary),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Text(stringResource(theme.label()), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        }
    }
}

private const val THEMES_PER_ROW = 3

@StringRes
internal fun ColorTheme.label(): Int = when (this) {
    ColorTheme.BLUEBERRY -> R.string.settings_theme_blueberry
    ColorTheme.PISTACHIO -> R.string.settings_theme_pistachio
    ColorTheme.APPLE -> R.string.settings_theme_apple
    ColorTheme.BILBERRY -> R.string.settings_theme_bilberry
    ColorTheme.MARSHMALLOW -> R.string.settings_theme_marshmallow
    ColorTheme.COCOA -> R.string.settings_theme_cocoa
}

@StringRes
private fun DarkMode.label(): Int = when (this) {
    DarkMode.SYSTEM -> R.string.settings_dark_mode_system
    DarkMode.LIGHT -> R.string.settings_dark_mode_light
    DarkMode.DARK -> R.string.settings_dark_mode_dark
}
