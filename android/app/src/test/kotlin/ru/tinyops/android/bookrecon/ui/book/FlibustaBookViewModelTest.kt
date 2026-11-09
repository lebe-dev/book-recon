package ru.tinyops.android.bookrecon.ui.book

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadQueue
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeFlibustaSource
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlibustaBookViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val flibusta = FakeFlibustaSource(behavior = FakeBehavior(1.seconds))
    private val settings = FakeSettingsRepository(Settings(preferredFormat = Format.EPUB))
    private val existing = mutableSetOf<String>()
    private var repository = FakeDownloadRepository(fileExists = { it in existing })

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.queue() = FakeDownloadQueue(repository, backgroundScope)

    private fun TestScope.viewModel(
        bookId: String = MASTER,
        queue: FakeDownloadQueue = queue(),
        handle: SavedStateHandle = SavedStateHandle(),
    ) = FlibustaBookViewModel(bookId, flibusta, settings, repository, queue, handle)

    @Test
    fun `shows loading, then the book with the preferred format selected`() = runVmTest {
        val vm = viewModel()
        runCurrent()
        assertEquals(BookDetailsState.Loading, vm.state.value.details)

        advanceUntilIdle()

        val loaded = vm.state.value.details as BookDetailsState.Loaded
        assertEquals("Мастер и Маргарита", loaded.book.title)
        assertEquals("Михаил Булгаков", loaded.book.author)
        assertEquals(listOf(Format.FB2, Format.EPUB, Format.MOBI), loaded.book.formats)
        assertEquals(Format.EPUB, vm.state.value.selectedFormat)
        assertNull(vm.state.value.downloaded)
    }

    @Test
    fun `falls back to the first format when the preferred one is missing`() = runVmTest {
        settings.update { it.copy(preferredFormat = Format.DJVU) }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(Format.FB2, vm.state.value.selectedFormat)
    }

    @Test
    fun `user can pick another offered format, but not a missing one`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onFormatSelected(Format.MOBI)
        assertEquals(Format.MOBI, vm.state.value.selectedFormat)

        vm.onFormatSelected(Format.PDF)
        assertEquals(Format.MOBI, vm.state.value.selectedFormat)
    }

    @Test
    fun `selected format survives process death`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        advanceUntilIdle()
        vm.onFormatSelected(Format.MOBI)

        val restored = viewModel(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        advanceUntilIdle()

        assertEquals(Format.MOBI, restored.state.value.selectedFormat)
    }

    @Test
    fun `a book without downloads cannot be downloaded`() = runVmTest {
        val noDownloads = FakeFlibustaSource(listOf(FlibustaBookDetails("77777", "Изъятая", "Автор", emptyList())))
        val vm = FlibustaBookViewModel("77777", noDownloads, settings, repository, queue(), SavedStateHandle())
        advanceUntilIdle()

        assertInstanceOf(BookDetailsState.Loaded::class.java, vm.state.value.details)
        assertNull(vm.state.value.selectedFormat)
        assertFalse(vm.state.value.canDownload)

        vm.onDownload()
        advanceUntilIdle()
        assertTrue(repository.all.isEmpty())
    }

    @Test
    fun `download enqueues the book in the selected format and reports it`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onFormatSelected(Format.FB2)

        vm.onDownload()
        advanceUntilIdle()

        val record = repository.all.single()
        assertEquals(Source.FLIBUSTA, record.source)
        assertEquals(MASTER, record.sourceId)
        assertEquals("Мастер и Маргарита", record.title)
        assertEquals("Михаил Булгаков", record.author)
        assertEquals(Format.FB2, record.format)
        assertEquals(BookMessage.Added, vm.state.value.message)
        assertFalse(vm.state.value.enqueuing)

        vm.onMessageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `download is not sent twice while the first request runs`() = runVmTest {
        val queue = queue().apply { behavior.delay = 1.seconds }
        val vm = viewModel(queue = queue)
        advanceUntilIdle()

        vm.onDownload()
        runCurrent()
        assertTrue(vm.state.value.enqueuing)
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(1, repository.all.size)
    }

    @Test
    fun `missing storage access or root leads to settings`() = runVmTest {
        val queue = queue()
        val vm = viewModel(queue = queue)
        advanceUntilIdle()

        queue.behavior.failNext(AppError.StorageNotGranted())
        vm.onDownload()
        advanceUntilIdle()
        val notGranted = vm.state.value.message as BookMessage.EnqueueFailed
        assertEquals(AppError.StorageNotGranted(), notGranted.error)
        assertEquals(BookMessageAction.SETTINGS, notGranted.action)

        vm.onMessageShown()
        queue.behavior.failNext(AppError.RootNotConfigured(MediaKind.BOOK))
        vm.onDownload()
        advanceUntilIdle()
        val noRoot = vm.state.value.message as BookMessage.EnqueueFailed
        assertEquals(BookMessageAction.SETTINGS, noRoot.action)
        assertTrue(repository.all.isEmpty())
    }

    @Test
    fun `already downloading points to the downloads tab`() = runVmTest {
        val queue = queue()
        val vm = viewModel(queue = queue)
        advanceUntilIdle()

        queue.behavior.failNext(AppError.AlreadyDownloading())
        vm.onDownload()
        advanceUntilIdle()

        val message = vm.state.value.message as BookMessage.EnqueueFailed
        assertEquals(BookMessageAction.DOWNLOADS, message.action)
    }

    @Test
    fun `other enqueue errors offer a retry`() = runVmTest {
        val queue = queue()
        val vm = viewModel(queue = queue)
        advanceUntilIdle()

        queue.behavior.failNext(AppError.StorageFailed())
        vm.onDownload()
        advanceUntilIdle()

        val message = vm.state.value.message as BookMessage.EnqueueFailed
        assertEquals(BookMessageAction.RETRY, message.action)
        assertFalse(vm.state.value.enqueuing)
    }

    @Test
    fun `network error is shown and retry loads the book`() = runVmTest {
        flibusta.behavior.failNext(AppError.Network())
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(BookDetailsState.Failed(AppError.Network()), vm.state.value.details)
        assertNull(vm.state.value.pendingVerification)

        vm.onRetry()
        runCurrent()
        assertEquals(BookDetailsState.Loading, vm.state.value.details)
        advanceUntilIdle()
        assertInstanceOf(BookDetailsState.Loaded::class.java, vm.state.value.details)
    }

    @Test
    fun `unknown book is reported as not found`() = runVmTest {
        val vm = viewModel(bookId = "99999")
        advanceUntilIdle()

        val failed = vm.state.value.details as BookDetailsState.Failed
        assertEquals(AppError.NotFound(), failed.error)
    }

    @Test
    fun `Cloudflare challenge opens verification once and a passed check reloads`() = runVmTest {
        flibusta.behavior.failNext(AppError.CloudflareChallenge(CHALLENGE_URL))
        val vm = viewModel()
        advanceUntilIdle()

        val request = vm.state.value.pendingVerification!!
        assertEquals(CHALLENGE_URL, request.url)
        assertEquals(VerificationPurpose.CLOUDFLARE, request.purpose)
        assertEquals(BookDetailsState.Failed(AppError.CloudflareChallenge(CHALLENGE_URL)), vm.state.value.details)

        vm.onVerificationOpened()
        assertNull(vm.state.value.pendingVerification)

        vm.onVerificationResult(true)
        advanceUntilIdle()

        assertInstanceOf(BookDetailsState.Loaded::class.java, vm.state.value.details)
        assertNull(vm.state.value.pendingVerification)
    }

    @Test
    fun `failed check keeps the error and does not reopen the check by itself`() = runVmTest {
        flibusta.behavior.failNext(AppError.CloudflareChallenge(CHALLENGE_URL))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onVerificationOpened()

        vm.onVerificationResult(false)
        advanceUntilIdle()

        assertInstanceOf(BookDetailsState.Failed::class.java, vm.state.value.details)
        assertNull(vm.state.value.pendingVerification)
    }

    @Test
    fun `completed download with the file on disk marks the book`() = runVmTest {
        repository = FakeDownloadRepository(listOf(completed(MASTER, "/books/master.epub")), fileExists = { it in existing })
        existing += "/books/master.epub"
        val vm = viewModel()
        advanceUntilIdle()

        val downloaded = vm.state.value.downloaded!!
        assertEquals(Format.EPUB, downloaded.format)
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `completed download whose file is gone does not mark the book`() = runVmTest {
        repository = FakeDownloadRepository(listOf(completed(MASTER, "/books/master.epub")), fileExists = { it in existing })
        val vm = viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.downloaded)
    }

    @Test
    fun `book downloaded again is enqueued anew`() = runVmTest {
        repository = FakeDownloadRepository(listOf(completed(MASTER, "/books/master.epub")), fileExists = { it in existing })
        existing += "/books/master.epub"
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        assertEquals(BookMessage.Added, vm.state.value.message)
        assertEquals(1, repository.all.count { it.status != DownloadStatus.COMPLETED })
    }

    @Test
    fun `download request carries the book details`() {
        val book = FakeData.flibustaBooks.first { it.id == MASTER }
        assertEquals(
            DownloadRequest.FlibustaBook(MASTER, book.title, book.author, Format.MOBI),
            book.toRequest(Format.MOBI),
        )
    }

    private fun completed(bookId: String, path: String): Download {
        val now = Instant.parse("2026-11-01T09:00:00Z")
        return Download(
            id = "done-$bookId",
            kind = MediaKind.BOOK,
            source = Source.FLIBUSTA,
            sourceId = bookId,
            title = "Мастер и Маргарита",
            author = "Михаил Булгаков",
            format = Format.EPUB,
            status = DownloadStatus.COMPLETED,
            paths = listOf(path),
            createdAt = now,
            completedAt = now,
        )
    }

    private companion object {
        const val MASTER = "11111"
        const val CHALLENGE_URL = "https://flibusta.is/b/11111"
    }
}
