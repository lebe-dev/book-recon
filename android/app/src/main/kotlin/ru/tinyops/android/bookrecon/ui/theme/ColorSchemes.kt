package ru.tinyops.android.bookrecon.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode

/** Whether the app is dark in this mode when the system is [systemDark]. */
fun DarkMode.isDark(systemDark: Boolean): Boolean = when (this) {
    DarkMode.SYSTEM -> systemDark
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/** Material 3 color scheme of [theme] in the light or [dark] variant. */
fun colorSchemeFor(theme: ColorTheme, dark: Boolean): ColorScheme {
    val pair = palettes.getValue(theme)
    return if (dark) pair.dark.toDarkScheme(inversePrimary = pair.light.primary)
    else pair.light.toLightScheme(inversePrimary = pair.dark.primary)
}

/**
 * The colors a theme variant is defined by; the other roles of the scheme are derived from
 * them. [container] and [highest] are surfaceContainer and surfaceContainerHighest.
 */
private data class Palette(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val surface: Long,
    val container: Long,
    val highest: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
)

private data class PalettePair(val light: Palette, val dark: Palette)

private fun Palette.toLightScheme(inversePrimary: Long): ColorScheme {
    val surface = Color(surface)
    val container = Color(container)
    val highest = Color(highest)
    val onSurface = Color(onSurface)
    return lightColorScheme(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondary = Color(secondary),
        onSecondary = Color.White,
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary),
        onTertiary = Color.White,
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = highest,
        onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(primary),
        inverseSurface = lerp(onSurface, surface, 0.08f),
        inverseOnSurface = container,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),
        scrim = Color.Black,
        surfaceBright = surface,
        surfaceDim = lerp(highest, onSurface, 0.05f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = lerp(surface, container, 0.5f),
        surfaceContainer = container,
        surfaceContainerHigh = lerp(container, highest, 0.5f),
        surfaceContainerHighest = highest,
    )
}

private fun Palette.toDarkScheme(inversePrimary: Long): ColorScheme {
    val surface = Color(surface)
    val container = Color(container)
    val highest = Color(highest)
    val onSurface = Color(onSurface)
    return darkColorScheme(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondary = Color(secondary),
        onSecondary = lerp(Color(secondaryContainer), Color.Black, 0.35f),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary),
        onTertiary = lerp(Color(tertiaryContainer), Color.Black, 0.35f),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = highest,
        onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(primary),
        inverseSurface = onSurface,
        inverseOnSurface = lerp(surface, onSurface, 0.15f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),
        scrim = Color.Black,
        surfaceBright = lerp(highest, Color.White, 0.04f),
        surfaceDim = surface,
        surfaceContainerLowest = lerp(surface, Color.Black, 0.3f),
        surfaceContainerLow = lerp(surface, container, 0.6f),
        surfaceContainer = container,
        surfaceContainerHigh = lerp(container, highest, 0.5f),
        surfaceContainerHighest = highest,
    )
}

