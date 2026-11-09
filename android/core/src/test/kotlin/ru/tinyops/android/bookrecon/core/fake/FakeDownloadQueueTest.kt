package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeDownloadQueueTest {
    private val repository = FakeDownloadRepository()
    private val clock = FakeClock()
    private var nextId = 0

    private fun TestScope.queue(limit: Int = 2, seed: Duration = Duration.ZERO) = FakeDownloadQueue(
        repository = repository,
        scope = backgroundScope,
        clock = clock,
        maxConcurrent = { limit },
        seedFor = { seed },
        steps = 4,
        stepDelay = 100.milliseconds,
        newId = { "job-${++nextId}" },
    )

    private fun book(id: String = "11111") = DownloadRequest.FlibustaBook(id, "Мастер и Маргарита", "Михаил Булгаков", Format.EPUB)

    private fun audio(indexes: Set<Int> = setOf(0, 1)) = DownloadRequest.TorrentSelection(
        topicId = FakeData.AUDIO_TOPIC_CD,
        kind = MediaKind.AUDIO,
        title = "Булгаков - Мастер и Маргарита",
        torrentBytes = FakeData.torrentBytes(FakeData.AUDIO_TOPIC_CD),
        selectedIndexes = indexes,
        targetName = "Булгаков Михаил - Мастер и Маргарита",
    )

    /** Runs the simulated downloads to the end; background work is not awaited by advanceUntilIdle. */
    private fun TestScope.drain() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private suspend fun status(id: String) = repository.get(id)?.status

    @Test
    fun `runs at most the limit and keeps the rest queued in order`() = runTest {
        val queue = queue(limit = 2)
        val ids = List(4) { queue.enqueue(book("$it")) }
        runCurrent()

        assertEquals(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.DOWNLOADING), ids.take(2).map { status(it) })
        assertEquals(listOf(1, 2), ids.drop(2).map { repository.get(it)?.queuePosition })

        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf(DownloadStatus.COMPLETED, DownloadStatus.COMPLETED), ids.take(2).map { status(it) })
        assertEquals(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.DOWNLOADING), ids.drop(2).map { status(it) })

        drain()
        assertTrue(ids.all { status(it) == DownloadStatus.COMPLETED })
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `completed download gets paths and full progress`() = runTest {
        val queue = queue()
        val id = queue.enqueue(audio())
        drain()

        val done = repository.get(id)!!
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(listOf("/storage/emulated/0/Audiobooks/Булгаков Михаил - Мастер и Маргарита"), done.paths)
        assertEquals(2, done.files.size)
        assertEquals(done.size, done.progress.completed)
        assertEquals(clock.now(), done.completedAt)
    }

    @Test
    fun `cancelling a queued download renumbers the queue`() = runTest {
        val queue = queue(limit = 1)
        val ids = List(3) { queue.enqueue(book("$it")) }
        runCurrent()

        queue.cancel(ids[1])

        assertEquals(DownloadStatus.CANCELLED, status(ids[1]))
        assertNull(repository.get(ids[1])?.queuePosition)
        assertEquals(1, repository.get(ids[2])?.queuePosition)
    }

    @Test
    fun `cancelling a running download frees the slot`() = runTest {
        val queue = queue(limit = 1)
        val first = queue.enqueue(book("1"))
        val second = queue.enqueue(book("2"))
        advanceTimeBy(150)

        queue.cancel(first)
        runCurrent()

        assertEquals(DownloadStatus.CANCELLED, status(first))
        assertEquals(DownloadStatus.DOWNLOADING, status(second))
        drain()
        assertEquals(DownloadStatus.CANCELLED, status(first))
        assertEquals(DownloadStatus.COMPLETED, status(second))
    }

    @Test
    fun `failed download keeps the error and can be retried`() = runTest {
        val queue = queue()
        queue.failNextDownload = AppError.NoSeeders()
        val id = queue.enqueue(audio())
        drain()

        val failed = repository.get(id)!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals(AppError.NoSeeders(), failed.error)
        assertEquals(listOf(id), queue.observe().first().map { it.id })

        queue.retry(id)
        drain()
        assertEquals(DownloadStatus.COMPLETED, status(id))
        assertNull(repository.get(id)?.error)
    }

    @Test
    fun `dismiss removes a failed download`() = runTest {
        val queue = queue()
        queue.failNextDownload = AppError.Stalled()
        val id = queue.enqueue(audio())
        drain()

        queue.dismiss(id)

        assertNull(repository.get(id))
    }

    @Test
    fun `enqueue errors leave nothing recorded`() = runTest {
        val queue = queue()

        assertThrows<AppError.SelectionEmpty> { queue.enqueue(audio(indexes = emptySet())) }
        queue.behavior.failNext(AppError.RootNotConfigured(MediaKind.BOOK))
        assertThrows<AppError.RootNotConfigured> { queue.enqueue(book()) }

        assertTrue(repository.all.isEmpty())
    }

    @Test
    fun `torrent seeds when seeding is on and stops on request`() = runTest {
        val queue = queue(seed = 3.hours)
        val torrent = queue.enqueue(audio())
        val flibusta = queue.enqueue(book())
        drain()

        val seeding = repository.get(torrent)!!
        assertEquals(DownloadStatus.SEEDING, seeding.status)
        assertNotNull(seeding.seedUntil)
        assertEquals(DownloadStatus.COMPLETED, status(flibusta))

        queue.stopSeeding(torrent)
        assertEquals(DownloadStatus.COMPLETED, status(torrent))
        assertNull(repository.get(torrent)?.seedUntil)
    }
}
