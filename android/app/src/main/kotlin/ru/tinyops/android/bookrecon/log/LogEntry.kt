package ru.tinyops.android.bookrecon.log

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Severity of a log line. The app log has two switches: INFO covers [INFO], [WARN] and
 * [ERROR] (what happened), DEBUG covers [DEBUG] (how it happened).
 */
enum class LogLevel(val letter: Char) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/** One line of the app log. [message] may contain line breaks. */
data class LogEntry(val time: Instant, val level: LogLevel, val tag: String, val message: String)

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private const val TIME_LENGTH = 23

/** The single-line text form: `2026-11-10 09:30:15.250 I/Queue: started`. Line breaks are escaped. */
fun LogEntry.format(zone: ZoneId): String =
    "${timeFormat.format(time.atZone(zone))} ${level.letter}/$tag: ${message.escapeLineBreaks()}"

/** Reads a line written by [format]; null for anything else. */
fun parseLogEntry(line: String, zone: ZoneId): LogEntry? {
    if (line.length < TIME_LENGTH + 5) return null
    val time = try {
        LocalDateTime.parse(line.substring(0, TIME_LENGTH), timeFormat).atZone(zone).toInstant()
    } catch (_: java.time.format.DateTimeParseException) {
        return null
    }
    if (line[TIME_LENGTH] != ' ' || line[TIME_LENGTH + 2] != '/') return null
    val level = LogLevel.entries.firstOrNull { it.letter == line[TIME_LENGTH + 1] } ?: return null
    val rest = line.substring(TIME_LENGTH + 3)
    val separator = rest.indexOf(": ")
    if (separator < 0) return null
    return LogEntry(time, level, rest.substring(0, separator), rest.substring(separator + 2).unescapeLineBreaks())
}

private fun String.escapeLineBreaks(): String =
    replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")

private fun String.unescapeLineBreaks(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\\' && i + 1 < length) {
            out.append(if (this[i + 1] == 'n') '\n' else this[i + 1])
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
