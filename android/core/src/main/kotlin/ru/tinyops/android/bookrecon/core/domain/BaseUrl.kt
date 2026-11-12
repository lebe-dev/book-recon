package ru.tinyops.android.bookrecon.core.domain

/**
 * Cleans up an address typed by hand: trims whitespace and trailing slashes, adds `https://`
 * when no scheme is given and lowercases the scheme and the host, so "flibusta.site" and
 * "HTTPS://Flibusta.Site/" both become "https://flibusta.site". The path keeps its case.
 * A blank input stays empty.
 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val schemeEnd = withScheme.indexOf("://") + 3
    val hostEnd = withScheme.indexOf('/', schemeEnd).let { if (it < 0) withScheme.length else it }
    return withScheme.substring(0, hostEnd).lowercase() + withScheme.substring(hostEnd)
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
