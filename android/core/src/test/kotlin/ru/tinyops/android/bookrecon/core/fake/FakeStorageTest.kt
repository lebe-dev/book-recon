package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import java.io.File

class FakeStorageTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock()

    @Test
    fun `library marks missing files and filters by query and kind`() = runTest {
        val repository = FakeDownloadRepository(FakeData.library(clock.now()), FakeData::sampleFileExists)

        val all = repository.observeLibrary().first()
        assertEquals(listOf("sample-2", "sample-1", "sample-3"), all.map { it.id })
        assertEquals(listOf(false, false, true), all.map { it.fileMissing })

        assertEquals(listOf("sample-2"), repository.observeLibrary(kind = MediaKind.AUDIO).first().map { it.id })
        assertEquals(listOf("sample-3"), repository.observeLibrary("замятин").first().map { it.id })
    }

    @Test
    fun `findInLibrary ignores other sources and unfinished downloads`() = runTest {
        val sample = FakeData.library(clock.now()).first()
        val repository = FakeDownloadRepository(
            listOf(sample, sample.copy(id = "queued", sourceId = "22222", status = DownloadStatus.QUEUED)),
            FakeData::sampleFileExists,
        )

        assertEquals("sample-1", repository.findInLibrary(Source.FLIBUSTA, "11111")?.id)
        assertNull(repository.findInLibrary(Source.RUTRACKER, "11111"))
        assertNull(repository.findInLibrary(Source.FLIBUSTA, "22222"))
    }

    @Test
    fun `seeding downloads are in the library and checked for missing files`() = runTest {
        val sample = FakeData.library(clock.now()).first()
        val seeding = sample.copy(
            id = "seeding",
            sourceId = "77777",
            status = DownloadStatus.SEEDING,
            completedAt = clock.now(),
            seedUntil = clock.now().plusSeconds(3600),
        )
        val gone = seeding.copy(id = "gone", sourceId = "88888", paths = listOf(FakeData.MISSING_SAMPLE_PATH))
        val repository = FakeDownloadRepository(
            listOf(sample, seeding, gone, sample.copy(id = "running", sourceId = "99999", status = DownloadStatus.DOWNLOADING)),
            FakeData::sampleFileExists,
        )

        val library = repository.observeLibrary().first()
        assertEquals(setOf("sample-1", "seeding", "gone"), library.map { it.id }.toSet())
        assertEquals(mapOf("sample-1" to false, "seeding" to false, "gone" to true), library.associate { it.id to it.fileMissing })
        assertEquals("seeding", repository.findInLibrary(Source.FLIBUSTA, "77777")?.id)
        assertTrue(repository.findInLibrary(Source.FLIBUSTA, "88888")!!.fileMissing)
        assertTrue(repository.get("gone")!!.fileMissing)
        assertNull(repository.findInLibrary(Source.FLIBUSTA, "99999"))
    }

    @Test
    fun `seeding that ends keeps its place in the library`() = runTest {
        val sample = FakeData.library(clock.now()).first()
        val seeding = sample.copy(
            id = "seeding",
            sourceId = "77777",
            status = DownloadStatus.SEEDING,
            completedAt = clock.now().minusSeconds(60),
            seedUntil = clock.now().plusSeconds(3600),
        )
        val repository = FakeDownloadRepository(listOf(sample, seeding), FakeData::sampleFileExists)
        val before = repository.observeLibrary().first().map { it.id }

        repository.update(seeding.copy(status = DownloadStatus.COMPLETED, seedUntil = null))

        assertEquals(before, repository.observeLibrary().first().map { it.id })
    }

    @Test
    fun `file storage stages outside the roots and reports missing roots`() = runTest {
        val storage = FakeFileStorage(tmp)

        val staging = storage.stagingDir("job-1")
        assertEquals(File(tmp, "staging/job-1"), staging)
        storage.deleteStaging("job-1")
        assertFalse(staging.exists())

        storage.booksRoot = null
        assertEquals(AppError.RootNotConfigured(MediaKind.BOOK), assertThrows<AppError> { storage.requireRoot(MediaKind.BOOK) })

        storage.hasAccess = false
        assertThrows<AppError.StorageNotGranted> { storage.requireRoot(MediaKind.AUDIO) }
    }

    @Test
    fun `copyInto copies a file or a folder and refuses to overwrite`() = runTest {
        val storage = FakeFileStorage(tmp)
        val file = File(tmp, "a.txt").apply { writeText("a") }
        val folder = File(tmp, "src").apply { mkdirs() }
        File(folder, "CD1").mkdirs()
        File(folder, "CD1/01.mp3").writeText("x")

        storage.copyInto(MediaKind.BOOK, file, "b.txt")
        storage.copyInto(MediaKind.AUDIO, folder, "Book/Nested")

        assertTrue(file.exists())
        assertTrue(storage.exists(MediaKind.BOOK, "b.txt"))
        assertTrue(storage.isFolder(MediaKind.AUDIO, "Book/Nested"))
        assertEquals(listOf("Book/Nested/CD1/01.mp3"), storage.listFiles(MediaKind.AUDIO, "Book"))
        assertThrows<AppError.StorageFailed> { storage.copyInto(MediaKind.BOOK, file, "b.txt") }

        storage.delete(MediaKind.BOOK, "b.txt")
        storage.delete(MediaKind.BOOK, "b.txt")
        assertFalse(storage.exists(MediaKind.BOOK, "b.txt"))
    }
}
