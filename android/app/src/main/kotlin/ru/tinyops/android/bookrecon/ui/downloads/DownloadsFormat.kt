package ru.tinyops.android.bookrecon.ui.downloads

import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** When seeding ends, relative to the current day in the user's time zone. */
sealed interface SeedUntil {
    /** Ends today (or is already overdue) at [time], "HH:mm". */
    data class Today(val time: String) : SeedUntil

    /** Ends tomorrow at [time]. */
    data class Tomorrow(val time: String) : SeedUntil

    /** Ends later: [date] is "dd.MM", [time] is "HH:mm". */
    data class Later(val date: String, val time: String) : SeedUntil
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

/** Describes the seeding deadline [until] as seen at [now] in [zone]. */
fun seedUntil(until: Instant, now: Instant, zone: ZoneId): SeedUntil {
    val end = until.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = end.format(timeFormat)
    val day = end.toLocalDate()
    if (!day.isAfter(today)) return SeedUntil.Today(time)
    if (day == today.plusDays(1)) return SeedUntil.Tomorrow(time)
    return SeedUntil.Later(end.format(dateFormat), time)
}

/** True when the user fixes the cause in settings: file access, a root folder or the RuTracker login. */
fun AppError.fixableInSettings(): Boolean = when (code) {
    ErrorCode.STORAGE_NOT_GRANTED,
    ErrorCode.ROOT_NOT_CONFIGURED,
    ErrorCode.RUTRACKER_AUTH_REQUIRED,
    ErrorCode.RUTRACKER_LOGIN_FAILED,
    -> true
    else -> false
}
