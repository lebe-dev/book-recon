package ru.tinyops.android.bookrecon.core.naming

/** Longest file or folder name produced, in UTF-16 units; well under the FAT/ext4 limits. */
const val MAX_FILE_NAME_LENGTH = 120

/** Name used when nothing is left after cleaning. */
const val UNTITLED = "Без названия"

/** E-book file extensions, lowercase, without the leading dot. */
val ebookExtensions: List<String> = listOf("epub", "fb2", "fb2.zip", "mobi", "pdf", "djvu")

/** Audiobook file extensions, the same set as the bot's default audio filter. */
val audioExtensions: List<String> = listOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma")

private val compoundExtensions = listOf("fb2.zip")

// Characters FAT and exFAT reject, plus ASCII control characters.
private val invalidChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val spaces = Regex("[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")

/**
 * Makes [name] safe as a single file or folder name on FAT/exFAT: invalid and control
 * characters become spaces, runs of spaces collapse, dots and spaces at both ends are
 * removed, and the result is cut to [maxLength] UTF-16 units without splitting a surrogate
 * pair. Returns [UNTITLED] when nothing is left.
 */
fun sanitizeFileName(name: String, maxLength: Int = MAX_FILE_NAME_LENGTH): String {
    val cleaned = name.replace(invalidChars, " ").replace(spaces, " ").trimEdges()
    return truncate(cleaned, maxLength).trimEdges().ifEmpty { UNTITLED }
}

/**
 * Like [sanitizeFileName], but cleans the stem and the extension apart, so the extension
 * survives truncation and a stray character before the dot does not leave a space.
 */
fun sanitizeFileNameKeepingExtension(name: String, maxLength: Int = MAX_FILE_NAME_LENGTH): String {
    val (stem, extension) = splitExtension(name)
    if (extension.isEmpty()) return sanitizeFileName(name, maxLength)
    val cleanExtension = extension.replace(invalidChars, "").replace(spaces, "")
    return sanitizeFileName(stem, (maxLength - cleanExtension.length - 1).coerceAtLeast(1)) + "." + cleanExtension
}

/**
 * Splits a file name into stem and lowercase extension; `fb2.zip` counts as one extension.
 * A name without a dot, a leading-dot name such as `.nomedia` and a name ending with a dot
 * have an empty extension.
 */
fun splitExtension(name: String): Pair<String, String> {
    val lower = name.lowercase()
    compoundExtensions.firstOrNull { lower.endsWith(".$it") && name.length > it.length + 1 }?.let {
        return name.dropLast(it.length + 1) to it
    }
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return name to ""
    return name.substring(0, dot) to lower.substring(dot + 1)
}

/** True when the last segment of a slash-separated [path] has an audio extension. */
fun isAudioFile(path: String): Boolean = extensionOf(path) in audioExtensions

/** True when the last segment of a slash-separated [path] has an e-book extension. */
fun isEbookFile(path: String): Boolean = extensionOf(path) in ebookExtensions

private fun extensionOf(path: String): String = splitExtension(path.substringAfterLast('/')).second

private fun String.trimEdges(): String = trim { it == '.' || it == ' ' }

private fun truncate(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    var end = maxLength.coerceAtLeast(0)
    if (end > 0 && text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end)
}
