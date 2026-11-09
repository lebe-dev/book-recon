package ru.tinyops.android.bookrecon.torrent

import ru.tinyops.android.bookrecon.core.domain.AppError
import kotlin.time.Duration

/**
 * Turns periodic samples of downloaded bytes into a speed and a stall verdict, the same way
 * the bot does: no growth for [stallTimeout] fails the download, with NoSeeders when no data
 * ever arrived and no peer is connected, and Stalled otherwise.
 */
internal class ProgressTracker(private val stallTimeout: Duration, startNanos: Long) {
    class Sample(val speed: Long, val failure: AppError?)

    private var lastCompleted = -1L
    private var lastSample = startNanos
    private var lastChange = startNanos
    private var gotAnyData = false

    fun sample(completed: Long, peers: Int, nowNanos: Long): Sample {
        var speed = 0L
        if (lastCompleted >= 0 && nowNanos > lastSample) {
            speed = (completed - lastCompleted).coerceAtLeast(0) * 1_000_000_000L / (nowNanos - lastSample)
        }
        if (completed > lastCompleted) {
            if (lastCompleted >= 0) gotAnyData = true
            lastChange = nowNanos
        }
        lastCompleted = completed
        lastSample = nowNanos

        if (nowNanos - lastChange <= stallTimeout.inWholeNanoseconds) return Sample(speed, null)
        val failure = if (!gotAnyData && peers == 0) AppError.NoSeeders() else AppError.Stalled()
        return Sample(speed, failure)
    }
}
