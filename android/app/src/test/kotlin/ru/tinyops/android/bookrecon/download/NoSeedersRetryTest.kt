package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NoSeedersRetryTest {
    /** Queue whose list is set by the test; a retry puts the download back to QUEUED. */
    private class ListQueue : DownloadQueue {
        val list = MutableStateFlow<List<Download>>(emptyList())
        val retried = mutableListOf<String>()

        override suspend fun enqueue(request: DownloadRequest): String = error("not used")

        override suspend fun cancel(id: String) = Unit

        override suspend fun retry(id: String) {
            retried += id
            list.update { all -> all.map { if (it.id == id) it.copy(status = DownloadStatus.QUEUED, error = null) else it } }
        }

        override suspend fun stopSeeding(id: String) = Unit

        override suspend fun dismiss(id: String) = Unit

        override fun observe(): Flow<List<Download>> = list

        fun fail(id: String, error: AppError) {
            list.update { all -> all.filterNot { it.id == id } + download(id, DownloadStatus.FAILED, error) }
        }
    }

    private val queue = ListQueue()

    private fun TestScope.start(minutes: Int): FakeSettingsRepository {
        val settings = FakeSettingsRepository(Settings(noSeedersRetryMinutes = minutes))
        backgroundScope.launch { NoSeedersRetry(queue, settings).run() }
        runCurrent()
        return settings
    }

    @Test
    fun retriesNoSeedersAfterTheInterval() = runTest {
        start(minutes = 5)
        queue.fail("a", AppError.NoSeeders())
        runCurrent()

        advanceTimeBy(5.minutes - 1.seconds)
        assertEquals(emptyList<String>(), queue.retried)

        advanceTimeBy(2.seconds)
        assertEquals(listOf("a"), queue.retried)
    }

    @Test
    fun retriesAgainAfterTheNextFailure() = runTest {
        start(minutes = 5)
        queue.fail("a", AppError.NoSeeders())
        runCurrent()
        advanceTimeBy(5.minutes + 1.seconds)

        queue.fail("a", AppError.NoSeeders())
        runCurrent()
        advanceTimeBy(5.minutes + 1.seconds)

        assertEquals(listOf("a", "a"), queue.retried)
    }

    @Test
    fun retriesStalledDownloads() = runTest {
        start(minutes = 5)
        queue.fail("a", AppError.Stalled())
        runCurrent()

        advanceTimeBy(5.minutes + 1.seconds)

        assertEquals(listOf("a"), queue.retried)
    }

    @Test
    fun leavesOtherFailuresAlone() = runTest {
        start(minutes = 5)
        queue.fail("a", AppError.Network())
        runCurrent()

        advanceTimeBy(30.minutes)

        assertEquals(emptyList<String>(), queue.retried)
    }

    @Test
    fun doesNothingWhenTurnedOff() = runTest {
        start(minutes = 0)
        queue.fail("a", AppError.NoSeeders())
        runCurrent()

        advanceTimeBy(120.minutes)

        assertEquals(emptyList<String>(), queue.retried)
    }

    @Test
    fun dropsTheTimerOfADownloadRetriedByHand() = runTest {
        start(minutes = 5)
        queue.fail("a", AppError.NoSeeders())
        runCurrent()
        advanceTimeBy(1.minutes)
        queue.retry("a")
        queue.retried.clear()
        runCurrent()

        advanceTimeBy(10.minutes)

        assertEquals(emptyList<String>(), queue.retried)
    }

    @Test
    fun newIntervalAppliesRightAway() = runTest {
        val settings = start(minutes = 30)
        queue.fail("a", AppError.NoSeeders())
        runCurrent()

        settings.update { it.copy(noSeedersRetryMinutes = 5) }
        runCurrent()
        advanceTimeBy(5.minutes + 1.seconds)

        assertEquals(listOf("a"), queue.retried)
    }

    private companion object {
        fun download(id: String, status: DownloadStatus, error: AppError?) = Download(
            id = id,
            kind = MediaKind.AUDIO,
            source = Source.RUTRACKER,
            sourceId = "1",
            title = "Book",
            author = "",
            status = status,
            error = error,
            createdAt = Instant.parse("2026-11-01T09:00:00Z"),
        )
    }
}