private val palettes: Map<ColorTheme, PalettePair> = mapOf(
    ColorTheme.BLUEBERRY to PalettePair(
        light = Palette(
            primary = 0xFF415F91, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFD6E3FF, onPrimaryContainer = 0xFF284777,
            secondary = 0xFF565F71, secondaryContainer = 0xFFDAE2F9, onSecondaryContainer = 0xFF3E4759,
            tertiary = 0xFF705575, tertiaryContainer = 0xFFFAD8FD, onTertiaryContainer = 0xFF5B3B5E,
            surface = 0xFFF9F9FF, container = 0xFFEDEDF4, highest = 0xFFE1E2E9,
            onSurface = 0xFF191C20, onSurfaceVariant = 0xFF44474E, outline = 0xFF74777F, outlineVariant = 0xFFC4C6D0,
        ),
        dark = Palette(
            primary = 0xFFAAC7FF, onPrimary = 0xFF0A305F, primaryContainer = 0xFF284777, onPrimaryContainer = 0xFFD6E3FF,
            secondary = 0xFFBEC6DC, secondaryContainer = 0xFF3E4759, onSecondaryContainer = 0xFFDAE2F9,
            tertiary = 0xFFDDBCE0, tertiaryContainer = 0xFF5B3B5E, onTertiaryContainer = 0xFFFAD8FD,
            surface = 0xFF111318, container = 0xFF1D2024, highest = 0xFF33353A,
            onSurface = 0xFFE2E2E9, onSurfaceVariant = 0xFFC4C6D0, outline = 0xFF8E9099, outlineVariant = 0xFF44474E,
        ),
    ),
    ColorTheme.PISTACHIO to PalettePair(
        light = Palette(
            primary = 0xFF3D6838, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFBEEFB2, onPrimaryContainer = 0xFF255023,
            secondary = 0xFF53634E, secondaryContainer = 0xFFD7E8CF, onSecondaryContainer = 0xFF3C4B38,
            tertiary = 0xFF39656B, tertiaryContainer = 0xFFBCEBEE, onTertiaryContainer = 0xFF1F4E52,
            surface = 0xFFF8FBF1, container = 0xFFECEFE6, highest = 0xFFE1E4DB,
            onSurface = 0xFF191D17, onSurfaceVariant = 0xFF42493F, outline = 0xFF72796F, outlineVariant = 0xFFC2C9BD,
        ),
        dark = Palette(
            primary = 0xFFA3D398, onPrimary = 0xFF0F380E, primaryContainer = 0xFF255023, onPrimaryContainer = 0xFFBEEFB2,
            secondary = 0xFFBACCB3, secondaryContainer = 0xFF3C4B38, onSecondaryContainer = 0xFFD7E8CF,
            tertiary = 0xFFA1CED5, tertiaryContainer = 0xFF1F4E52, onTertiaryContainer = 0xFFBCEBEE,
            surface = 0xFF10140F, container = 0xFF1D211B, highest = 0xFF323630,
            onSurface = 0xFFE0E4DB, onSurfaceVariant = 0xFFC2C9BD, outline = 0xFF8C9388, outlineVariant = 0xFF42493F,
        ),
    ),
    ColorTheme.APPLE to PalettePair(
        light = Palette(
            primary = 0xFF904A43, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFFFDAD5, onPrimaryContainer = 0xFF73342D,
            secondary = 0xFF775652, secondaryContainer = 0xFFF7DDD9, onSecondaryContainer = 0xFF5D3F3B,
            tertiary = 0xFF705C2E, tertiaryContainer = 0xFFFBDFA6, onTertiaryContainer = 0xFF5A4300,
            surface = 0xFFFFF8F7, container = 0xFFFCEAE7, highest = 0xFFF1DEDC,
            onSurface = 0xFF231918, onSurfaceVariant = 0xFF534341, outline = 0xFF857371, outlineVariant = 0xFFD8C2BF,
        ),
        dark = Palette(
            primary = 0xFFFFB4AA, onPrimary = 0xFF561E19, primaryContainer = 0xFF73342D, onPrimaryContainer = 0xFFFFDAD5,
            secondary = 0xFFE7BDB7, secondaryContainer = 0xFF5D3F3B, onSecondaryContainer = 0xFFF7DDD9,
            tertiary = 0xFFDFC38C, tertiaryContainer = 0xFF5A4300, onTertiaryContainer = 0xFFFBDFA6,
            surface = 0xFF1A1110, container = 0xFF271D1C, highest = 0xFF3D3231,
            onSurface = 0xFFF1DEDC, onSurfaceVariant = 0xFFD8C2BF, outline = 0xFFA08C8A, outlineVariant = 0xFF534341,
        ),
    ),
    ColorTheme.BILBERRY to PalettePair(
        light = Palette(
            primary = 0xFF2B6780, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFC1E8FF, onPrimaryContainer = 0xFF004D66,
            secondary = 0xFF4D616C, secondaryContainer = 0xFFCFE6F1, onSecondaryContainer = 0xFF344A53,
            tertiary = 0xFF5B5B7E, tertiaryContainer = 0xFFDFE0FF, onTertiaryContainer = 0xFF43466F,
            surface = 0xFFF6FAFE, container = 0xFFEAEEF2, highest = 0xFFDFE3E7,
            onSurface = 0xFF171C1F, onSurfaceVariant = 0xFF40484C, outline = 0xFF70787D, outlineVariant = 0xFFC0C8CD,
        ),
        dark = Palette(
            primary = 0xFF96CDEC, onPrimary = 0xFF003548, primaryContainer = 0xFF0B4D66, onPrimaryContainer = 0xFFC1E8FF,
            secondary = 0xFFB4CAD6, secondaryContainer = 0xFF344A53, onSecondaryContainer = 0xFFCFE6F1,
            tertiary = 0xFFC4C3EA, tertiaryContainer = 0xFF43466F, onTertiaryContainer = 0xFFDFE0FF,
            surface = 0xFF0F1417, container = 0xFF1B2023, highest = 0xFF303538,
            onSurface = 0xFFDEE3E7, onSurfaceVariant = 0xFFC0C8CD, outline = 0xFF8A9297, outlineVariant = 0xFF40484C,
        ),
    ),
    ColorTheme.MARSHMALLOW to PalettePair(
        light = Palette(
            primary = 0xFF8B4A62, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFFFD9E3, onPrimaryContainer = 0xFF6F334A,
            secondary = 0xFF74565F, secondaryContainer = 0xFFF7DCE3, onSecondaryContainer = 0xFF5A3F47,
            tertiary = 0xFF7C5635, tertiaryContainer = 0xFFFFDCC2, onTertiaryContainer = 0xFF623F21,
            surface = 0xFFFFF8F8, container = 0xFFFAEAED, highest = 0xFFEFDFE2,
            onSurface = 0xFF22191C, onSurfaceVariant = 0xFF514347, outline = 0xFF837377, outlineVariant = 0xFFD5C2C6,
        ),
        dark = Palette(
            primary = 0xFFFFB0C9, onPrimary = 0xFF541D34, primaryContainer = 0xFF6F334A, onPrimaryContainer = 0xFFFFD9E3,
            secondary = 0xFFE2BDC7, secondaryContainer = 0xFF5A3F47, onSecondaryContainer = 0xFFF7DCE3,
            tertiary = 0xFFF0BC95, tertiaryContainer = 0xFF623F21, onTertiaryContainer = 0xFFFFDCC2,
            surface = 0xFF191113, container = 0xFF261D20, highest = 0xFF3C3235,
            onSurface = 0xFFEFDFE2, onSurfaceVariant = 0xFFD5C2C6, outline = 0xFF9E8C90, outlineVariant = 0xFF514347,
        ),
    ),
    ColorTheme.COCOA to PalettePair(
        light = Palette(
            primary = 0xFF7D5636, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFFFDCC4, onPrimaryContainer = 0xFF623F20,
            secondary = 0xFF6F5B4C, secondaryContainer = 0xFFF2DFD1, onSecondaryContainer = 0xFF51443A,
            tertiary = 0xFF56633F, tertiaryContainer = 0xFFD9E6C0, onTertiaryContainer = 0xFF3F4B2D,
            surface = 0xFFFFF8F5, container = 0xFFF7ECE6, highest = 0xFFECE0DA,
            onSurface = 0xFF211A15, onSurfaceVariant = 0xFF51443B, outline = 0xFF84746A, outlineVariant = 0xFFD6C3B7,
        ),
        dark = Palette(
            primary = 0xFFF0BC95, onPrimary = 0xFF48290C, primaryContainer = 0xFF623F20, onPrimaryContainer = 0xFFFFDCC4,
            secondary = 0xFFDDC2B0, secondaryContainer = 0xFF51443A, onSecondaryContainer = 0xFFF2DFD1,
            tertiary = 0xFFBDCAA5, tertiaryContainer = 0xFF3F4B2D, onTertiaryContainer = 0xFFD9E6C0,
            surface = 0xFF18120E, container = 0xFF251E19, highest = 0xFF3B332E,
            onSurface = 0xFFEDE0D9, onSurfaceVariant = 0xFFD6C3B7, outline = 0xFF9E8E83, outlineVariant = 0xFF51443B,
        ),
    ),
)
