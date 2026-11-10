package ru.tinyops.android.bookrecon.torrent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProgressTrackerTest {
    private val second = 1.seconds.inWholeNanoseconds

    @Test
    fun `speed is the growth of selected bytes per second`() {
        val tracker = ProgressTracker(10.minutes, startNanos = 0)

        assertEquals(0, tracker.sample(completed = 0, peers = 1, nowNanos = 0).speed)
        assertEquals(500, tracker.sample(completed = 1000, peers = 1, nowNanos = 2 * second).speed)
        assertEquals(0, tracker.sample(completed = 1000, peers = 1, nowNanos = 4 * second).speed)
    }

    @Test
    fun `no peers and no data for the stall timeout means no seeders`() {
        val tracker = ProgressTracker(10.minutes, startNanos = 0)

        assertNull(tracker.sample(0, peers = 0, nowNanos = 0).failure)
        assertNull(tracker.sample(0, peers = 0, nowNanos = 10.minutes.inWholeNanoseconds).failure)
        val failure = tracker.sample(0, peers = 0, nowNanos = 10.minutes.inWholeNanoseconds + second).failure

        assertTrue(failure is AppError.NoSeeders, "$failure")
    }

    @Test
    fun `peers without data for the stall timeout is a stall`() {
        val tracker = ProgressTracker(10.minutes, startNanos = 0)

        tracker.sample(0, peers = 3, nowNanos = 0)
        val failure = tracker.sample(0, peers = 3, nowNanos = 11.minutes.inWholeNanoseconds).failure

        assertTrue(failure is AppError.Stalled, "$failure")
    }

    @Test
    fun `data that stops arriving is a stall even without peers`() {
        val tracker = ProgressTracker(10.minutes, startNanos = 0)

        tracker.sample(0, peers = 2, nowNanos = 0)
        tracker.sample(500, peers = 2, nowNanos = second)
        val failure = tracker.sample(500, peers = 0, nowNanos = second + 11.minutes.inWholeNanoseconds).failure

        assertTrue(failure is AppError.Stalled, "$failure")
    }

    @Test
    fun `progress resets the stall timer`() {
        val tracker = ProgressTracker(10.minutes, startNanos = 0)

        tracker.sample(0, peers = 1, nowNanos = 0)
        assertNull(tracker.sample(10, peers = 1, nowNanos = 9.minutes.inWholeNanoseconds).failure)
        assertNull(tracker.sample(20, peers = 1, nowNanos = 18.minutes.inWholeNanoseconds).failure)
        assertNull(tracker.sample(20, peers = 1, nowNanos = 27.minutes.inWholeNanoseconds).failure)
        assertTrue(tracker.sample(20, peers = 1, nowNanos = 29.minutes.inWholeNanoseconds).failure is AppError.Stalled)
    }

    @Test
    fun `resumed data at the first sample is not counted as new data`() {
        val tracker = ProgressTracker(10.minutes, startNanos = 0)

        tracker.sample(700, peers = 0, nowNanos = 0)
        val failure = tracker.sample(700, peers = 0, nowNanos = 11.minutes.inWholeNanoseconds).failure

        assertTrue(failure is AppError.NoSeeders, "$failure")
    }
}
