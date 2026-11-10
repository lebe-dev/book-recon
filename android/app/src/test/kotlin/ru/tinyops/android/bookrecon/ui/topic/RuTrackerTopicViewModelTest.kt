package ru.tinyops.android.bookrecon.ui.topic

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
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadQueue
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeRuTrackerClient
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.fake.FakeTorrentEngine
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.selection.FolderState
import ru.tinyops.android.bookrecon.core.selection.TreeNode
import ru.tinyops.android.bookrecon.ui.book.BookMessage
import ru.tinyops.android.bookrecon.ui.book.BookMessageAction
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RuTrackerTopicViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = FakeRuTrackerClient(behavior = FakeBehavior(1.seconds))
    private val engine = FakeTorrentEngine()
    private val settings = FakeSettingsRepository(Settings(preferredFormat = Format.EPUB, booksRoot = "content://tree/books", audioRoot = "content://tree/audio"))
    private val repository = FakeDownloadRepository()

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
        topicId: String = FakeData.AUDIO_TOPIC_CD,
        kind: MediaKind = MediaKind.AUDIO,
        queue: FakeDownloadQueue = queue(),
        handle: SavedStateHandle = SavedStateHandle(),
    ) = RuTrackerTopicViewModel(
        topicId = topicId,
        kind = kind,
        title = titleOf(topicId),
        ruTracker = client,
        torrents = engine,
        settings = settings,
        queue = queue,
        savedState = handle,
        parseDispatcher = dispatcher,
    )

    private fun titleOf(topicId: String) = FakeData.topics.first { it.topicId == topicId }.title

    private fun RuTrackerTopicViewModel.folders(): List<TreeNode.Folder> = state.value.tree.filterIsInstance<TreeNode.Folder>()

    private fun RuTrackerTopicViewModel.files(): List<TreeNode.File> = state.value.tree.filterIsInstance<TreeNode.File>()

    private fun SavedStateHandle.copy() = SavedStateHandle(keys().associateWith { get<Any>(it) })

    @Test
    fun `audio topic shows loading, then the CD1 and CD2 tree with every audio file selected`() = runVmTest {
        val vm = viewModel()
        runCurrent()
        assertEquals(TopicLoadState.Loading, vm.state.value.load)
        assertFalse(vm.state.value.canDownload)

        advanceUntilIdle()

        assertInstanceOf(TopicLoadState.Loaded::class.java, vm.state.value.load)
        assertEquals(listOf("CD1", "CD2"), vm.folders().map { it.name })
        assertEquals(listOf(0, 0), vm.folders().map { it.depth })
        assertEquals(6, vm.files().size)
        assertTrue(vm.files().all { it.selected && it.name.endsWith(".mp3") })
        val selection = vm.state.value.selection!!
        assertEquals(6, selection.selectedCount)
        assertEquals(selection.totalSize, selection.selectedSize)
        assertEquals(FakeData.torrentInfo(FakeData.AUDIO_TOPIC_CD).totalSize, vm.state.value.torrentSize)
        assertEquals("Булгаков Михаил - Мастер и Маргарита", vm.state.value.folderName)
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `files inside a folder go in natural order`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()

        val cd1 = vm.state.value.tree.dropWhile { it.name != "CD1" }.drop(1).takeWhile { it is TreeNode.File }
        assertEquals(
            listOf(
                "01 Глава 1. Никогда не разговаривайте с неизвестными.mp3",
                "02 Глава 2. Понтий Пилат.mp3",
                "10 Глава 10. Вести из Ялты.mp3",
            ),
            cd1.map { it.name },
        )
        assertTrue(cd1.all { it.depth == 1 })
    }

    @Test
    fun `e-book topic preselects files of the preferred format`() = runVmTest {
        settings.update { it.copy(preferredFormat = Format.FB2) }
        val vm = viewModel(FakeData.BOOK_TOPIC_FORMATS, MediaKind.BOOK)
        advanceUntilIdle()

        assertEquals(3, vm.files().size)
        assertEquals(listOf("Достоевский - Преступление и наказание.fb2"), vm.files().filter { it.selected }.map { it.name })
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `toggling files and folders updates the tri-state folder and the totals`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()
        val first = vm.files().first()

        vm.onToggleFile(first.file.index)
        assertFalse(vm.files().first().selected)
        assertEquals(FolderState.Check.PARTIAL, vm.folders().first { it.name == "CD1" }.state.check)
        assertEquals(5, vm.state.value.selection!!.selectedCount)

        vm.onToggleFolder("Булгаков - Мастер и Маргарита/CD1")
        assertEquals(FolderState.Check.ALL, vm.folders().first { it.name == "CD1" }.state.check)

        vm.onToggleFolder("Булгаков - Мастер и Маргарита/CD1")
        assertEquals(FolderState.Check.NONE, vm.folders().first { it.name == "CD1" }.state.check)
        assertEquals(3, vm.state.value.selection!!.selectedCount)
        assertEquals(37L + 15 + 12, vm.state.value.selection!!.selectedSize / MB)
    }

    @Test
    fun `select none disables the download, select all enables it again`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSelectNone()
        assertEquals(0, vm.state.value.selection!!.selectedCount)
        assertFalse(vm.state.value.canDownload)
        vm.onDownload()
        advanceUntilIdle()
        assertTrue(repository.listByStatus(DownloadStatus.entries.toSet()).isEmpty())

        vm.onSelectAll()
        assertEquals(6, vm.state.value.selection!!.selectedCount)
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `collapsing a folder hides its files and keeps the selection`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onToggleExpanded("Булгаков - Мастер и Маргарита/CD1")

        assertEquals(listOf("CD1", "CD2"), vm.folders().map { it.name })
        assertFalse(vm.folders().first { it.name == "CD1" }.expanded)
        assertEquals(3, vm.files().size)
        assertEquals(6, vm.state.value.selection!!.selectedCount)

        vm.onToggleExpanded("Булгаков - Мастер и Маргарита/CD1")
        assertEquals(6, vm.files().size)
    }

    @Test
    fun `selection, collapsed folders and the folder name survive process death`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        advanceUntilIdle()
        vm.onToggleFolder("Булгаков - Мастер и Маргарита/CD2")
        vm.onToggleExpanded("Булгаков - Мастер и Маргарита/CD1")
        vm.onFolderNameChange("Булгаков - Мастер и Маргарита (Басилашвили)")
        val before = vm.state.value.selection!!.selectedIndexes

        val restored = viewModel(handle = handle.copy())
        advanceUntilIdle()

        assertEquals(before, restored.state.value.selection!!.selectedIndexes)
        assertEquals(setOf("Булгаков - Мастер и Маргарита/CD1"), restored.state.value.collapsed)
        assertEquals("Булгаков - Мастер и Маргарита (Басилашвили)", restored.state.value.folderName)
        assertEquals(3, restored.files().size)
    }

    @Test
    fun `an emptied selection is restored as empty`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        advanceUntilIdle()
        vm.onSelectNone()

        val restored = viewModel(handle = handle.copy())
        advanceUntilIdle()

        assertEquals(0, restored.state.value.selection!!.selectedCount)
    }

    @Test
    fun `download puts the selected files with the edited folder into the queue`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onToggleFolder("Булгаков - Мастер и Маргарита/CD2")
        vm.onFolderNameChange("  Мастер и Маргарита  ")

        vm.onDownload()
        assertTrue(vm.state.value.enqueuing)
        assertFalse(vm.state.value.canDownload)
        runCurrent()

        assertEquals(BookMessage.Added, vm.state.value.message)
        assertFalse(vm.state.value.enqueuing)
        val download = repository.listByStatus(DownloadStatus.entries.toSet()).single()
        assertEquals(Source.RUTRACKER, download.source)
        assertEquals(FakeData.AUDIO_TOPIC_CD, download.sourceId)
        assertEquals(MediaKind.AUDIO, download.kind)
        assertEquals("Мастер и Маргарита", download.targetName)
        assertEquals(titleOf(FakeData.AUDIO_TOPIC_CD), download.title)
        assertEquals(listOf(0, 1, 2), download.files.map { it.index })

        vm.onMessageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `e-book download uses the cleaned topic title as display name`() = runVmTest {
        val vm = viewModel(FakeData.BOOK_TOPIC_FORMATS, MediaKind.BOOK)
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        val download = repository.listByStatus(DownloadStatus.entries.toSet()).single()
        assertEquals(MediaKind.BOOK, download.kind)
        assertEquals("Достоевский Фёдор - Преступление и наказание", download.targetName)
    }

    @Test
    fun `audio download needs a folder name`() = runVmTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onFolderNameChange("   ")
        assertTrue(vm.state.value.folderNameMissing)
        assertFalse(vm.state.value.canDownload)
        vm.onDownload()
        advanceUntilIdle()

        assertTrue(repository.listByStatus(DownloadStatus.entries.toSet()).isEmpty())
    }

    @Test
    fun `download is blocked while the folder of its kind is not chosen`() = runVmTest {
        settings.update { it.copy(audioRoot = null) }
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.rootMissing)
        assertFalse(vm.state.value.canDownload)
        vm.onDownload()
        advanceUntilIdle()
        assertTrue(repository.listByStatus(DownloadStatus.entries.toSet()).isEmpty())

        settings.update { it.copy(audioRoot = "content://tree/audio") }
        advanceUntilIdle()
        assertFalse(vm.state.value.rootMissing)
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `a missing folder of the other kind does not block`() = runVmTest {
        settings.update { it.copy(booksRoot = null) }
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.rootMissing)
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `storage errors from the queue point to settings`() = runVmTest {
        val queue = queue()
        queue.behavior.failNext(AppError.RootNotConfigured(MediaKind.AUDIO))
        val vm = viewModel(queue = queue)
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        val message = vm.state.value.message as BookMessage.EnqueueFailed
        assertEquals(AppError.RootNotConfigured(MediaKind.AUDIO), message.error)
        assertEquals(BookMessageAction.SETTINGS, message.action)
        assertTrue(vm.state.value.canDownload)
    }

    @Test
    fun `a topic already in the queue points to downloads`() = runVmTest {
        val queue = queue()
        queue.behavior.failNext(AppError.AlreadyDownloading())
        val vm = viewModel(queue = queue)
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        assertEquals(BookMessageAction.DOWNLOADS, (vm.state.value.message as BookMessage.EnqueueFailed).action)
    }

    @Test
    fun `a topic without files of the kind says so`() = runVmTest {
        val vm = viewModel(FakeData.BOOK_TOPIC_FORMATS, MediaKind.AUDIO)
        advanceUntilIdle()

        assertEquals(TopicLoadState.Failed(AppError.NoMatchingFiles(MediaKind.AUDIO)), vm.state.value.load)
        assertNull(vm.state.value.pendingVerification)
        assertFalse(vm.state.value.canDownload)
    }

    @Test
    fun `missing credentials fail without a web check`() = runVmTest {
        client.behavior.failNext(AppError.RuTrackerAuthRequired())
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(TopicLoadState.Failed(AppError.RuTrackerAuthRequired()), vm.state.value.load)
        assertNull(vm.state.value.pendingVerification)
    }

    @Test
    fun `captcha opens the login page once and a passed check loads the topic`() = runVmTest {
        client.behavior.failNext(AppError.RuTrackerCaptcha(FakeRuTrackerClient.LOGIN_URL))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(TopicLoadState.Failed(AppError.RuTrackerCaptcha(FakeRuTrackerClient.LOGIN_URL)), vm.state.value.load)
        val pending = vm.state.value.pendingVerification!!
        assertEquals(FakeRuTrackerClient.LOGIN_URL, pending.url)
        assertEquals(VerificationPurpose.RUTRACKER_LOGIN, pending.purpose)

        vm.onVerificationOpened()
        assertNull(vm.state.value.pendingVerification)

        vm.onVerificationResult(true)
        advanceUntilIdle()
        assertInstanceOf(TopicLoadState.Loaded::class.java, vm.state.value.load)
    }

    @Test
    fun `cloudflare asks for a check and a failed check keeps the error`() = runVmTest {
        client.behavior.failNext(AppError.CloudflareChallenge("https://rutracker.org/forum/dl.php?t=6012345"))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(VerificationPurpose.CLOUDFLARE, vm.state.value.pendingVerification!!.purpose)
        vm.onVerificationOpened()

        vm.onVerificationResult(false)
        advanceUntilIdle()
        assertInstanceOf(TopicLoadState.Failed::class.java, vm.state.value.load)
        assertNull(vm.state.value.pendingVerification)
    }

    @Test
    fun `retry after a network error loads the topic and keeps the restored selection`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        advanceUntilIdle()
        vm.onToggleFolder("Булгаков - Мастер и Маргарита/CD1")

        client.behavior.failNext(AppError.Network())
        val restored = viewModel(handle = handle.copy())
        advanceUntilIdle()
        assertEquals(TopicLoadState.Failed(AppError.Network()), restored.state.value.load)

        restored.onRetry()
        runCurrent()
        assertEquals(TopicLoadState.Loading, restored.state.value.load)
        advanceUntilIdle()

        assertEquals(3, restored.state.value.selection!!.selectedCount)
    }

    @Test
    fun `a broken torrent file is a parse error`() = runVmTest {
        val vm = RuTrackerTopicViewModel(
            topicId = "1",
            kind = MediaKind.AUDIO,
            title = "Нет такой раздачи",
            ruTracker = NotATorrentClient(),
            torrents = engine,
            settings = settings,
            queue = queue(),
            savedState = SavedStateHandle(),
            parseDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(ErrorCode.PARSE_ERROR, (vm.state.value.load as TopicLoadState.Failed).error.code)
    }

    /** Returns bytes that are not a torrent. */
    private class NotATorrentClient : RuTrackerClient {
        override suspend fun login() = Unit

        override suspend fun isLoggedIn() = true

        override suspend fun search(query: String, kind: MediaKind, limit: Int) = emptyList<TorrentTopic>()

        override suspend fun fetchTorrent(topicId: String) = "<html>not a torrent</html>".toByteArray()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
