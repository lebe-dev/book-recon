package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootResumeTest {
    private class Calls {
        var downloadSchedules = 0
        var seedingSchedules = 0
        val log = mutableListOf<String>()
    }

    private fun resume(
        calls: Calls,
        hasWork: Boolean = false,
        hasSeeding: Boolean = false,
        jobPending: Boolean = false,
        scheduleAllowed: Boolean = false,
    ) = BootResume(
        hasWork = { hasWork },
        hasSeeding = { hasSeeding },
        downloadJobPending = { jobPending },
        scheduleDownloads = {
            calls.downloadSchedules++
            scheduleAllowed
        },
        scheduleSeeding = { calls.seedingSchedules++ },
        log = { calls.log += it },
    )

    @Test
    fun nothingToDoSchedulesNothing() = runBlocking {
        val calls = Calls()

        val outcome = resume(calls).run()

        assertEquals(BootOutcome(DownloadsOnBoot.NOTHING, seeding = false), outcome)
        assertEquals(0, calls.downloadSchedules)
        assertEquals(0, calls.seedingSchedules)
    }

    @Test
    fun persistedDownloadJobIsKept() = runBlocking {
        val calls = Calls()

        val outcome = resume(calls, hasWork = true, jobPending = true).run()

        assertEquals(DownloadsOnBoot.JOB_KEPT, outcome.downloads)
        // Rescheduling would replace the persisted job, and from the background it fails anyway.
        assertEquals(0, calls.downloadSchedules)
    }

    @Test
    fun lostDownloadJobIsScheduledWhenTheSystemAllows() = runBlocking {
        val calls = Calls()

        val outcome = resume(calls, hasWork = true, scheduleAllowed = true).run()

        assertEquals(DownloadsOnBoot.SCHEDULED, outcome.downloads)
        assertEquals(1, calls.downloadSchedules)
    }

    @Test
    fun refusedDownloadJobWaitsForTheApp() = runBlocking {
        val calls = Calls()

        val outcome = resume(calls, hasWork = true, scheduleAllowed = false).run()

        assertEquals(DownloadsOnBoot.WAITS_FOR_APP, outcome.downloads)
        assertEquals(1, calls.downloadSchedules)
        assertTrue(calls.log.any { "opened" in it })
    }

    @Test
    fun seedingJobIsScheduled() = runBlocking {
        val calls = Calls()

        val outcome = resume(calls, hasSeeding = true).run()

        assertTrue(outcome.seeding)
        assertEquals(DownloadsOnBoot.NOTHING, outcome.downloads)
        assertEquals(1, calls.seedingSchedules)
    }

    @Test
    fun downloadsAndSeedingResumeTogether() = runBlocking {
        val calls = Calls()

        val outcome = resume(calls, hasWork = true, hasSeeding = true, jobPending = true).run()

        assertEquals(BootOutcome(DownloadsOnBoot.JOB_KEPT, seeding = true), outcome)
        assertEquals(1, calls.seedingSchedules)
    }

    @Test
    fun seedingFailureDoesNotStopDownloads() = runBlocking {
        val calls = Calls()
        val resume = BootResume(
            hasWork = { true },
            hasSeeding = { error("database is not ready") },
            downloadJobPending = { false },
            scheduleDownloads = {
                calls.downloadSchedules++
                true
            },
            scheduleSeeding = { calls.seedingSchedules++ },
            log = { calls.log += it },
        )

        val outcome = resume.run()

        assertEquals(DownloadsOnBoot.SCHEDULED, outcome.downloads)
        assertFalse(outcome.seeding)
        assertEquals(0, calls.seedingSchedules)
    }
}
