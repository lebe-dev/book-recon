package ru.tinyops.android.bookrecon.core.naming

private val squareBlock = Regex("\\[[^\\[\\]]*]")
private val roundBlock = Regex("\\(([^()]*)\\)")
private val year = Regex("(?<!\\d)(?:1[89]|20)\\d{2}(?!\\d)")

// Android's ICU regex rejects the (?U) flag, while on the JVM \w and \b are ASCII-only without
// it, so word characters and boundaries are spelled out with Unicode classes.
private const val WORD_CHAR = "[\\p{L}\\p{N}_]"
private val technicalWord = Regex(
    "(?<!$WORD_CHAR)(?:kbps|mp3|m4b|m4a|aac|flac|ogg|opus|fb2|epub|mobi|pdf|djvu|rtf|txt|doc|rus|eng|ebook|" +
        "аудиокниг$WORD_CHAR*|аудиоспектакл$WORD_CHAR*|чита$WORD_CHAR*|чтец$WORD_CHAR*|исполн$WORD_CHAR*)" +
        "(?!$WORD_CHAR)",
    RegexOption.IGNORE_CASE,
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
