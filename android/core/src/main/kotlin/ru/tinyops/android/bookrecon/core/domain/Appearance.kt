package ru.tinyops.android.bookrecon.core.domain

/** Pastel color theme of the app; each has a light and a dark variant. */
enum class ColorTheme {
    /** Blue, the default. */
    BLUEBERRY,

    /** Green. */
    PISTACHIO,

    /** Red. */
    APPLE,

    /** Light blue. */
    BILBERRY,

    /** Pink. */
    MARSHMALLOW,

    /** Brown. */
    COCOA,
}

/** Whether the app is light or dark. */
enum class DarkMode {
    /** Follows the system setting, the default. */
    SYSTEM,
    LIGHT,
    DARK,
}
