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
    fun `findCompleted ignores other sources and unfinished downloads`() = runTest {
        val sample = FakeData.library(clock.now()).first()
        val repository = FakeDownloadRepository(
            listOf(sample, sample.copy(id = "queued", sourceId = "22222", status = DownloadStatus.QUEUED)),
            FakeData::sampleFileExists,
        )

        assertEquals("sample-1", repository.findCompleted(Source.FLIBUSTA, "11111")?.id)
        assertNull(repository.findCompleted(Source.RUTRACKER, "11111"))
        assertNull(repository.findCompleted(Source.FLIBUSTA, "22222"))
    }

    @Test
    fun `file storage creates hidden staging and reports missing roots`() = runTest {
        val storage = FakeFileStorage(tmp)

        val staging = storage.stagingDir(MediaKind.AUDIO, "job-1")
        assertEquals(File(tmp, "Audiobooks/.book-recon/job-1"), staging)
        assertTrue(File(tmp, "Audiobooks/.book-recon/.nomedia").exists())

        storage.booksRoot = null
        assertEquals(AppError.RootNotConfigured(MediaKind.BOOK), assertThrows<AppError> { storage.root(MediaKind.BOOK) })

        storage.hasAccess = false
        assertThrows<AppError.StorageNotGranted> { storage.root(MediaKind.AUDIO) }
    }

    @Test
    fun `move refuses to overwrite`() = runTest {
        val storage = FakeFileStorage(tmp)
        val from = File(tmp, "a.txt").apply { writeText("a") }
        val to = File(tmp, "dir/b.txt")

        storage.move(from, to)
        assertFalse(from.exists())
        assertTrue(to.exists())

        File(tmp, "c.txt").writeText("c")
        assertThrows<AppError.StorageFailed> { storage.move(File(tmp, "c.txt"), to) }
    }
}
