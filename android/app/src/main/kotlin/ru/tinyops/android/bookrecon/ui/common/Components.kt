package ru.tinyops.android.bookrecon.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.tinyops.android.bookrecon.core.domain.MediaKind

/** Large title at the top of a tab screen. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, inset: Dp = 24.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = inset, end = inset, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * Tonal tile with the icon of a book or an audiobook: books use the primary container,
 * audiobooks the tertiary one, so the two kinds tell apart at a glance in every theme.
 */
@Composable
fun KindTile(kind: MediaKind, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        MediaKind.BOOK -> colors.primaryContainer to colors.onPrimaryContainer
        MediaKind.AUDIO -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(size * TILE_CORNER_RATIO),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (kind == MediaKind.AUDIO) Icons.Filled.Headphones else Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/** Small rounded label: a format, a size, a state like "Скачано". */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: (@Composable () -> Unit)? = null,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            if (icon != null) {
                Box(modifier = Modifier.padding(end = 4.dp).size(14.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Shape of a pill-like search field. */
val PillShape = RoundedCornerShape(28.dp)

/** Colors of a pill-like search field: a tonal container without the underline. */
@Composable
fun pillFieldColors(): TextFieldColors {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    return TextFieldDefaults.colors(
        focusedContainerColor = container,
        unfocusedContainerColor = container,
        disabledContainerColor = container,
        errorContainerColor = container,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )
}

private const val TILE_CORNER_RATIO = 0.29f
