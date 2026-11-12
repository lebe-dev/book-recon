package ru.tinyops.android.bookrecon.core.domain

/**
 * Cleans up an address typed by hand: trims spaces and trailing slashes and adds `https://`
 * when no scheme is given, so "flibusta.site" becomes "https://flibusta.site".
 * A blank input stays empty.
 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    if (SCHEME.containsMatchIn(trimmed)) return trimmed
    return "https://$trimmed"
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
