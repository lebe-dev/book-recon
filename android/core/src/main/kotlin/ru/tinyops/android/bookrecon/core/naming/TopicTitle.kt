package ru.tinyops.android.bookrecon.core.naming

private val squareBlock = Regex("\\[[^\\[\\]]*]")
private val roundBlock = Regex("\\(([^()]*)\\)")
private val year = Regex("(?<!\\d)(?:1[89]|20)\\d{2}(?!\\d)")
private val technicalWord = Regex(
    "(?iU)\\b(?:kbps|mp3|m4b|m4a|aac|flac|ogg|opus|fb2|epub|mobi|pdf|djvu|rtf|txt|doc|rus|eng|ebook|" +
        "аудиокниг\\w*|аудиоспектакл\\w*|чита\\w*|чтец\\w*|исполн\\w*)\\b",
)
private val separator = Regex("\\s+[|/–—]\\s+")
private val spaceRun = Regex("\\s+")
private const val EDGE_PUNCTUATION = " -–—|/,;:"

/**
 * Turns a RuTracker topic title into "Author - Title" for a folder name: drops every
 * `[...]` block, drops `(...)` blocks with technical data (a year, a bitrate, a file format,
 * the narrator), unifies the author/title separator to " - " and collapses spaces. Plain
 * parentheses such as a series name stay. Falls back to the trimmed original when nothing
 * is left. The result is not sanitized for the file system.
 */
fun cleanTopicTitle(title: String): String {
    val cleaned = title
        .replace(squareBlock, " ")
        .replace(roundBlock) { if (isTechnical(it.groupValues[1])) " " else it.value }
        .replace(separator, " - ")
        .replace(spaceRun, " ")
        .trim { it in EDGE_PUNCTUATION }
    return cleaned.ifEmpty { title.replace(spaceRun, " ").trim() }
}

private fun isTechnical(text: String): Boolean = year.containsMatchIn(text) || technicalWord.containsMatchIn(text)
