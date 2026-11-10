package ru.tinyops.android.bookrecon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.tinyops.android.bookrecon.core.domain.ColorTheme

/**
 * Material 3 theme of the app in one of the pastel [colorTheme]s. Previews use the default
 * blue theme in the system mode.
 */
@Composable
fun BookReconTheme(
    colorTheme: ColorTheme = ColorTheme.BLUEBERRY,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(colorTheme, darkTheme) { colorSchemeFor(colorTheme, darkTheme) }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
