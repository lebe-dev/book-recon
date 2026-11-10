package ru.tinyops.android.bookrecon.log

import java.util.Locale

/** Size for log lines, in English units: `512 B`, `1.5 MB`. */
fun formatBytes(bytes: Long): String {
    if (bytes < KIB) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / KIB
    var unit = 0
    while (value >= KIB && unit < units.lastIndex) {
        value /= KIB
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

private const val KIB = 1024
