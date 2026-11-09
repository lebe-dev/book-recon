package ru.tinyops.android.bookrecon.download.seeding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeFileStorage
import ru.tinyops.android.bookrecon.core.fake.FakeFlibustaSource
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.fake.FakeTorrentEngine
import ru.tinyops.android.bookrecon.core.fake.FakeTorrentEngine.JobState
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.download.DownloadQueueImpl
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Seeding policy on the real queue with fake ports. The clock follows the test scheduler, so
 * waiting for a deadline and the time the policy sees move together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeedingManagerTest {
    @TempDir
    lateinit var dir: File

    private val repository = FakeDownloadRepository()
    private val settings = FakeSettingsRepository(Settings(seedHours = SEED_HOURS))
    private var torrents = FakeTorrentEngine(steps = 5, stepDelay = 1.seconds)
    private lateinit var storage: FakeFileStorage
    private var ids = 0
    private var scheduled = 0

    @BeforeEach
    fun setUp() {
        storage = FakeFileStorage(dir)
    }

    private class Setup(val queue: DownloadQueueImpl, val seeding: SeedingManager)

    private fun TestScope.clock(): Clock = Clock { START.plusMillis(testScheduler.currentTime) }

    private fun TestScope.setup(scope: CoroutineScope = backgroundScope): Setup {
        lateinit var queue: DownloadQueueImpl
        val seeding = SeedingManager(
            repository = repository,
            settings = settings,
            storage = storage,
            torrents = torrents,
            scope = scope,
            clock = clock(),
            stopSeeding = { queue.stopSeeding(it) },
            onSeeding = { scheduled++ },
        )
        queue = DownloadQueueImpl(
            repository = repository,
            settings = settings,
            storage = storage,
            flibusta = FakeFlibustaSource(),
            torrents = torrents,
            torrentStore = File(dir, "torrents"),
            scope = scope,
            clock = clock(),
            seeding = seeding,
            newId = { "job-${++ids}" },
        )
        return Setup(queue, seeding)
    }

    private fun torrent(topicId: String, kind: MediaKind, indexes: Set<Int>? = null) = DownloadRequest.TorrentSelection(
        topicId = topicId,
        kind = kind,
        title = "Раздача $topicId",
        torrentBytes = FakeData.torrentBytes(topicId),
        selectedIndexes = indexes ?: FakeData.torrentInfo(topicId).files.map { it.index }.toSet(),
        targetName = "Булгаков - Мастер и Маргарита",
    )

    /** Enqueues a torrent and lets it download completely. */
    private suspend fun TestScope.downloaded(setup: Setup, request: DownloadRequest.TorrentSelection): String {
        setup.queue.start()
        val id = setup.queue.enqueue(request)
        advance(TORRENT_TIME)
        return id
    }

    private suspend fun get(id: String): Download = repository.get(id)!!

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun TestScope.left(until: Instant): Duration =
        (until.toEpochMilli() - START.plusMillis(testScheduler.currentTime).toEpochMilli()).milliseconds

    private val audioTarget get() = File(dir, "Audiobooks/Булгаков - Мастер и Маргарита")

    @Test
    fun completedTorrentMovesIntoTheLibraryThroughTheEngineAndSeeds() = runTest {
        val setup = setup()
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO, setOf(0, 1, 3)))

        val download = get(id)
        assertEquals(DownloadStatus.SEEDING, download.status)
        assertEquals(download.completedAt!!.plusSeconds(SEED_HOURS * 3600L), download.seedUntil)
        assertEquals(listOf(audioTarget.path), download.paths)
        assertEquals(download.size, download.progress.completed)
        // The same layout as without seeding: the torrent's root folder is dropped, CDs stay.
        val moved = torrents.movedFiles(id)
        assertEquals(File(audioTarget, "CD1/01 Глава 1. Никогда не разговаривайте с неизвестными.mp3"), moved[0])
        assertEquals(File(audioTarget, "CD2/01 Глава 13. Явление героя.mp3"), moved[3])
        assertTrue(moved.values.all { it.isFile })
        assertFalse(File(dir, "Audiobooks/.book-recon/$id").exists())
        assertTrue(File(dir, "torrents/$id.torrent").exists(), "kept until seeding ends")
        assertTrue(scheduled > 0, "the seeding job is scheduled")
        // Nothing is uploaded until the job says Wi-Fi and charger are there.
        assertEquals(JobState.DONE, torrents.state(id))
    }

    @Test
    fun ebooksFromTorrentGoFlatIntoTheBooksRootWithCollisionNames() = runTest {
        File(dir, "Books").mkdirs()
        File(dir, "Books/Достоевский - Преступление и наказание.epub").writeText("old")
        val setup = setup()

        val id = downloaded(setup, torrent(FakeData.BOOK_TOPIC_FORMATS, MediaKind.BOOK, setOf(0, 1)))

        val expected = listOf(
            File(dir, "Books/Достоевский - Преступление и наказание (2).epub"),
            File(dir, "Books/Достоевский - Преступление и наказание.fb2"),
        )
        assertEquals(DownloadStatus.SEEDING, get(id).status)
        assertEquals(expected.map { it.path }, get(id).paths)
        assertEquals(expected, torrents.movedFiles(id).toSortedMap().values.toList())
        assertEquals("old", File(dir, "Books/Достоевский - Преступление и наказание.epub").readText())
    }

    @Test
    fun seedsOnlyWhileAllowedAndPauseTimeCountsTowardsTheLimit() = runTest {
        val setup = setup()
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        val until = get(id).seedUntil!!

        setup.seeding.setAllowed(true)
        runCurrent()
        assertEquals(JobState.SEEDING, torrents.state(id))
        assertEquals(left(until), torrents.seedDuration(id))

        advance(1.hours)
        setup.seeding.setAllowed(false)
        runCurrent()
        assertEquals(JobState.SEEDING_PAUSED, torrents.state(id))
        assertEquals(DownloadStatus.SEEDING, get(id).status)

        advance(30.minutes)
        setup.seeding.setAllowed(true)
        runCurrent()
        assertEquals(JobState.SEEDING, torrents.state(id))
        assertEquals(left(until), torrents.seedDuration(id))
        assertTrue(left(until) < SEED_HOURS.hours - 90.minutes)
        assertEquals(until, get(id).seedUntil, "the deadline does not move")
    }

    @Test
    fun seedingEndsAtTheDeadlineAndTheFilesStay() = runTest {
        val setup = setup()
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        setup.seeding.setAllowed(true)
        runCurrent()

        advance(left(get(id).seedUntil!!) - 1.seconds)
        assertEquals(DownloadStatus.SEEDING, get(id).status)
        advance(1.seconds)

        val download = get(id)
        assertEquals(DownloadStatus.COMPLETED, download.status)
        assertNull(download.seedUntil)
        assertNull(torrents.state(id), "the torrent left the session")
        assertFalse(File(dir, "torrents/$id.torrent").exists())
        assertTrue(File(audioTarget, "01. Пролог.mp3").isFile)
        assertFalse(download.fileMissing)
    }

    @Test
    fun deadlinePassingWhilePausedCompletesTheDownload() = runTest {
        val setup = setup()
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        setup.seeding.setAllowed(true)
        runCurrent()
        setup.seeding.setAllowed(false)
        runCurrent()

        advance(SEED_HOURS.hours)

        assertEquals(DownloadStatus.COMPLETED, get(id).status)
        assertNull(torrents.state(id))
        assertTrue(File(audioTarget, "01. Пролог.mp3").isFile)
    }

    @Test
    fun stopSeedingFromTheQueueIsRespected() = runTest {
        val setup = setup()
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        setup.seeding.setAllowed(true)
        runCurrent()

        setup.queue.stopSeeding(id)
        runCurrent()
        setup.seeding.setAllowed(false)
        setup.seeding.setAllowed(true)
        runCurrent()

        assertEquals(DownloadStatus.COMPLETED, get(id).status)
        assertNull(torrents.state(id))
        assertTrue(File(audioTarget, "01. Пролог.mp3").isFile)
        assertFalse(setup.seeding.hasSeedingNow())
    }

    @Test
    fun seedingContinuesAfterAProcessRestartAndOverdueOnesComplete() = runTest {
        val process = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val first = setup(process)
        val a = downloaded(first, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        advance(1.hours)
        val b = downloaded(first, torrent(FakeData.BOOK_TOPIC_FORMATS, MediaKind.BOOK, setOf(0)))
        val untilB = get(b).seedUntil!!

        // The process dies: new queue and seeding manager over the same records and engine.
        process.cancel()
        val restarted = setup()
        advance(SEED_HOURS.hours - 30.minutes)
        restarted.seeding.setAllowed(true)
        runCurrent()

        assertEquals(DownloadStatus.COMPLETED, get(a).status)
        assertEquals(DownloadStatus.SEEDING, get(b).status)
        assertEquals(JobState.SEEDING, torrents.state(b))
        assertEquals(left(untilB), torrents.seedDuration(b))
    }

    @Test
    fun engineThatLostTheTorrentCompletesTheDownloadInsteadOfFailing() = runTest {
        val setup = setup()
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        torrents.cancel(id)

        setup.seeding.setAllowed(true)
        runCurrent()

        assertEquals(DownloadStatus.COMPLETED, get(id).status)
        assertNull(get(id).error)
        assertTrue(File(audioTarget, "01. Пролог.mp3").isFile)
    }

    @Test
    fun failedMoveFallsBackToPlacingWithoutSeeding() = runTest {
        torrents.failMoveFiles = AppError.StorageFailed("disk full")
        val setup = setup()

        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO, setOf(0, 1, 3)))

        val download = get(id)
        assertEquals(DownloadStatus.COMPLETED, download.status)
        assertNull(download.seedUntil)
        assertEquals(listOf(audioTarget.path), download.paths)
        assertTrue(File(audioTarget, "CD1/01 Глава 1. Никогда не разговаривайте с неизвестными.mp3").isFile)
        assertTrue(File(audioTarget, "CD1/02 Глава 2. Понтий Пилат.mp3").isFile)
        assertTrue(File(audioTarget, "CD2/01 Глава 13. Явление героя.mp3").isFile)
        assertFalse(File(dir, "Audiobooks/Булгаков - Мастер и Маргарита (2)").exists())
        assertNull(torrents.state(id))
    }

    @Test
    fun seedingOffLeavesTheQueueToPlaceFiles() = runTest {
        settings.update { it.copy(seedHours = 0) }
        val setup = setup()

        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))

        assertEquals(DownloadStatus.COMPLETED, get(id).status)
        assertTrue(torrents.movedFiles(id).isEmpty())
        assertTrue(File(audioTarget, "01. Пролог.mp3").isFile)
        assertEquals(0, scheduled)
    }

    @Test
    fun hasSeedingFollowsTheRecords() = runTest {
        val setup = setup()
        assertFalse(setup.seeding.hasSeedingNow())
        val id = downloaded(setup, torrent(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO))
        assertTrue(setup.seeding.hasSeedingNow())

        setup.queue.stopSeeding(id)

        assertFalse(setup.seeding.hasSeedingNow())
    }

    private companion object {
        const val SEED_HOURS = 3
        val START: Instant = Instant.parse("2026-11-01T09:00:00Z")
        val TORRENT_TIME = 10.seconds
    }
}
