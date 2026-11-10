package ru.tinyops.android.bookrecon.ui.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadQueue
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.queue(repository: FakeDownloadRepository, maxConcurrent: Int = 2) =
        FakeDownloadQueue(
            repository = repository,
            scope = backgroundScope,
            maxConcurrent = { maxConcurrent },
            steps = 10,
            stepDelay = 1.seconds,
        )

    @Test
    fun `shows loading until the queue answers, then the empty state`() = runVmTest {
        val vm = DownloadsViewModel(queue(FakeDownloadRepository()))
        assertTrue(vm.state.value.loading)

        runCurrent()

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun `splits the queue into active, queued, seeding and failed sections`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(
                record("q2", DownloadStatus.QUEUED, position = 2, minute = 1),
                record("run", DownloadStatus.DOWNLOADING, minute = 2),
                record("old-fail", DownloadStatus.FAILED, error = AppError.NoSeeders(), minute = 3),
                record("q1", DownloadStatus.QUEUED, position = 1, minute = 4),
                record("seed", DownloadStatus.SEEDING, seedUntil = Instant.parse("2026-11-09T18:00:00Z"), minute = 5),
                record("new-fail", DownloadStatus.FAILED, error = AppError.Stalled(), minute = 6),
                record("done", DownloadStatus.COMPLETED, minute = 7),
                record("old-cancel", DownloadStatus.CANCELLED, minute = 8),
                record("new-cancel", DownloadStatus.CANCELLED, minute = 9),
            ),
        )
        val vm = DownloadsViewModel(queue(repository))
        runCurrent()

        val state = vm.state.value
        assertEquals(listOf("run"), state.active.map { it.id })
        assertEquals(listOf("q1", "q2"), state.queued.map { it.id })
        assertEquals(listOf("seed"), state.seeding.map { it.id })
        assertEquals(listOf("new-fail", "old-fail"), state.failed.map { it.id })
        assertEquals(listOf("new-cancel", "old-cancel"), state.cancelled.map { it.id })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `follows live progress of a running download`() = runVmTest {
        val repository = FakeDownloadRepository()
        val queue = queue(repository)
        val vm = DownloadsViewModel(queue)
        runCurrent()

        queue.enqueue(BOOK_REQUEST)
        runCurrent()
        assertEquals(1, vm.state.value.active.size)
        assertEquals(0L, vm.state.value.active.single().progress.completed)

        advanceTimeBy(3.seconds)
        runCurrent()
        val progress = vm.state.value.active.single().progress
        assertTrue(progress.completed > 0)
        assertTrue(progress.fraction in 0.25f..0.35f)

        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun `cancel asks for confirmation and does nothing when the user declines`() = runVmTest {
        val repository = FakeDownloadRepository(listOf(record("q1", DownloadStatus.QUEUED, position = 1)))
        val vm = DownloadsViewModel(queue(repository, maxConcurrent = 0))
        runCurrent()

        vm.onCancelRequest("q1")
        assertEquals("q1", vm.state.value.cancelCandidate?.id)

        vm.onCancelDismiss()
        runCurrent()

        assertNull(vm.state.value.cancelCandidate)
        assertEquals(listOf("q1"), vm.state.value.queued.map { it.id })
        assertEquals(DownloadStatus.QUEUED, repository.all.single().status)
    }

    @Test
    fun `confirmed cancel removes a running download and starts the next one`() = runVmTest {
        val repository = FakeDownloadRepository()
        val queue = queue(repository, maxConcurrent = 1)
        val vm = DownloadsViewModel(queue)
        val first = queue.enqueue(BOOK_REQUEST)
        val second = queue.enqueue(BOOK_REQUEST.copy(bookId = "2"))
        runCurrent()
        assertEquals(listOf(first), vm.state.value.active.map { it.id })
        assertEquals(listOf(second), vm.state.value.queued.map { it.id })

        vm.onCancelRequest(first)
        vm.onCancelConfirm()
        runCurrent()

        assertNull(vm.state.value.cancelCandidate)
        assertEquals(listOf(second), vm.state.value.active.map { it.id })
        assertTrue(vm.state.value.queued.isEmpty())
        assertEquals(DownloadStatus.CANCELLED, repository.all.first { it.id == first }.status)
    }

    @Test
    fun `confirmation disappears when the download finishes meanwhile`() = runVmTest {
        val repository = FakeDownloadRepository()
        val queue = queue(repository)
        val vm = DownloadsViewModel(queue)
        val id = queue.enqueue(BOOK_REQUEST)
        runCurrent()

        vm.onCancelRequest(id)
        assertEquals(id, vm.state.value.cancelCandidate?.id)

        advanceTimeBy(11.seconds)
        runCurrent()
        assertNull(vm.state.value.cancelCandidate)

        vm.onCancelConfirm()
        runCurrent()
        assertEquals(DownloadStatus.COMPLETED, repository.all.single().status)
    }

    @Test
    fun `cancel is not offered for seeding or failed downloads`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(
                record("seed", DownloadStatus.SEEDING, seedUntil = Instant.parse("2026-11-09T18:00:00Z")),
                record("fail", DownloadStatus.FAILED, error = AppError.NoSeeders()),
            ),
        )
        val vm = DownloadsViewModel(queue(repository))
        runCurrent()

        vm.onCancelRequest("seed")
        assertNull(vm.state.value.cancelCandidate)
        vm.onCancelRequest("fail")
        assertNull(vm.state.value.cancelCandidate)
    }

    @Test
    fun `retry puts a failed download back into the queue`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(record("fail", DownloadStatus.FAILED, error = AppError.StorageNotGranted())),
        )
        val vm = DownloadsViewModel(queue(repository, maxConcurrent = 0))
        runCurrent()
        assertEquals(listOf("fail"), vm.state.value.failed.map { it.id })

        vm.onRetry("fail")
        runCurrent()

        assertTrue(vm.state.value.failed.isEmpty())
        val queued = vm.state.value.queued.single()
        assertEquals("fail", queued.id)
        assertNull(queued.error)
    }

    @Test
    fun `retry is refused while the folder of the download kind is not chosen`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(record("fail", DownloadStatus.FAILED, error = AppError.RootNotConfigured(MediaKind.AUDIO))),
        )
        val chosen = MutableStateFlow(setOf(MediaKind.BOOK))
        val vm = DownloadsViewModel(queue(repository, maxConcurrent = 0), chosen)
        runCurrent()

        vm.onRetry("fail")
        runCurrent()
        assertEquals(listOf("fail"), vm.state.value.failed.map { it.id })
        assertEquals(setOf(MediaKind.BOOK), vm.state.value.chosenKinds)

        chosen.value = MediaKind.entries.toSet()
        runCurrent()
        vm.onRetry("fail")
        runCurrent()
        assertEquals(listOf("fail"), vm.state.value.queued.map { it.id })
    }

    @Test
    fun `a cancelled download stays on the screen and can be started again`() = runVmTest {
        val repository = FakeDownloadRepository(listOf(record("q", DownloadStatus.QUEUED, position = 1)))
        val vm = DownloadsViewModel(queue(repository, maxConcurrent = 0))
        runCurrent()

        vm.onCancelRequest("q")
        vm.onCancelConfirm()
        runCurrent()

        assertTrue(vm.state.value.queued.isEmpty())
        assertEquals(listOf("q"), vm.state.value.cancelled.map { it.id })

        vm.onRetry("q")
        runCurrent()

        assertTrue(vm.state.value.cancelled.isEmpty())
        assertEquals(listOf("q"), vm.state.value.queued.map { it.id })
    }

    @Test
    fun `stop seeding completes the download and removes it from the screen`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(record("seed", DownloadStatus.SEEDING, seedUntil = Instant.parse("2026-11-09T18:00:00Z"))),
        )
        val vm = DownloadsViewModel(queue(repository))
        runCurrent()

        vm.onStopSeeding("seed")
        runCurrent()

        assertTrue(vm.state.value.isEmpty)
        assertEquals(DownloadStatus.COMPLETED, repository.all.single().status)
    }

    @Test
    fun `dismiss removes a failed download`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(record("fail", DownloadStatus.FAILED, error = AppError.Stalled())),
        )
        val vm = DownloadsViewModel(queue(repository))
        runCurrent()

        vm.onDismiss("fail")
        runCurrent()

        assertTrue(vm.state.value.isEmpty)
        assertTrue(repository.all.isEmpty())
    }

    @Test
    fun `a failed action shows a message and keeps the list`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(record("fail", DownloadStatus.FAILED, error = AppError.Stalled())),
        )
        val failing = FailingQueue(queue(repository), AppError.StorageFailed())
        val vm = DownloadsViewModel(failing)
        runCurrent()

        vm.onRetry("fail")
        runCurrent()

        assertEquals(DownloadsMessage(DownloadAction.RETRY, AppError.StorageFailed()), vm.state.value.message)
        assertEquals(listOf("fail"), vm.state.value.failed.map { it.id })
        assertTrue(vm.state.value.busy.isEmpty())

        vm.onMessageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `an action in flight marks the download busy and ignores repeated taps`() = runVmTest {
        val repository = FakeDownloadRepository(
            listOf(record("fail", DownloadStatus.FAILED, error = AppError.Stalled())),
        )
        val slow = SlowQueue(queue(repository, maxConcurrent = 0))
        val vm = DownloadsViewModel(slow)
        runCurrent()

        vm.onRetry("fail")
        runCurrent()
        assertEquals(setOf("fail"), vm.state.value.busy)
        vm.onRetry("fail")
        vm.onDismiss("fail")

        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(1, slow.calls)
        assertTrue(vm.state.value.busy.isEmpty())
        assertEquals(listOf("fail"), vm.state.value.queued.map { it.id })
    }

    @Test
    fun `a broken queue flow shows the load error instead of crashing`() = runVmTest {
        val broken = object : DownloadQueue by queue(FakeDownloadRepository()) {
            override fun observe(): Flow<List<Download>> = flow { throw AppError.StorageFailed() }
        }
        val vm = DownloadsViewModel(broken)
        runCurrent()

        assertFalse(vm.state.value.loading)
        assertEquals(AppError.StorageFailed(), vm.state.value.loadError)
    }

    /** Delegates to [delegate] but every action throws [error]. */
    private class FailingQueue(private val delegate: DownloadQueue, private val error: AppError) :
        DownloadQueue by delegate {
        override suspend fun cancel(id: String) = throw error
        override suspend fun retry(id: String) = throw error
        override suspend fun stopSeeding(id: String) = throw error
        override suspend fun dismiss(id: String) = throw error
    }

    /** Delegates to [delegate] after a one-second pause and counts the actions. */
    private class SlowQueue(private val delegate: DownloadQueue) : DownloadQueue by delegate {
        var calls = 0

        override suspend fun retry(id: String) {
            calls++
            kotlinx.coroutines.delay(1.seconds)
            delegate.retry(id)
        }

        override suspend fun dismiss(id: String) {
            calls++
            delegate.dismiss(id)
        }
    }

    private companion object {
        val BOOK_REQUEST = DownloadRequest.FlibustaBook("1", "Мастер и Маргарита", "Михаил Булгаков", Format.EPUB)

        fun record(
            id: String,
            status: DownloadStatus,
            position: Int? = null,
            error: AppError? = null,
            seedUntil: Instant? = null,
            minute: Int = 0,
        ) = Download(
            id = id,
            kind = MediaKind.AUDIO,
            source = Source.RUTRACKER,
            sourceId = id,
            title = "Раздача $id",
            author = "",
            status = status,
            progress = DownloadProgress(0, 1000),
            queuePosition = position,
            size = 1000,
            error = error,
            createdAt = Instant.parse("2026-11-09T10:00:00Z").plusSeconds(minute * 60L),
            seedUntil = seedUntil,
        )
    }
}
