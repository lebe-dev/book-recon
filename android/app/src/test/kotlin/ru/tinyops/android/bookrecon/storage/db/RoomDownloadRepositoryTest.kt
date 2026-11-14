package ru.tinyops.android.bookrecon.storage.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.fake.FakeClock
import ru.tinyops.android.bookrecon.core.fake.FakeFileStorage
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 fails to set up SDK 36+ on JDK 21 (ApplicationSharedMemory), so pin 35.
@Config(sdk = [35])
class RoomDownloadRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private lateinit var storage: FakeFileStorage
    private lateinit var database: DownloadDatabase
    private lateinit var repository: RoomDownloadRepository

    @Before
    fun setUp() {
        storage = FakeFileStorage(temp.root)
        database = inMemoryDatabase()
        repository = RoomDownloadRepository(database.downloadDao(), storage, clock, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun inMemoryDatabase(): DownloadDatabase =
        Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()

    @Test
    fun insertAndGetRoundTripEveryField() = runBlocking {
        val book = File(temp.root, "Audiobooks/Мастер и Маргарита.epub").apply { parentFile!!.mkdirs(); writeText("x") }
        val torrent = download(
            id = "t1",
            kind = MediaKind.AUDIO,
            source = Source.RUTRACKER,
            sourceId = "6543210",
            status = DownloadStatus.SEEDING,
        ).copy(
            author = "Булгаков",
            targetName = "Булгаков - Мастер и Маргарита",
            progress = DownloadProgress(completed = 10, total = 20),
            files = listOf(TorrentFile(0, "CD1/01.mp3", 5), TorrentFile(3, "CD2/01.mp3", 7)),
            paths = listOf(book.name, "Audiobooks/x\ny"),
            size = 12,
            error = AppError.CloudflareChallenge("https://rutracker.org/forum/tracker.php"),
            completedAt = at(5),
            seedUntil = at(3600),
        )
        val flibusta = download(id = "f1").copy(format = Format.FB2, queuePosition = 1)

        repository.insert(torrent)
        repository.insert(flibusta)

        assertEquals(torrent.copy(progress = DownloadProgress(10, 20)), repository.get("t1"))
        assertEquals(flibusta, repository.get("f1"))
        assertNull(repository.get("missing"))
    }

    @Test
    fun peersAndSpeedAreNotStored() = runBlocking {
        repository.insert(download(id = "a").copy(progress = DownloadProgress(1, 2, peers = 5, speedBytesPerSec = 100)))

        assertEquals(DownloadProgress(1, 2), repository.get("a")!!.progress)
    }

    @Test
    fun insertingAnExistingIdFails() = runBlocking {
        repository.insert(download(id = "a"))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.insert(download(id = "a", title = "Другое")) }
        }
        assertEquals("Книга", repository.get("a")!!.title)
    }

    @Test
    fun updatingAMissingDownloadFails() {
        assertThrows(AppError.NotFound::class.java) {
            runBlocking { repository.update(download(id = "nope")) }
        }
    }

    @Test
    fun updateReplacesFields() = runBlocking {
        repository.insert(
            download(id = "a", source = Source.RUTRACKER).copy(
                files = listOf(TorrentFile(0, "a.mp3", 1), TorrentFile(1, "b.mp3", 2)),
            ),
        )

        val updated = repository.get("a")!!.copy(
            status = DownloadStatus.FAILED,
            files = listOf(TorrentFile(2, "c.mp3", 3)),
            error = AppError.NoSeeders(),
            queuePosition = null,
        )
        repository.update(updated)

        assertEquals(updated, repository.get("a"))
    }

    @Test
    fun deleteRemovesTheRecordAndItsFiles() = runBlocking {
        repository.insert(download(id = "a").copy(files = listOf(TorrentFile(0, "a.mp3", 1))))
        repository.insert(download(id = "b").copy(files = listOf(TorrentFile(0, "b.mp3", 1))))

        repository.delete("a")
        repository.delete("missing")

        assertNull(repository.get("a"))
        assertEquals(1, database.downloadDao().countFiles())
        assertEquals(listOf(TorrentFile(0, "b.mp3", 1)), repository.get("b")!!.files)
    }

    @Test
    fun queueIsOrderedByStatusPositionAndCreationTime() = runBlocking {
        repository.insert(download(id = "failed", status = DownloadStatus.FAILED, created = 1))
        repository.insert(download(id = "q2", status = DownloadStatus.QUEUED, created = 2).copy(queuePosition = 2))
        repository.insert(download(id = "q1", status = DownloadStatus.QUEUED, created = 3).copy(queuePosition = 1))
        repository.insert(download(id = "q0", status = DownloadStatus.QUEUED, created = 0))
        repository.insert(download(id = "seed", status = DownloadStatus.SEEDING, created = 4))
        repository.insert(download(id = "down2", status = DownloadStatus.DOWNLOADING, created = 6))
        repository.insert(download(id = "down1", status = DownloadStatus.DOWNLOADING, created = 5))
        repository.insert(download(id = "done", status = DownloadStatus.COMPLETED, created = 7))
        repository.insert(download(id = "done-new", status = DownloadStatus.COMPLETED, created = 9))
        repository.insert(download(id = "cancelled", status = DownloadStatus.CANCELLED, created = 8))

        val ids = repository.observeQueue().first().map { it.id }

        assertEquals(listOf("down1", "down2", "seed", "q1", "q2", "q0", "failed", "cancelled", "done-new", "done"), ids)
    }

    @Test
    fun queueEmitsAfterEveryWrite() = runBlocking {
        repository.observeQueue().test {
            assertEquals(emptyList<Download>(), awaitItem())

            repository.insert(download(id = "a"))
            assertEquals(listOf(DownloadStatus.QUEUED), awaitItem().map { it.status })

            repository.update(repository.get("a")!!.copy(status = DownloadStatus.DOWNLOADING))
            assertEquals(listOf(DownloadStatus.DOWNLOADING), awaitItem().map { it.status })

            repository.delete("a")
            assertEquals(emptyList<Download>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun libraryHoldsCompletedAndSeedingDownloadsNewestFirst() = runBlocking {
        repository.insert(completed(id = "old", title = "Старая", finished = 10))
        repository.insert(completed(id = "new", title = "Новая", finished = 20))
        repository.insert(seeding(id = "seeding", finished = 15))
        repository.insert(download(id = "queued", status = DownloadStatus.QUEUED))
        repository.insert(download(id = "running", status = DownloadStatus.DOWNLOADING))
        repository.insert(download(id = "failed", status = DownloadStatus.FAILED))
        repository.insert(download(id = "cancelled", status = DownloadStatus.CANCELLED))

        assertEquals(listOf("new", "seeding", "old"), repository.observeLibrary().first().map { it.id })
    }

    @Test
    fun seedingThatEndsStaysInPlaceWithoutLeavingTheLibrary() = runBlocking {
        repository.insert(completed(id = "old", finished = 10))
        repository.insert(seeding(id = "seeding", finished = 15))
        repository.insert(completed(id = "new", finished = 20))

        repository.observeLibrary().test {
            assertEquals(listOf("new", "seeding", "old"), awaitItem().map { it.id })

            val record = repository.get("seeding")!!
            repository.update(record.copy(status = DownloadStatus.COMPLETED, seedUntil = null))

            val after = awaitItem()
            assertEquals(listOf("new", "seeding", "old"), after.map { it.id })
            assertEquals(DownloadStatus.COMPLETED, after[1].status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun seedingDownloadsAreFoundAndCheckedForMissingFiles() = runBlocking {
        val folder = File(temp.root, "Audiobooks/Пикник").apply { mkdirs() }
        repository.insert(
            seeding(id = "seeding", source = Source.RUTRACKER, sourceId = "42", finished = 1)
                .copy(kind = MediaKind.AUDIO, paths = listOf(folder.name)),
        )
        repository.insert(download(id = "running", source = Source.RUTRACKER, sourceId = "43", status = DownloadStatus.DOWNLOADING))

        val found = repository.findInLibrary(Source.RUTRACKER, "42")!!
        assertEquals("seeding", found.id)
        assertFalse(found.fileMissing)
        assertNull(repository.findInLibrary(Source.RUTRACKER, "43"))

        folder.deleteRecursively()

        assertTrue(repository.findInLibrary(Source.RUTRACKER, "42")!!.fileMissing)
        assertTrue(repository.get("seeding")!!.fileMissing)
        assertTrue(repository.observeLibrary().first().single().fileMissing)
    }

    @Test
    fun librarySearchMatchesTitleOrAuthorIgnoringCase() = runBlocking {
        repository.insert(completed(id = "a", title = "Мастер и Маргарита", author = "Михаил Булгаков", finished = 1))
        repository.insert(completed(id = "b", title = "Пикник на обочине", author = "Стругацкие", finished = 2))
        repository.insert(completed(id = "c", title = "100% Kotlin_guide", author = "", finished = 3))

        assertEquals(listOf("a"), library("маргарита"))
        assertEquals(listOf("a"), library("  БУЛГАКОВ "))
        assertEquals(listOf("b"), library("стругацкие"))
        assertEquals(listOf("c"), library("kotlin_"))
        assertEquals(listOf("c"), library("100%"))
        assertEquals(emptyList<String>(), library("1_0"))
        assertEquals(emptyList<String>(), library("0%k"))
        assertEquals(listOf("c", "b", "a"), library(""))
    }

    @Test
    fun libraryFiltersByKind() = runBlocking {
        repository.insert(completed(id = "book", kind = MediaKind.BOOK, finished = 1))
        repository.insert(completed(id = "audio", kind = MediaKind.AUDIO, finished = 2))

        assertEquals(listOf("book"), repository.observeLibrary(kind = MediaKind.BOOK).first().map { it.id })
        assertEquals(listOf("audio"), repository.observeLibrary(kind = MediaKind.AUDIO).first().map { it.id })
        assertEquals(listOf("audio", "book"), repository.observeLibrary(kind = null).first().map { it.id })
    }

    @Test
    fun fileMissingIsCheckedOnEveryRead() = runBlocking {
        val file = File(temp.root, "Books/Мы.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        val folder = File(temp.root, "Audiobooks/Пикник").apply { mkdirs() }
        repository.insert(completed(id = "book", finished = 1).copy(paths = listOf(file.name)))
        repository.insert(completed(id = "audio", finished = 2).copy(kind = MediaKind.AUDIO, paths = listOf(folder.name)))
        repository.insert(completed(id = "nopaths", finished = 3))

        assertFalse(repository.get("book")!!.fileMissing)
        assertTrue(repository.observeLibrary().first().none { it.fileMissing })

        file.delete()
        folder.deleteRecursively()

        assertTrue(repository.get("book")!!.fileMissing)
        val missing = repository.observeLibrary().first().associate { it.id to it.fileMissing }
        assertEquals(mapOf("nopaths" to false, "audio" to true, "book" to true), missing)
        assertTrue(repository.findInLibrary(Source.FLIBUSTA, "book")!!.fileMissing)
    }

    @Test
    fun fileIsPresentWhileAnyPathExists() = runBlocking {
        val kept = File(temp.root, "Books/a.epub").apply { parentFile!!.mkdirs(); writeText("x") }
        repository.insert(
            completed(id = "a", finished = 1).copy(paths = listOf("gone.fb2", kept.name)),
        )

        assertFalse(repository.get("a")!!.fileMissing)
    }

    @Test
    fun fileMissingIsNeverStored() = runBlocking {
        repository.insert(completed(id = "a", finished = 1).copy(paths = listOf("nowhere/a.epub"), fileMissing = true))
        assertTrue(repository.get("a")!!.fileMissing)

        repository.update(repository.get("a")!!.copy(status = DownloadStatus.FAILED))
        assertFalse(repository.get("a")!!.fileMissing)
    }

    @Test
    fun findInLibraryReturnsTheNewestOfTheSameItem() = runBlocking {
        repository.insert(completed(id = "old", sourceId = "42", finished = 1))
        repository.insert(completed(id = "new", sourceId = "42", finished = 2))
        repository.insert(completed(id = "other", sourceId = "43", finished = 3))
        repository.insert(completed(id = "tracker", source = Source.RUTRACKER, sourceId = "42", finished = 4))
        repository.insert(download(id = "queued", sourceId = "42", created = 5))

        assertEquals("new", repository.findInLibrary(Source.FLIBUSTA, "42")!!.id)
        assertEquals("tracker", repository.findInLibrary(Source.RUTRACKER, "42")!!.id)
        assertNull(repository.findInLibrary(Source.FLIBUSTA, "44"))
    }

    @Test
    fun listByStatusReturnsOldestFirst() = runBlocking {
        repository.insert(download(id = "b", status = DownloadStatus.DOWNLOADING, created = 2))
        repository.insert(download(id = "a", status = DownloadStatus.QUEUED, created = 1))
        repository.insert(download(id = "c", status = DownloadStatus.COMPLETED, created = 0))

        assertEquals(
            listOf("a", "b"),
            repository.listByStatus(setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)).map { it.id },
        )
        assertEquals(emptyList<Download>(), repository.listByStatus(emptySet()))
    }

    @Test
    fun progressOnlyUpdatesAreWrittenAtMostOncePerSecond() = runBlocking {
        val start = download(id = "a", status = DownloadStatus.DOWNLOADING)
        repository.insert(start)

        clock.advance(1.seconds)
        repository.update(start.copy(progress = DownloadProgress(10, 100)))
        assertEquals(10L, repository.get("a")!!.progress.completed)

        clock.advance(500.milliseconds)
        repository.update(start.copy(progress = DownloadProgress(20, 100)))
        assertEquals(10L, repository.get("a")!!.progress.completed)

        clock.advance(500.milliseconds)
        repository.update(start.copy(progress = DownloadProgress(30, 100)))
        assertEquals(30L, repository.get("a")!!.progress.completed)
    }

    @Test
    fun otherChangesAreWrittenRightAway() = runBlocking {
        val start = download(id = "a", status = DownloadStatus.DOWNLOADING)
        repository.insert(start)

        repository.update(start.copy(progress = DownloadProgress(20, 100), status = DownloadStatus.QUEUED, queuePosition = 1))
        assertEquals(DownloadStatus.QUEUED, repository.get("a")!!.status)
        assertEquals(20L, repository.get("a")!!.progress.completed)

        repository.update(start.copy(progress = DownloadProgress(20, 100), status = DownloadStatus.QUEUED, queuePosition = 2))
        assertEquals(2, repository.get("a")!!.queuePosition)
    }

    @Test
    fun interruptedDownloadsSurviveARestartAndGoBackToTheQueue() = runBlocking {
        val file = temp.newFile("downloads.db")
        file.delete()
        val first = Room.databaseBuilder(context, DownloadDatabase::class.java, file.absolutePath).build()
        val before = RoomDownloadRepository(first.downloadDao(), storage, clock, Dispatchers.IO)
        before.insert(download(id = "running", status = DownloadStatus.DOWNLOADING, created = 1))
        before.insert(download(id = "waiting", status = DownloadStatus.QUEUED, created = 2).copy(queuePosition = 1))
        first.close()

        val second = Room.databaseBuilder(context, DownloadDatabase::class.java, file.absolutePath).build()
        val after = RoomDownloadRepository(second.downloadDao(), storage, clock, Dispatchers.IO)
        after.listByStatus(setOf(DownloadStatus.DOWNLOADING)).forEach {
            after.update(it.copy(status = DownloadStatus.QUEUED, queuePosition = 0))
        }

        assertEquals(listOf("running", "waiting"), after.observeQueue().first().map { it.id })
        assertTrue(after.observeQueue().first().all { it.status == DownloadStatus.QUEUED })
        second.close()
    }

    @Test
    fun unknownStoredErrorCodeFallsBackToUnknown() {
        val entity = download(id = "a", status = DownloadStatus.FAILED).toEntity().copy(errorCode = "REMOVED_CODE")

        val restored = DownloadWithFiles(entity, emptyList()).toDomain()

        assertTrue(restored.error is AppError.Unknown)
    }

    private suspend fun library(query: String): List<String> =
        repository.observeLibrary(query = query).first().map { it.id }

    private fun at(seconds: Long): Instant = BASE.plusSeconds(seconds)

    private fun download(
        id: String,
        title: String = "Книга",
        kind: MediaKind = MediaKind.BOOK,
        source: Source = Source.FLIBUSTA,
        sourceId: String = id,
        status: DownloadStatus = DownloadStatus.QUEUED,
        created: Long = 0,
    ) = Download(
        id = id,
        kind = kind,
        source = source,
        sourceId = sourceId,
        title = title,
        author = "",
        status = status,
        createdAt = at(created),
    )

    private fun completed(
        id: String,
        title: String = "Книга",
        author: String = "",
        kind: MediaKind = MediaKind.BOOK,
        source: Source = Source.FLIBUSTA,
        sourceId: String = id,
        finished: Long,
    ) = download(id = id, title = title, kind = kind, source = source, sourceId = sourceId, status = DownloadStatus.COMPLETED)
        .copy(author = author, completedAt = at(100 + finished))

    private fun seeding(
        id: String,
        source: Source = Source.FLIBUSTA,
        sourceId: String = id,
        finished: Long,
    ) = download(id = id, source = source, sourceId = sourceId, status = DownloadStatus.SEEDING)
        .copy(completedAt = at(100 + finished), seedUntil = at(100_000))

    private companion object {
        val BASE: Instant = Instant.parse("2026-11-01T09:00:00Z")
    }
}
