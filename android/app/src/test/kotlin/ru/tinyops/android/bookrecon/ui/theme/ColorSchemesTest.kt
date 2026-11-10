package ru.tinyops.android.bookrecon.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode

class ColorSchemesTest {
    @Test
    fun darkModeFollowsTheSystemOnlyWhenAsked() {
        assertTrue(DarkMode.SYSTEM.isDark(systemDark = true))
        assertFalse(DarkMode.SYSTEM.isDark(systemDark = false))
        assertTrue(DarkMode.DARK.isDark(systemDark = false))
        assertFalse(DarkMode.LIGHT.isDark(systemDark = true))
    }

    @Test
    fun everyThemeHasItsOwnPrimaryColor() {
        for (dark in listOf(false, true)) {
            val primaries = ColorTheme.entries.map { colorSchemeFor(it, dark).primary }
            assertEquals(ColorTheme.entries.size, primaries.toSet().size, "dark=$dark")
        }
    }

    @Test
    fun lightSchemesAreLightAndDarkSchemesAreDark() {
        for (theme in ColorTheme.entries) {
            assertTrue(colorSchemeFor(theme, dark = false).background.luminance() > 0.8f, "$theme light")
            assertTrue(colorSchemeFor(theme, dark = true).background.luminance() < 0.05f, "$theme dark")
        }
    }

    @Test
    fun textIsReadableOnItsBackground() {
        for (theme in ColorTheme.entries) {
            for (dark in listOf(false, true)) {
                val s = colorSchemeFor(theme, dark)
                val pairs = mapOf(
                    "onPrimary/primary" to (s.onPrimary to s.primary),
                    "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                    "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                    "onSurface/surface" to (s.onSurface to s.surface),
                    "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
                    "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
                    "primary/surface" to (s.primary to s.surface),
                    "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
                )
                for ((name, pair) in pairs) {
                    val ratio = contrast(pair.first, pair.second)
                    assertTrue(ratio >= 4.5, "$theme dark=$dark $name: $ratio")
                }
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}
