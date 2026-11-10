package ru.tinyops.android.bookrecon.storage.files

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import java.io.File

class AndroidFileStorageTest {
    @TempDir
    lateinit var dir: File

    private var access = true
    private var renameWorks = true
    private val books by lazy { File(dir, "Books").apply { mkdirs() } }
    private val audio by lazy { File(dir, "Audiobooks").apply { mkdirs() } }
    private val settings by lazy { FakeSettingsRepository(Settings(booksRoot = books.path, audioRoot = audio.path)) }

    private fun storage() = AndroidFileStorage(
        settings = settings,
        hasAccess = { access },
        rename = { from, to -> renameWorks && from.renameTo(to) },
    )

    @Test
    fun reportsAllFilesAccess() {
        val storage = storage()
        assertTrue(storage.hasAllFilesAccess())
        access = false
        assertFalse(storage.hasAllFilesAccess())
    }

    @Test
    fun rootOfEachKind() = runBlocking {
        assertEquals(books, storage().root(MediaKind.BOOK))
        assertEquals(audio, storage().root(MediaKind.AUDIO))
    }

    @Test
    fun rootWithoutAccess() = runBlocking {
        access = false
        assertThrows<AppError.StorageNotGranted> { runBlocking { storage().root(MediaKind.BOOK) } }
        Unit
    }

    @Test
    fun rootNotChosen() = runBlocking {
        settings.update { it.copy(audioRoot = null) }
        val error = assertThrows<AppError.RootNotConfigured> { runBlocking { storage().root(MediaKind.AUDIO) } }
        assertEquals(MediaKind.AUDIO, error.kind)
    }

    @Test
    fun rootThatIsGoneIsNotRecreated() = runBlocking {
        books.deleteRecursively()
        assertThrows<AppError.RootNotConfigured> { runBlocking { storage().root(MediaKind.BOOK) } }
        assertFalse(books.exists())
    }

    @Test
    fun rootThatIsAFile() = runBlocking {
        val file = File(dir, "file").apply { writeText("x") }
        settings.update { it.copy(booksRoot = file.path) }
        assertThrows<AppError.RootNotConfigured> { runBlocking { storage().root(MediaKind.BOOK) } }
        Unit
    }

    @Test
    fun stagingDirHidesItselfFromMediaScanners() = runBlocking {
        val staging = storage().stagingDir(MediaKind.AUDIO, "job-1")

        assertEquals(File(audio, ".book-recon/job-1"), staging)
        assertTrue(staging.isDirectory)
        assertTrue(File(audio, ".book-recon/.nomedia").isFile)

        assertEquals(staging, storage().stagingDir(MediaKind.AUDIO, "job-1"))
        assertEquals(File(books, ".book-recon/job-2"), storage().stagingDir(MediaKind.BOOK, "job-2"))
        assertTrue(File(books, ".book-recon/.nomedia").isFile)
    }

    @Test
    fun stagingDirHasTheSameErrorsAsRoot() = runBlocking {
        settings.update { it.copy(booksRoot = null) }
        assertThrows<AppError.RootNotConfigured> { runBlocking { storage().stagingDir(MediaKind.BOOK, "job") } }
        access = false
        assertThrows<AppError.StorageNotGranted> { runBlocking { storage().stagingDir(MediaKind.AUDIO, "job") } }
        Unit
    }

    @Test
    fun moveFileByRename() = runBlocking {
        val from = File(dir, "a.epub").apply { writeText("book") }
        val to = File(books, "Автор/Книга.epub")

        storage().move(from, to)

        assertFalse(from.exists())
        assertEquals("book", to.readText())
    }

    @Test
    fun moveFolderByRename() = runBlocking {
        val from = File(dir, "staging").apply { File(this, "CD1").mkdirs() }
        File(from, "CD1/01.mp3").writeText("one")

        val to = File(audio, "Книга")
        storage().move(from, to)

        assertFalse(from.exists())
        assertEquals("one", File(to, "CD1/01.mp3").readText())
    }

    @Test
    fun moveFallsBackToCopyAndDelete() = runBlocking {
        renameWorks = false
        val file = File(dir, "a.epub").apply { writeText("book") }
        val folder = File(dir, "staging").apply { File(this, "CD2").mkdirs() }
        File(folder, "CD2/02.mp3").writeText("two")

        storage().move(file, File(books, "a.epub"))
        storage().move(folder, File(audio, "Книга"))

        assertFalse(file.exists())
        assertFalse(folder.exists())
        assertEquals("book", File(books, "a.epub").readText())
        assertEquals("two", File(audio, "Книга/CD2/02.mp3").readText())
    }

    @Test
    fun moveNeverOverwrites() = runBlocking {
        val from = File(dir, "a.epub").apply { writeText("new") }
        val to = File(books, "a.epub").apply { writeText("old") }

        assertThrows<AppError.StorageFailed> { runBlocking { storage().move(from, to) } }
        assertEquals("new", from.readText())
        assertEquals("old", to.readText())
    }

    @Test
    fun failedMoveLeavesNoPartialTarget() = runBlocking {
        renameWorks = false
        val missing = File(dir, "missing")
        val to = File(books, "missing")

        assertThrows<AppError.StorageFailed> { runBlocking { storage().move(missing, to) } }
        assertFalse(to.exists())
    }

    @Test
    fun deleteFilesAndFolders() = runBlocking {
        val file = File(books, "a.epub").apply { writeText("x") }
        val folder = File(audio, "Книга/CD1").apply { mkdirs() }
        File(folder, "01.mp3").writeText("x")

        storage().delete(file)
        storage().delete(File(audio, "Книга"))
        storage().delete(File(dir, "missing"))

        assertFalse(file.exists())
        assertFalse(File(audio, "Книга").exists())
        assertTrue(storage().exists(books))
        assertFalse(storage().exists(file))
    }

    @Test
    fun checkRoot() = runBlocking {
        val storage = storage()
        assertEquals(RootCheck.OK, storage.checkRoot(books.path))
        assertEquals(listOf<String>(), books.list()!!.toList())

        assertEquals(RootCheck.MISSING, storage.checkRoot(File(dir, "missing").path))
        assertEquals(RootCheck.MISSING, storage.checkRoot(File(dir, "file").apply { writeText("x") }.path))

        val readOnly = File(dir, "readonly").apply { mkdirs() }
        readOnly.setWritable(false)
        try {
            assertEquals(RootCheck.NOT_WRITABLE, storage.checkRoot(readOnly.path))
        } finally {
            readOnly.setWritable(true)
        }

        access = false
        assertEquals(RootCheck.NO_ACCESS, storage.checkRoot(books.path))
    }
}
