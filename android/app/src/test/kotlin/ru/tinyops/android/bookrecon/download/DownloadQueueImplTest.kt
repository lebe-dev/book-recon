package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeClock
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeFileStorage
import ru.tinyops.android.bookrecon.core.fake.FakeFlibustaSource
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.fake.FakeTorrentEngine
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueImplTest {
    @TempDir
    lateinit var dir: File

    private val clock = FakeClock()
    private val repository = FakeDownloadRepository()
    private val settings = FakeSettingsRepository(Settings(maxConcurrent = 2))
    private val flibusta = FakeFlibustaSource(behavior = FakeBehavior(BOOK_TIME))
    private var torrents = FakeTorrentEngine(steps = 5, stepDelay = 1.seconds)
    private lateinit var storage: FakeFileStorage
    private var ids = 0
    private var workRequests = 0
    private val reported = mutableListOf<Throwable>()

    @BeforeEach
    fun setUp() {
        storage = FakeFileStorage(dir)
    }

    private fun queue(scope: CoroutineScope, seeding: SeedingHandoff = SeedingHandoff.None) = DownloadQueueImpl(
        repository = repository,
        settings = settings,
        storage = storage,
        flibusta = flibusta,
        torrents = torrents,
        torrentStore = File(dir, "torrents"),
        scope = scope,
        clock = clock,
        seeding = seeding,
        onWork = { workRequests++ },
        report = { reported += it },
        newId = { "job-${++ids}" },
    )

    private fun book(id: String, format: Format = Format.EPUB): DownloadRequest.FlibustaBook {
        val details = FakeData.flibustaBooks.first { it.id == id }
        return DownloadRequest.FlibustaBook(details.id, details.title, details.author, format)
    }

    private fun torrent(topicId: String, kind: MediaKind, indexes: Set<Int>? = null, targetName: String = "Булгаков - Мастер и Маргарита") =
        DownloadRequest.TorrentSelection(
            topicId = topicId,
            kind = kind,
            title = "Раздача $topicId",
            torrentBytes = FakeData.torrentBytes(topicId),
            selectedIndexes = indexes ?: FakeData.torrentInfo(topicId).files.map { it.index }.toSet(),
            targetName = targetName,
        )

    private suspend fun status(id: String): DownloadStatus? = repository.get(id)?.status

    private suspend fun get(id: String): Download = repository.get(id)!!

    private fun TestScope.advance(seconds: Int) {
        advanceTimeBy(seconds.seconds)
        runCurrent()
    }

    @Test
    fun runsAtMostMaxConcurrentDownloads() = runTest {
        val queue = queue(backgroundScope)
        queue.start()
        val a = queue.enqueue(book("11111"))
        val b = queue.enqueue(book("22222"))
        val c = queue.enqueue(book("33333"))
        runCurrent()

        assertEquals(DownloadStatus.DOWNLOADING, status(a))
        assertEquals(DownloadStatus.DOWNLOADING, status(b))
        assertEquals(DownloadStatus.QUEUED, status(c))
        assertEquals(1, get(c).queuePosition)

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(a))
        assertEquals(DownloadStatus.COMPLETED, status(b))
        assertEquals(DownloadStatus.DOWNLOADING, status(c))

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(c))
        val books = File(dir, "Books").list()!!.filter { !it.startsWith(".") }.sorted()
        assertEquals(listOf("Мастер и Маргарита.epub", "Пикник на обочине.epub", "Преступление и наказание.epub"), books)
        assertEquals(listOf("Мастер и Маргарита.epub"), get(a).paths)
        assertTrue(get(a).size > 0)
        assertEquals(clock.now(), get(a).completedAt)
        assertEquals(3, workRequests)
    }

    @Test
    fun startsQueuedDownloadsInOrderAndRenumbersThem() = runTest {
        settings.update { it.copy(maxConcurrent = 1) }
        val queue = queue(backgroundScope)
        queue.start()
        val a = queue.enqueue(book("11111"))
        val b = queue.enqueue(book("22222"))
        val c = queue.enqueue(book("33333"))
        runCurrent()

        assertEquals(listOf(null, 1, 2), listOf(a, b, c).map { get(it).queuePosition })
        assertEquals(listOf(a, b, c), queue.observe().first().map { it.id })

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.DOWNLOADING, status(b))
        assertEquals(1, get(c).queuePosition)
    }

    @Test
    fun raisedLimitStartsWaitingDownloads() = runTest {
        settings.update { it.copy(maxConcurrent = 1) }
        val queue = queue(backgroundScope)
        queue.start()
        val a = queue.enqueue(book("11111"))
        val b = queue.enqueue(book("22222"))
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, status(b))

        settings.update { it.copy(maxConcurrent = 2) }
        queue.reschedule()
        runCurrent()

        assertEquals(DownloadStatus.DOWNLOADING, status(a))
        assertEquals(DownloadStatus.DOWNLOADING, status(b))
        assertNull(get(b).queuePosition)
    }

    @Test
    fun rescheduleBeforeStartRunsNothing() = runTest {
        val queue = queue(backgroundScope)
        val a = queue.enqueue(book("11111"))
        queue.reschedule()
        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.QUEUED, status(a))
    }

    @Test
    fun nothingRunsUntilStarted() = runTest {
        val queue = queue(backgroundScope)
        val a = queue.enqueue(book("11111"))
        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.QUEUED, status(a))
        assertTrue(queue.hasWork.first())

        queue.start()
        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(a))
        assertFalse(queue.hasWork.first())
    }

    @Test
    fun cancelsQueuedDownload() = runTest {
        settings.update { it.copy(maxConcurrent = 1) }
        val queue = queue(backgroundScope)
        queue.start()
        val a = queue.enqueue(book("11111"))
        val b = queue.enqueue(book("22222"))
        val c = queue.enqueue(book("33333"))
        runCurrent()

        queue.cancel(b)
        assertEquals(DownloadStatus.CANCELLED, status(b))
        assertNull(get(b).queuePosition)
        assertEquals(1, get(c).queuePosition)

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(a))
        assertEquals(DownloadStatus.DOWNLOADING, status(c))
    }

    @Test
    fun cancelsRunningTorrentAndDeletesStaging() = runTest {
        settings.update { it.copy(maxConcurrent = 1) }
        val queue = queue(backgroundScope)
        queue.start()
        val audio = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO))
        val next = queue.enqueue(book("11111"))
        advance(2)
        assertEquals(DownloadStatus.DOWNLOADING, status(audio))
        val staging = File(dir, "staging/$audio")
        assertTrue(staging.exists())
        assertTrue(queue.observe().first().first { it.id == audio }.progress.completed > 0)

        queue.cancel(audio)
        runCurrent()
        assertEquals(DownloadStatus.CANCELLED, status(audio))
        assertFalse(staging.exists())
        assertNull(torrents.state(audio))
        assertEquals(DownloadStatus.DOWNLOADING, status(next))

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(next))
    }

    @Test
    fun sourceFailureMarksDownloadFailedAndRunsTheNext() = runTest {
        settings.update { it.copy(maxConcurrent = 1) }
        val events = mutableListOf<DownloadEvent>()
        val queue = queue(backgroundScope)
        backgroundScope.launch { queue.events.collect { events += it } }
        queue.start()
        flibusta.behavior.failNext(AppError.NotFound())
        val a = queue.enqueue(book("11111"))
        val b = queue.enqueue(book("22222"))

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.FAILED, status(a))
        assertEquals(AppError.NotFound(), get(a).error)
        assertFalse(File(dir, "staging/$a").exists())
        assertEquals(DownloadStatus.DOWNLOADING, status(b))

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(b))
        assertEquals(listOf(a, b), events.map { it.download.id })
        assertTrue(events[0] is DownloadEvent.Failed)
        assertTrue(events[1] is DownloadEvent.Completed)
        assertTrue(reported.isEmpty(), "expected failures are not reported")
    }

    @Test
    fun unexpectedFailureIsReported() = runTest {
        val queue = queue(backgroundScope)
        queue.start()
        val unexpected = AppError.Unknown("boom", IllegalStateException("boom"))
        flibusta.behavior.failNext(unexpected)
        val a = queue.enqueue(book("11111"))

        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.FAILED, status(a))
        assertEquals(listOf<Throwable>(unexpected), reported)
    }

    @Test
    fun torrentFailureDeletesStagingAndCanBeRetried() = runTest {
        val queue = queue(backgroundScope)
        queue.start()
        torrents.failAtStep = AppError.NoSeeders()
        val id = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO, targetName = "Пикник"))

        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.FAILED, status(id))
        assertEquals(AppError.NoSeeders(), get(id).error)
        assertFalse(File(dir, "staging/$id").exists())

        queue.retry(id)
        runCurrent()
        assertEquals(DownloadStatus.DOWNLOADING, status(id))
        assertNull(get(id).error)

        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(id))
        assertEquals(4, File(dir, "Audiobooks/Пикник").list()!!.size)
    }

    @Test
    fun failsRightAwayWithoutRootOrAccess() = runTest {
        val queue = queue(backgroundScope)
        storage.booksRoot = null
        assertThrows<AppError.RootNotConfigured> { queue.enqueue(book("11111")) }

        storage.hasAccess = false
        assertThrows<AppError.StorageNotGranted> {
            queue.enqueue(torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO))
        }
        assertTrue(repository.all.isEmpty())
        assertEquals(0, workRequests)
    }

    @Test
    fun rejectsEmptySelectionAndDuplicates() = runTest {
        val queue = queue(backgroundScope)
        assertThrows<AppError.SelectionEmpty> {
            queue.enqueue(torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO, indexes = emptySet()))
        }
        assertThrows<AppError.SelectionEmpty> {
            queue.enqueue(torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO, indexes = setOf(99)))
        }
        queue.enqueue(book("11111"))
        assertThrows<AppError.AlreadyDownloading> { queue.enqueue(book("11111")) }
        queue.enqueue(book("11111", Format.FB2))
        assertEquals(2, repository.all.size)
    }

    @Test
    fun placesAudiobookFilesWithoutTheTorrentRootFolder() = runTest {
        File(dir, "Audiobooks/Булгаков - Мастер и Маргарита").mkdirs()
        val queue = queue(backgroundScope)
        queue.start()
        val id = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO, indexes = setOf(0, 1, 3)))

        advance(TORRENT_SECONDS)
        val target = File(dir, "Audiobooks/Булгаков - Мастер и Маргарита (2)")
        assertEquals(DownloadStatus.COMPLETED, status(id))
        assertEquals(listOf("Булгаков - Мастер и Маргарита (2)"), get(id).paths)
        assertTrue(File(target, "CD1/01 Глава 1. Никогда не разговаривайте с неизвестными.mp3").isFile)
        assertTrue(File(target, "CD1/02 Глава 2. Понтий Пилат.mp3").isFile)
        assertTrue(File(target, "CD2/01 Глава 13. Явление героя.mp3").isFile)
        assertFalse(File(dir, "staging/$id").exists())
        assertFalse(File(dir, "torrents/$id.torrent").exists())
        assertNull(torrents.state(id))
    }

    @Test
    fun placesTorrentEbooksFlatAndAvoidsNameClashes() = runTest {
        File(dir, "Books").mkdirs()
        File(dir, "Books/Достоевский - Преступление и наказание.epub").writeText("old")
        val queue = queue(backgroundScope)
        queue.start()
        val id = queue.enqueue(torrent(FakeData.BOOK_TOPIC_FORMATS, MediaKind.BOOK, indexes = setOf(0, 1)))

        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(id))
        assertEquals(
            listOf(
                "Достоевский - Преступление и наказание (2).epub",
                "Достоевский - Преступление и наказание.fb2",
            ),
            get(id).paths,
        )
        assertEquals("old", File(dir, "Books/Достоевский - Преступление и наказание.epub").readText())
    }

    @Test
    fun flibustaBookTakesAuthorOnNameClash() = runTest {
        File(dir, "Books").mkdirs()
        File(dir, "Books/Мастер и Маргарита.epub").writeText("old")
        val queue = queue(backgroundScope)
        queue.start()
        val id = queue.enqueue(book("11111"))

        advance(BOOK_SECONDS)
        assertEquals(listOf("Мастер и Маргарита (Михаил Булгаков).epub"), get(id).paths)
    }

    @Test
    fun recoversAfterProcessRestart() = runTest {
        val process = CoroutineScope(backgroundScope.coroutineContext + Job())
        val before = queue(process)
        before.start()
        val audio = before.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO, targetName = "Пикник"))
        clock.advance(1.seconds)
        val book = before.enqueue(book("11111"))
        clock.advance(1.seconds)
        val waiting = before.enqueue(book("22222"))
        advance(2)
        process.cancel()
        runCurrent()
        assertEquals(DownloadStatus.DOWNLOADING, status(audio))
        assertEquals(DownloadStatus.DOWNLOADING, status(book))
        assertEquals(DownloadStatus.QUEUED, status(waiting))

        val after = queue(backgroundScope)
        assertTrue(after.recover())
        assertEquals(DownloadStatus.QUEUED, status(audio))
        assertEquals(DownloadStatus.QUEUED, status(book))
        assertEquals(listOf(1, 2, 3), listOf(audio, book, waiting).map { get(it).queuePosition })

        after.start()
        runCurrent()
        assertEquals(DownloadStatus.DOWNLOADING, status(audio))
        assertEquals(DownloadStatus.DOWNLOADING, status(book))
        assertEquals(DownloadStatus.QUEUED, status(waiting))

        advance(BOOK_SECONDS)
        advance(BOOK_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(audio))
        assertEquals(DownloadStatus.COMPLETED, status(book))
        assertEquals(DownloadStatus.COMPLETED, status(waiting))
        assertEquals(4, File(dir, "Audiobooks/Пикник").list()!!.size)
    }

    @Test
    fun startsTorrentOverWhenResumeDataIsGone() = runTest {
        val process = CoroutineScope(backgroundScope.coroutineContext + Job())
        val before = queue(process)
        before.start()
        val audio = before.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO, targetName = "Пикник"))
        advance(2)
        process.cancel()
        runCurrent()

        torrents = FakeTorrentEngine(steps = 5, stepDelay = 1.seconds)
        val after = queue(backgroundScope)
        after.start()
        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(audio))
    }

    @Test
    fun pausePutsRunningDownloadsBackAtTheHead() = runTest {
        settings.update { it.copy(maxConcurrent = 1) }
        val queue = queue(backgroundScope)
        queue.start()
        val audio = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO, targetName = "Пикник"))
        val book = queue.enqueue(book("11111"))
        advance(2)

        queue.pause()
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, status(audio))
        assertEquals(1, get(audio).queuePosition)
        assertEquals(2, get(book).queuePosition)
        assertTrue(get(audio).progress.completed > 0)
        assertEquals(FakeTorrentEngine.JobState.PAUSED, torrents.state(audio))

        queue.start()
        runCurrent()
        assertEquals(DownloadStatus.DOWNLOADING, status(audio))
        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(audio))
    }

    @Test
    fun handsTorrentOverToSeedingWhenEnabled() = runTest {
        settings.update { it.copy(seedHours = 3) }
        val offered = mutableListOf<String>()
        val seeding = SeedingHandoff { download, staging ->
            offered += download.id
            assertTrue(staging.exists())
            download.copy(status = DownloadStatus.SEEDING, seedUntil = clock.now().plusSeconds(3 * 3600))
        }
        val queue = queue(backgroundScope, seeding)
        queue.start()
        val id = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))

        advance(TORRENT_SECONDS)
        assertEquals(listOf(id), offered)
        assertEquals(DownloadStatus.SEEDING, status(id))
        assertEquals(FakeTorrentEngine.JobState.DONE, torrents.state(id))

        queue.stopSeeding(id)
        assertEquals(DownloadStatus.COMPLETED, status(id))
        assertNull(torrents.state(id))
    }

    @Test
    fun dismissRemovesFailedDownload() = runTest {
        val queue = queue(backgroundScope)
        queue.start()
        torrents.failAtStep = AppError.Stalled()
        val id = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.FAILED, status(id))

        queue.dismiss(id)
        assertNull(repository.get(id))
        assertFalse(File(dir, "torrents/$id.torrent").exists())
    }

    @Test
    fun dismissRemovesCompletedDownloadRecordOnly() = runTest {
        val queue = queue(backgroundScope)
        queue.start()
        val id = queue.enqueue(torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        advance(TORRENT_SECONDS)
        assertEquals(DownloadStatus.COMPLETED, status(id))
        val placed = repository.get(id)!!.paths

        queue.dismiss(id)

        assertNull(repository.get(id))
        assertTrue(placed.all { storage.exists(MediaKind.AUDIO, it) })
    }

    private companion object {
        val BOOK_TIME = 10.seconds
        const val BOOK_SECONDS = 10
        const val TORRENT_SECONDS = 10
    }
}
