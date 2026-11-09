package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.delay
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * Latency and failures shared by a fake: every call waits [delay] and then throws a queued
 * or a permanent error, if any. Safe to change from tests while the fake is in use.
 */
class FakeBehavior(delay: Duration = Duration.ZERO) {
    @Volatile
    var delay: Duration = delay

    /** Thrown by every call until set back to null. */
    @Volatile
    var error: AppError? = null

    private val queued = ArrayDeque<AppError>()

    /** Makes the next call throw [error]; queued errors are used in order, before [error]. */
    fun failNext(error: AppError) {
        synchronized(queued) { queued.addLast(error) }
    }

    /** Waits [delay] and throws the pending error, if any. Called at the start of each call. */
    suspend fun step() {
        if (delay > Duration.ZERO) delay(delay)
        val pending = synchronized(queued) { queued.removeFirstOrNull() } ?: error
        if (pending != null) throw pending
    }

    companion object {
        /** Latency that makes loading states visible when the app runs on fakes. */
        val UI_DELAY: Duration = 600.milliseconds
    }
}

/** A clock that moves only when told to. */
class FakeClock(start: Instant = Instant.parse("2026-11-01T09:00:00Z")) : Clock {
    @Volatile
    var current: Instant = start

    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration.toJavaDuration())
    }
}
