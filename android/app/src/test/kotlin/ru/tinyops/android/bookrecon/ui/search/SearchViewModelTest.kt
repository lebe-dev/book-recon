package ru.tinyops.android.bookrecon.ui.search

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeFlibustaSource
import ru.tinyops.android.bookrecon.core.fake.FakeRuTrackerClient
import ru.tinyops.android.bookrecon.core.fake.FakeSearchHistory
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.usecase.search.SearchUseCase
import ru.tinyops.android.bookrecon.core.usecase.search.SourceState
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val flibusta = FakeFlibustaSource(behavior = FakeBehavior(1.seconds))
    private val ruTracker = FakeRuTrackerClient(behavior = FakeBehavior(2.seconds))
    private val settings = FakeSettingsRepository(Settings(rutrackerLogin = "reader"), password = "secret")
    private var downloads = FakeDownloadRepository(fileExists = { true })
    private val history = FakeSearchHistory()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val chosen = MutableStateFlow(MediaKind.entries.toSet())

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        SearchViewModel(SearchUseCase(flibusta, ruTracker, settings, downloads), history, handle, chosen)

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun `a kind without a chosen folder can be opened to see why, but is not searched`() = runVmTest {
        chosen.value = setOf(MediaKind.BOOK)
        val vm = viewModel()
        runCurrent()
        vm.onQueryChange("Булгаков")
        vm.onSearch()
        runCurrent()

        vm.onKindChange(MediaKind.AUDIO)
        runCurrent()
        vm.onSearch()
        runCurrent()

        assertEquals(MediaKind.AUDIO, vm.state.value.kind)
        assertEquals(setOf(MediaKind.BOOK), vm.state.value.chosenKinds)
        assertNull(vm.state.value.search)
    }

    @Test
    fun `the tab moves to a kind that has a folder when its own folder is gone`() = runVmTest {
        val vm = viewModel()
        runCurrent()
        vm.onQueryChange("Булгаков")
        vm.onSearch()
        runCurrent()

        chosen.value = setOf(MediaKind.AUDIO)
        advanceUntilIdle()

        assertEquals(MediaKind.AUDIO, vm.state.value.kind)
        assertTrue(vm.state.value.search != null)
    }

    @Test
    fun `without any folder nothing is searched`() = runVmTest {
        chosen.value = emptySet()
        val vm = viewModel()
        runCurrent()

        vm.onQueryChange("Булгаков")
        vm.onSearch()
        advanceUntilIdle()

        assertNull(vm.state.value.search)
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test
    fun `choosing the missing folder resumes the submitted search`() = runVmTest {
        val vm = viewModel()
        runCurrent()
        vm.onQueryChange("Булгаков")
        vm.onSearch()
        runCurrent()
        chosen.value = emptySet()
        runCurrent()
        assertNull(vm.state.value.search)

        chosen.value = setOf(MediaKind.BOOK)
        advanceUntilIdle()

        assertTrue(vm.state.value.search!!.results.isNotEmpty())
    }

    @Test
    fun `starts empty on the books tab`() = runVmTest {
        val vm = viewModel()

        val state = vm.state.value
        assertEquals("", state.query)
        assertEquals(MediaKind.BOOK, state.kind)
        assertNull(state.search)
        assertFalse(state.queryTooShort)
    }

    @Test
    fun `submitted queries go to the history, newest first`() = runVmTest {
        val vm = viewModel()
        runCurrent()

        vm.onQueryChange("Токарчук")
        vm.onSearch()
        vm.onQueryChange("Пелевин")
        vm.onSearch()
        vm.onQueryChange("а")
        vm.onSearch()
        runCurrent()

        assertEquals(listOf("Пелевин", "Токарчук"), vm.state.value.history)
    }

    @Test
    fun `choosing a history entry searches it`() = runVmTest {
        history.add("Веди свой плуг")
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("Веди свой плуг"), vm.state.value.history)

        vm.onHistorySelect("Веди свой плуг")
        runCurrent()

        assertEquals("Веди свой плуг", vm.state.value.query)
        assertEquals("Веди свой плуг", vm.state.value.submittedQuery)
        assertTrue(vm.state.value.search != null)
    }

    @Test
    fun `a history entry can be removed`() = runVmTest {
        history.add("Пелевин")
        history.add("Токарчук")
        val vm = viewModel()
        runCurrent()

        vm.onHistoryRemove("Пелевин")
        runCurrent()

        assertEquals(listOf("Токарчук"), vm.state.value.history)
    }

    @Test
    fun `too short query is rejected without a search`() = runVmTest {
        val vm = viewModel()

        vm.onQueryChange(" а ")
        vm.onSearch()
        advanceUntilIdle()

        assertTrue(vm.state.value.queryTooShort)
        assertNull(vm.state.value.search)

        vm.onQueryChange("ав")
        assertFalse(vm.state.value.queryTooShort)
    }

    @Test
    fun `book search shows every source loading, then results of both`() = runVmTest {
        val vm = viewModel()

        vm.onQueryChange("Достоевский")
        vm.onSearch()
        runCurrent()

        val loading = vm.state.value.search!!
        assertEquals(SourceState.Loading, loading.sourceState(Source.FLIBUSTA))
        assertEquals(SourceState.Loading, loading.sourceState(Source.RUTRACKER))

        advanceTimeByAndRun(1.5)
        val partial = vm.state.value.search!!
        assertEquals(listOf("22222"), partial.resultsOf(Source.FLIBUSTA).map { it.sourceId })
        assertEquals(SourceState.Loading, partial.sourceState(Source.RUTRACKER))

        advanceUntilIdle()
        val final = vm.state.value.search!!
        assertTrue(final.isComplete)
        assertEquals(listOf("22222", FakeData.BOOK_TOPIC_FORMATS), final.results.map { it.sourceId })
    }

    @Test
    fun `switching to audiobooks repeats the last search on RuTracker only`() = runVmTest {
        val vm = viewModel()
        vm.onQueryChange("Мастер")
        vm.onSearch()
        advanceUntilIdle()

        vm.onQueryChange("что-то недописанное")
        vm.onKindChange(MediaKind.AUDIO)
        advanceUntilIdle()

        val search = vm.state.value.search!!
        assertEquals(MediaKind.AUDIO, search.kind)
        assertEquals("Мастер", search.query)
        assertEquals(listOf(Source.RUTRACKER), search.sources.keys.toList())
        assertEquals(listOf(FakeData.AUDIO_TOPIC_CD), search.results.map { it.sourceId })
    }

    @Test
    fun `switching the tab before any search does not search`() = runVmTest {
        val vm = viewModel()

        vm.onKindChange(MediaKind.AUDIO)
        advanceUntilIdle()

        assertEquals(MediaKind.AUDIO, vm.state.value.kind)
        assertNull(vm.state.value.search)
    }

    @Test
    fun `a new search replaces the running one`() = runVmTest {
        val vm = viewModel()
        vm.onQueryChange("Достоевский")
        vm.onSearch()
        runCurrent()

        vm.onQueryChange("Булгаков")
        vm.onSearch()
        advanceUntilIdle()

        val search = vm.state.value.search!!
        assertEquals("Булгаков", search.query)
        assertEquals(listOf("11111", "66666"), search.resultsOf(Source.FLIBUSTA).map { it.sourceId })
    }

    @Test
    fun `failed source keeps the results of the others`() = runVmTest {
        ruTracker.behavior.error = AppError.RuTrackerAuthRequired()
        val vm = viewModel()

        vm.onQueryChange("Достоевский")
        vm.onSearch()
        advanceUntilIdle()

        val search = vm.state.value.search!!
        assertEquals(listOf("22222"), search.results.map { it.sourceId })
        assertEquals(AppError.RuTrackerAuthRequired(), search.errors[Source.RUTRACKER])
        assertNull(vm.state.value.pendingVerification)
    }

    @Test
    fun `Cloudflare challenge opens verification once and a passed check repeats the search`() = runVmTest {
        ruTracker.behavior.failNext(AppError.CloudflareChallenge("https://rutracker.org/forum/tracker.php"))
        val vm = viewModel()

        vm.onQueryChange("Достоевский")
        vm.onSearch()
        advanceUntilIdle()

        val request = vm.state.value.pendingVerification!!
        assertEquals("https://rutracker.org/forum/tracker.php", request.url)
        assertEquals(VerificationPurpose.CLOUDFLARE, request.purpose)
        assertEquals(listOf("22222"), vm.state.value.search!!.results.map { it.sourceId })

        vm.onVerificationOpened()
        assertNull(vm.state.value.pendingVerification)

        vm.onVerificationResult(true)
        advanceUntilIdle()

        val search = vm.state.value.search!!
        assertTrue(search.isComplete)
        assertNull(search.verification)
        assertEquals(listOf("22222", FakeData.BOOK_TOPIC_FORMATS), search.results.map { it.sourceId })
        assertNull(vm.state.value.pendingVerification)
    }

    @Test
    fun `verification is not opened again until the next search`() = runVmTest {
        ruTracker.behavior.error = AppError.CloudflareChallenge("https://rutracker.org/forum/tracker.php")
        flibusta.behavior.delay = 3.seconds
        val vm = viewModel()

        vm.onQueryChange("Достоевский")
        vm.onSearch()
        advanceTimeByAndRun(2.5)
        assertTrue(vm.state.value.pendingVerification != null)
        vm.onVerificationOpened()

        // Flibusta answers later: the snapshot still carries the verification, but it is not reopened.
        advanceUntilIdle()
        assertNull(vm.state.value.pendingVerification)

        vm.onVerificationResult(false)
        advanceUntilIdle()
        assertNull(vm.state.value.pendingVerification)

        vm.onSearch()
        advanceUntilIdle()
        assertTrue(vm.state.value.pendingVerification != null)
    }

    @Test
    fun `failed check does not repeat the search`() = runVmTest {
        ruTracker.behavior.failNext(AppError.CloudflareChallenge("https://rutracker.org/forum/tracker.php"))
        val vm = viewModel()
        vm.onQueryChange("Достоевский")
        vm.onSearch()
        advanceUntilIdle()
        vm.onVerificationOpened()
        val before = vm.state.value.search

        vm.onVerificationResult(false)
        advanceUntilIdle()

        assertEquals(before, vm.state.value.search)
    }

    @Test
    fun `RuTracker captcha opens the login page`() = runVmTest {
        ruTracker.loggedIn = false
        ruTracker.captcha = true
        val vm = viewModel()

        vm.onQueryChange("Мастер")
        vm.onKindChange(MediaKind.AUDIO)
        vm.onSearch()
        advanceUntilIdle()

        val request = vm.state.value.pendingVerification!!
        assertEquals(FakeRuTrackerClient.LOGIN_URL, request.url)
        assertEquals(VerificationPurpose.RUTRACKER_LOGIN, request.purpose)
    }

    @Test
    fun `retry repeats the last search`() = runVmTest {
        ruTracker.behavior.failNext(AppError.Network())
        val vm = viewModel()
        vm.onQueryChange("Достоевский")
        vm.onSearch()
        advanceUntilIdle()
        assertEquals(AppError.Network(), vm.state.value.search!!.errors[Source.RUTRACKER])

        vm.onRetry()
        advanceUntilIdle()

        assertTrue(vm.state.value.search!!.errors.isEmpty())
    }

    @Test
    fun `already downloaded results are marked`() = runVmTest {
        downloads = FakeDownloadRepository(listOf(completed("22222")), fileExists = { true })
        val vm = viewModel()

        vm.onQueryChange("Достоевский")
        vm.onSearch()
        advanceUntilIdle()

        val flibustaResults = vm.state.value.search!!.resultsOf(Source.FLIBUSTA)
        assertTrue(flibustaResults.single().alreadyDownloaded)
    }

    @Test
    fun `query, tab and last search are kept in saved state`() = runVmTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)

        vm.onQueryChange("Мастер")
        vm.onKindChange(MediaKind.AUDIO)
        vm.onSearch()
        advanceUntilIdle()

        val restored = viewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals("Мастер", restored.state.value.query)
        assertEquals(MediaKind.AUDIO, restored.state.value.kind)

        advanceUntilIdle()
        val search = restored.state.value.search!!
        assertEquals("Мастер", search.query)
        assertEquals(MediaKind.AUDIO, search.kind)
        assertEquals(listOf(FakeData.AUDIO_TOPIC_CD), search.results.map { it.sourceId })
    }

    private fun TestScope.advanceTimeByAndRun(seconds: Double) {
        testScheduler.advanceTimeBy((seconds * 1000).toLong())
        testScheduler.runCurrent()
    }

    private fun completed(bookId: String): Download {
        val now = Instant.parse("2026-11-01T09:00:00Z")
        return FakeData.library(now).first { it.source == Source.FLIBUSTA }.copy(
            id = "done-$bookId",
            sourceId = bookId,
            status = DownloadStatus.COMPLETED,
            completedAt = now,
        )
    }
}
