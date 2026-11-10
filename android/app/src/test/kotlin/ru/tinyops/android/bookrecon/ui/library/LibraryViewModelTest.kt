package ru.tinyops.android.bookrecon.ui.library

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeFileStorage
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FileStorage
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    private lateinit var storage: FakeFileStorage

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        storage = FakeFileStorage(dir)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    /** IDs passed to stopSeeding, in call order. */
    private val stopped = mutableListOf<String>()

    private fun viewModel(
        repository: DownloadRepository,
        fileStorage: FileStorage = storage,
        savedState: SavedStateHandle = SavedStateHandle(),
        stopSeeding: suspend (String) -> Unit = { stopped += it },
    ) = LibraryViewModel(repository, fileStorage, savedState, stopSeeding, io = dispatcher)

    /** Stops seeding like the queue does: the record becomes COMPLETED; checks the files are still there. */
    private fun queueStop(repository: DownloadRepository, file: File): suspend (String) -> Unit = { id ->
        assertTrue(file.exists(), "files must still be on disk when seeding stops")
        stopped += id
        val download = repository.get(id)!!
        repository.update(download.copy(status = DownloadStatus.COMPLETED, seedUntil = null))
    }

    @Test
    fun `shows loading, then completed downloads newest first with the missing file flagged`() = runVmTest {
        val repository = FakeDownloadRepository(FakeData.library(NOW), FakeData::sampleFileExists)
        val vm = viewModel(repository)
        assertTrue(vm.state.value.loading)

        runCurrent()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("sample-2", "sample-1", "sample-3"), state.items.map { it.id })
        assertEquals(listOf(false, false, true), state.items.map { it.fileMissing })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `query matches title or author in any case and is saved`() = runVmTest {
        val savedState = SavedStateHandle()
        val vm = viewModel(FakeDownloadRepository(FakeData.library(NOW), FakeData::sampleFileExists), savedState = savedState)
        runCurrent()

        vm.onQueryChange("булгаков")
        runCurrent()
        assertEquals("булгаков", vm.state.value.query)
        assertEquals(listOf("sample-1"), vm.state.value.items.map { it.id })

        vm.onQueryChange("ПИКНИК")
        runCurrent()
        assertEquals(listOf("sample-2"), vm.state.value.items.map { it.id })
        assertEquals("ПИКНИК", savedState.get<String>(LibraryViewModel.KEY_QUERY))
    }

    @Test
    fun `filter shows only books or only audiobooks`() = runVmTest {
        val vm = viewModel(FakeDownloadRepository(FakeData.library(NOW), FakeData::sampleFileExists))
        runCurrent()

        vm.onFilterChange(LibraryFilter.BOOKS)
        runCurrent()
        assertEquals(listOf("sample-1", "sample-3"), vm.state.value.items.map { it.id })

        vm.onFilterChange(LibraryFilter.AUDIO)
        runCurrent()
        assertEquals(listOf("sample-2"), vm.state.value.items.map { it.id })

        vm.onFilterChange(LibraryFilter.ALL)
        runCurrent()
        assertEquals(3, vm.state.value.items.size)
    }

    @Test
    fun `query and filter survive process death`() = runVmTest {
        val savedState = SavedStateHandle(
            mapOf(LibraryViewModel.KEY_QUERY to "мы", LibraryViewModel.KEY_FILTER to LibraryFilter.BOOKS.name),
        )
        val vm = viewModel(FakeDownloadRepository(FakeData.library(NOW), FakeData::sampleFileExists), savedState = savedState)
        runCurrent()

        assertEquals("мы", vm.state.value.query)
        assertEquals(LibraryFilter.BOOKS, vm.state.value.filter)
        assertEquals(listOf("sample-3"), vm.state.value.items.map { it.id })
    }

    @Test
    fun `empty library differs from an empty search result`() = runVmTest {
        val empty = viewModel(FakeDownloadRepository())
        runCurrent()
        assertTrue(empty.state.value.isEmpty)
        assertTrue(empty.state.value.isLibraryEmpty)

        val vm = viewModel(FakeDownloadRepository(FakeData.library(NOW), FakeData::sampleFileExists))
        vm.onQueryChange("Толстой")
        runCurrent()
        assertTrue(vm.state.value.isEmpty)
        assertFalse(vm.state.value.isLibraryEmpty)
    }

    @Test
    fun `refresh notices a file deleted outside the app`() = runVmTest {
        val file = bookFile("Мастер и Маргарита.epub")
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(book("b", file))))
        runCurrent()
        assertFalse(vm.state.value.items.single().fileMissing)

        file.delete()
        vm.onRefresh()
        runCurrent()

        assertTrue(vm.state.value.items.single().fileMissing)
    }

    @Test
    fun `a failing library read shows an error instead of crashing`() = runVmTest {
        val failing = object : DownloadRepository by FakeDownloadRepository() {
            override fun observeLibrary(query: String, kind: MediaKind?): Flow<List<Download>> =
                flow { throw AppError.StorageFailed() }
        }
        val vm = viewModel(failing)
        runCurrent()

        assertFalse(vm.state.value.loading)
        assertEquals(ErrorCode.STORAGE_FAILED, vm.state.value.loadError?.code)
    }

    @Test
    fun `open of a book asks the screen to view the file with its MIME type`() = runVmTest {
        val file = bookFile("Мастер и Маргарита.epub")
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(book("b", file))))
        runCurrent()

        vm.onOpen("b")
        runCurrent()

        assertEquals(OpenRequest(file.toURI().toString(), "application/epub+zip"), vm.state.value.open)
        vm.onOpenHandled()
        assertNull(vm.state.value.open)
    }

    @Test
    fun `open of an audiobook picks the first audio file of its folder`() = runVmTest {
        val folder = File(dir, "Audiobooks/Автор - Книга")
        listOf("CD2/01.mp3", "CD1/02.mp3", "CD1/01.mp3", "cover.jpg").forEach {
            File(folder, it).apply { parentFile?.mkdirs() }.writeText("x")
        }
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(audio("a", folder))))
        runCurrent()

        vm.onOpen("a")
        runCurrent()

        assertEquals(OpenRequest(File(folder, "CD1/01.mp3").toURI().toString(), "audio/mpeg"), vm.state.value.open)
    }

    @Test
    fun `open of an audiobook folder without audio explains why`() = runVmTest {
        val folder = File(dir, "Audiobooks/Пусто").apply { mkdirs() }
        File(folder, "cover.jpg").writeText("x")
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(audio("a", folder))))
        runCurrent()

        vm.onOpen("a")
        runCurrent()

        assertNull(vm.state.value.open)
        assertEquals(LibraryMessage.NoAudioFiles, vm.state.value.message)
    }

    @Test
    fun `open of a missing file reports it and opens nothing`() = runVmTest {
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(book("b", File(dir, "Books/gone.epub")))))
        runCurrent()

        vm.onOpen("b")
        runCurrent()

        assertNull(vm.state.value.open)
        assertEquals(LibraryMessage.FileMissing, vm.state.value.message)
        assertTrue(vm.state.value.items.single().fileMissing)
    }

    @Test
    fun `no app to open the file becomes a message`() = runVmTest {
        val file = bookFile("Книга.djvu")
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(book("b", file, Format.DJVU))))
        runCurrent()
        vm.onOpen("b")
        runCurrent()

        vm.onOpenFailed()

        assertNull(vm.state.value.open)
        assertEquals(LibraryMessage.NoApp, vm.state.value.message)
        vm.onMessageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `delete asks for confirmation and keeps everything when declined`() = runVmTest {
        val file = bookFile("Книга.epub")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", file)))
        val vm = viewModel(repository)
        runCurrent()

        vm.onDeleteRequest("b")
        assertEquals("b", vm.state.value.deleteCandidate?.id)
        vm.onDeleteDismiss()
        runCurrent()

        assertNull(vm.state.value.deleteCandidate)
        assertTrue(file.exists())
        assertEquals(1, repository.all.size)
    }

    @Test
    fun `confirmed delete removes the files and the record`() = runVmTest {
        val folder = File(dir, "Audiobooks/Автор - Книга")
        File(folder, "CD1/01.mp3").apply { parentFile?.mkdirs() }.writeText("x")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(audio("a", folder)))
        val vm = viewModel(repository)
        runCurrent()

        vm.onDeleteRequest("a")
        vm.onDeleteConfirm()
        runCurrent()

        assertFalse(folder.exists())
        assertTrue(File(dir, "Audiobooks").exists())
        assertTrue(repository.all.isEmpty())
        assertTrue(vm.state.value.items.isEmpty())
        assertNull(vm.state.value.deleteCandidate)
        assertEquals(emptySet<String>(), vm.state.value.busy)
    }

    @Test
    fun `delete of a record whose file is already gone just removes the record`() = runVmTest {
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", File(dir, "Books/gone.epub"))))
        val vm = viewModel(repository)
        runCurrent()

        vm.onDeleteRequest("b")
        vm.onDeleteConfirm()
        runCurrent()

        assertTrue(repository.all.isEmpty())
        assertNull(vm.state.value.message)
    }

    @Test
    fun `delete without file access keeps the record and says so`() = runVmTest {
        val file = bookFile("Книга.epub")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", file)))
        storage.hasAccess = false
        val vm = viewModel(repository)
        runCurrent()

        vm.onDeleteRequest("b")
        vm.onDeleteConfirm()
        runCurrent()

        assertTrue(file.exists())
        assertEquals(1, repository.all.size)
        assertEquals(ErrorCode.STORAGE_NOT_GRANTED, (vm.state.value.message as LibraryMessage.DeleteFailed).error.code)
    }

    @Test
    fun `delete that leaves the file on disk keeps the record`() = runVmTest {
        val file = bookFile("Книга.epub")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", file)))
        val stubborn = object : FileStorage by storage {
            override suspend fun delete(kind: MediaKind, path: String) = Unit
        }
        val vm = viewModel(repository, fileStorage = stubborn)
        runCurrent()

        vm.onDeleteRequest("b")
        vm.onDeleteConfirm()
        runCurrent()

        assertEquals(1, repository.all.size)
        assertEquals(ErrorCode.STORAGE_FAILED, (vm.state.value.message as LibraryMessage.DeleteFailed).error.code)
    }

    @Test
    fun `delete never removes a library root`() = runVmTest {
        val root = File(dir, "Audiobooks").apply { mkdirs() }
        File(root, "other/01.mp3").apply { parentFile?.mkdirs() }.writeText("x")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(audio("a", root)))
        val vm = viewModel(repository)
        runCurrent()

        vm.onDeleteRequest("a")
        vm.onDeleteConfirm()
        runCurrent()

        assertTrue(File(root, "other/01.mp3").exists())
        assertEquals(1, repository.all.size)
        assertEquals(ErrorCode.STORAGE_FAILED, (vm.state.value.message as LibraryMessage.DeleteFailed).error.code)
    }

    @Test
    fun `remove from history keeps the files`() = runVmTest {
        val file = bookFile("Книга.epub")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", file)))
        val vm = viewModel(repository)
        runCurrent()

        vm.onForget("b")
        runCurrent()

        assertTrue(file.exists())
        assertTrue(repository.all.isEmpty())
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `delete dialog closes when the download leaves the list`() = runVmTest {
        val file = bookFile("Книга.epub")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", file)))
        val vm = viewModel(repository)
        runCurrent()

        vm.onDeleteRequest("b")
        repository.delete("b")
        runCurrent()

        assertNull(vm.state.value.deleteCandidate)
    }

    @Test
    fun `seeding downloads are listed with completed ones`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", bookFile("Книга.epub")), seeding("s", folder)))
        val vm = viewModel(repository)
        runCurrent()

        val items = vm.state.value.items.associate { it.id to it.status }
        assertEquals(mapOf("b" to DownloadStatus.COMPLETED, "s" to DownloadStatus.SEEDING), items)
        assertFalse(vm.state.value.items.first { it.id == "s" }.fileMissing)
    }

    @Test
    fun `end of seeding updates the entry in place`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(book("b", bookFile("Книга.epub")), seeding("s", folder)))
        val vm = viewModel(repository)
        runCurrent()
        val before = vm.state.value.items.map { it.id }

        repository.update(repository.get("s")!!.copy(status = DownloadStatus.COMPLETED, seedUntil = null))
        runCurrent()

        assertEquals(before, vm.state.value.items.map { it.id })
        assertEquals(DownloadStatus.COMPLETED, vm.state.value.items.first { it.id == "s" }.status)
    }

    @Test
    fun `delete of a seeding download stops seeding before deleting the files`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(seeding("s", folder)))
        val vm = viewModel(repository, stopSeeding = queueStop(repository, folder))
        runCurrent()

        vm.onDeleteRequest("s")
        vm.onDeleteConfirm()
        runCurrent()

        assertEquals(listOf("s"), stopped)
        assertFalse(folder.exists())
        assertTrue(repository.all.isEmpty())
        assertNull(vm.state.value.message)
    }

    @Test
    fun `failed stop of seeding keeps the files and the record`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(seeding("s", folder)))
        val vm = viewModel(repository, stopSeeding = { throw AppError.StorageFailed("busy") })
        runCurrent()

        vm.onDeleteRequest("s")
        vm.onDeleteConfirm()
        runCurrent()

        assertTrue(folder.exists())
        assertEquals(1, repository.all.size)
        assertEquals(ErrorCode.STORAGE_FAILED, (vm.state.value.message as LibraryMessage.DeleteFailed).error.code)
    }

    @Test
    fun `delete of a completed download does not touch seeding`() = runVmTest {
        val file = bookFile("Книга.epub")
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(book("b", file))))
        runCurrent()

        vm.onDeleteRequest("b")
        vm.onDeleteConfirm()
        runCurrent()

        assertFalse(file.exists())
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `remove from history of a seeding download stops seeding and keeps the files`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(seeding("s", folder)))
        val vm = viewModel(repository, stopSeeding = queueStop(repository, folder))
        runCurrent()

        vm.onForget("s")
        runCurrent()

        assertEquals(listOf("s"), stopped)
        assertTrue(folder.exists())
        assertTrue(repository.all.isEmpty())
    }

    @Test
    fun `stop seeding leaves the entry in the library as completed`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val repository = FakeDownloadRepository(storage = storage, initial = listOf(seeding("s", folder)))
        val vm = viewModel(repository, stopSeeding = queueStop(repository, folder))
        runCurrent()

        vm.onStopSeeding("s")
        runCurrent()

        assertEquals(listOf("s"), stopped)
        assertTrue(folder.exists())
        assertEquals(DownloadStatus.COMPLETED, vm.state.value.items.single().status)
        assertEquals(emptySet<String>(), vm.state.value.busy)
    }

    @Test
    fun `stop seeding of a completed download does nothing`() = runVmTest {
        val vm = viewModel(FakeDownloadRepository(storage = storage, initial = listOf(book("b", bookFile("Книга.epub")))))
        runCurrent()

        vm.onStopSeeding("b")
        runCurrent()

        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `failed stop of seeding becomes a message`() = runVmTest {
        val folder = audioFolder("Автор - Книга")
        val vm = viewModel(
            FakeDownloadRepository(storage = storage, initial = listOf(seeding("s", folder))),
            stopSeeding = { throw AppError.StorageFailed("busy") },
        )
        runCurrent()

        vm.onStopSeeding("s")
        runCurrent()

        assertEquals(ErrorCode.STORAGE_FAILED, (vm.state.value.message as LibraryMessage.StopSeedingFailed).error.code)
    }

    private fun audioFolder(name: String): File =
        File(dir, "Audiobooks/$name").apply { File(this, "01.mp3").apply { parentFile?.mkdirs() }.writeText("x") }

    private fun seeding(id: String, folder: File) = audio(id, folder).copy(
        status = DownloadStatus.SEEDING,
        seedUntil = NOW.plusSeconds(3600),
    )

    private fun bookFile(name: String): File =
        File(dir, "Books/$name").apply { parentFile?.mkdirs() }.apply { writeText("book") }

    private fun book(id: String, file: File, format: Format = Format.EPUB) = Download(
        id = id,
        kind = MediaKind.BOOK,
        source = Source.FLIBUSTA,
        sourceId = id,
        title = file.nameWithoutExtension,
        author = "Михаил Булгаков",
        format = format,
        status = DownloadStatus.COMPLETED,
        paths = listOf(file.relativeTo(File(dir, "Books")).invariantSeparatorsPath),
        size = 1000,
        createdAt = NOW,
        completedAt = NOW,
    )

    private fun audio(id: String, folder: File) = Download(
        id = id,
        kind = MediaKind.AUDIO,
        source = Source.RUTRACKER,
        sourceId = id,
        title = folder.name,
        author = "",
        targetName = folder.name,
        status = DownloadStatus.COMPLETED,
        paths = listOf(folder.relativeTo(File(dir, "Audiobooks")).invariantSeparatorsPath),
        createdAt = NOW,
        completedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-09T10:00:00Z")
    }
}
