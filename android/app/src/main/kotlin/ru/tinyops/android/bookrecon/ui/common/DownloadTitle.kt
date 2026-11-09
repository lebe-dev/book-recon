package ru.tinyops.android.bookrecon.ui.common

import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.MediaKind

/** The audiobook folder name or the book title, prefixed with the author when known. */
fun Download.displayTitle(): String {
    val name = targetName?.takeIf { kind == MediaKind.AUDIO && it.isNotBlank() } ?: title
    if (author.isBlank()) return name
    return "$author — $name"
}
