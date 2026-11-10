package ru.tinyops.android.bookrecon.storage.files

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    private val booksUri = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
    private val audioUri = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks"
    private val books by lazy { File(dir, "Books").apply { mkdirs() } }
    private val audio by lazy { File(dir, "Audiobooks").apply { mkdirs() } }
    private val staging by lazy { File(dir, "staging") }
    private val tree by lazy { LocalDocumentTree(mapOf(booksUri to books, audioUri to audio)) }
    private val settings by lazy { FakeSettingsRepository(Settings(booksRoot = booksUri, audioRoot = audioUri)) }

    private fun storage() = AndroidFileStorage(
        settings = settings,
        tree = tree,
        stagingBase = staging,
        primaryRoot = "/storage/emulated/0",
    )

    @Test
    fun requireRootAcceptsChosenRoots() = runBlocking {
        storage().requireRoot(MediaKind.BOOK)
        storage().requireRoot(MediaKind.AUDIO)
    }

    @Test
    fun rootNotChosen() = runBlocking {
        settings.update { it.copy(audioRoot = null) }
        val error = assertThrows<AppError.RootNotConfigured> { runBlocking { storage().requireRoot(MediaKind.AUDIO) } }
        assertEquals(MediaKind.AUDIO, error.kind)
    }

    @Test
    fun rootWithoutGrant() = runBlocking {
        tree.granted = false
        assertThrows<AppError.StorageNotGranted> { runBlocking { storage().requireRoot(MediaKind.BOOK) } }
        Unit
    }

    @Test
    fun rootThatIsGone() = runBlocking {
        books.deleteRecursively()
        assertThrows<AppError.RootNotConfigured> { runBlocking { storage().requireRoot(MediaKind.BOOK) } }
        Unit
    }

    @Test
    fun stagingIsPrivateAndRemovable() = runBlocking {
        val storage = storage()

        val dir = storage.stagingDir("job-1")

        assertEquals(File(staging, "job-1"), dir)
        assertTrue(dir.isDirectory)
        File(dir, "a.bin").writeText("a")
        storage.deleteStaging("job-1")
        assertFalse(dir.exists())
        storage.deleteStaging("job-1")
    }

    @Test
    fun copyIntoPlacesFilesAndFolders() = runBlocking {
        val storage = storage()
        val file = File(dir, "src.epub").apply { writeText("book") }
        val folder = File(dir, "src").apply { mkdirs() }
        File(folder, "CD1").mkdirs()
        File(folder, "CD1/01.mp3").writeText("sound")

        storage.copyInto(MediaKind.BOOK, file, "Мастер.epub")
        storage.copyInto(MediaKind.AUDIO, folder, "Книга")

        assertEquals("book", File(books, "Мастер.epub").readText())
        assertTrue(file.exists())
        assertTrue(storage.exists(MediaKind.BOOK, "Мастер.epub"))
        assertFalse(storage.isFolder(MediaKind.BOOK, "Мастер.epub"))
        assertTrue(storage.isFolder(MediaKind.AUDIO, "Книга"))
        assertEquals(listOf("Книга/CD1/01.mp3"), storage.listFiles(MediaKind.AUDIO, "Книга"))
        assertTrue(storage.uriOf(MediaKind.BOOK, "Мастер.epub")!!.startsWith("content://"))
    }

    @Test
    fun copyIntoNeverOverwrites() = runBlocking {
        val storage = storage()
        File(books, "a.epub").writeText("old")
        val file = File(dir, "a.epub").apply { writeText("new") }

        assertThrows<AppError.StorageFailed> { runBlocking { storage.copyInto(MediaKind.BOOK, file, "a.epub") } }

        assertEquals("old", File(books, "a.epub").readText())
    }

    @Test
    fun copyIntoReportsFailure() = runBlocking {
        val storage = storage()
        tree.failCopies = true
        val file = File(dir, "a.epub").apply { writeText("new") }

        assertThrows<AppError.StorageFailed> { runBlocking { storage.copyInto(MediaKind.BOOK, file, "a.epub") } }
        Unit
    }

    @Test
    fun copyIntoNeedsAUsableRoot() = runBlocking {
        tree.granted = false
        val file = File(dir, "a.epub").apply { writeText("new") }

        assertThrows<AppError.StorageNotGranted> { runBlocking { storage().copyInto(MediaKind.BOOK, file, "a.epub") } }
        Unit
    }

    @Test
    fun deleteFilesAndFolders() = runBlocking {
        val storage = storage()
        File(books, "a.epub").writeText("a")
        File(audio, "Книга/CD1").mkdirs()
        File(audio, "Книга/CD1/01.mp3").writeText("x")

        storage.delete(MediaKind.BOOK, "a.epub")
        storage.delete(MediaKind.AUDIO, "Книга")
        storage.delete(MediaKind.AUDIO, "Книга")

        assertFalse(File(books, "a.epub").exists())
        assertFalse(File(audio, "Книга").exists())
    }

    @Test
    fun deleteNeverRemovesTheRoot() = runBlocking {
        storage().delete(MediaKind.BOOK, "")
        storage().delete(MediaKind.BOOK, "/")

        assertTrue(books.isDirectory)
    }

    @Test
    fun pathsThatClimbOutOfTheRootAreIgnored() = runBlocking {
        File(dir, "secret.txt").writeText("x")
        val storage = storage()

        assertFalse(storage.exists(MediaKind.BOOK, "../secret.txt"))
        assertNull(storage.uriOf(MediaKind.BOOK, "../secret.txt"))
        assertThrows<AppError.Validation> {
            runBlocking { storage.copyInto(MediaKind.BOOK, File(dir, "secret.txt"), "../out.txt") }
        }
        Unit
    }

    @Test
    fun oldAbsolutePathsInsideTheRootStillResolve() = runBlocking {
        File(books, "a.epub").writeText("a")
        File(audio, "Книга").mkdirs()
        File(audio, "Книга/01.mp3").writeText("x")
        val storage = storage()

        assertTrue(storage.exists(MediaKind.BOOK, "/storage/emulated/0/Books/a.epub"))
        assertTrue(storage.isFolder(MediaKind.AUDIO, "/storage/emulated/0/Audiobooks/Книга"))
        assertEquals(listOf("Книга/01.mp3"), storage.listFiles(MediaKind.AUDIO, "/storage/emulated/0/Audiobooks/Книга"))
        assertFalse(storage.exists(MediaKind.BOOK, "/storage/emulated/0/Other/a.epub"))
        assertFalse(storage.exists(MediaKind.BOOK, "/storage/emulated/0/Documents/a.epub"))
    }

    @Test
    fun checkRoot() = runBlocking {
        val storage = storage()
        assertEquals(RootCheck.OK, storage.checkRoot(booksUri))
        assertTrue(books.listFiles().orEmpty().isEmpty())

        audio.deleteRecursively()
        assertEquals(RootCheck.MISSING, storage.checkRoot(audioUri))

        tree.failCopies = true
        assertEquals(RootCheck.NOT_WRITABLE, storage.checkRoot(booksUri))
        tree.failCopies = false

        tree.granted = false
        assertEquals(RootCheck.NO_ACCESS, storage.checkRoot(booksUri))
    }
}
