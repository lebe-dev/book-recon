package ru.tinyops.android.bookrecon.log

import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.ui.log.LogViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {
    @TempDir
    lateinit var dir: File

    private val dispatcher = StandardTestDispatcher()
    private val executor = Executors.newSingleThreadExecutor()
    private val settings = FakeSettingsRepository(Settings())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
    }

    private fun appLog() = AppLog(dir, Clock.System, ZoneOffset.UTC, executor = executor)

    @Test
    fun showsLevelsFromSettingsAndEntriesOfTheLog() = runTest(dispatcher) {
        val log = appLog().also { it.info("Queue", "hello") }
        val vm = LogViewModel(log, settings)
        advanceUntilIdle()

        assertTrue(vm.state.value.infoEnabled)
        assertFalse(vm.state.value.debugEnabled)
        assertEquals(listOf("hello"), vm.state.value.entries.map { it.message })
    }

    @Test
    fun levelSwitchesAreSavedInSettings() = runTest(dispatcher) {
        val vm = LogViewModel(appLog(), settings)
        advanceUntilIdle()

        vm.onDebugChange(true)
        vm.onInfoChange(false)
        advanceUntilIdle()

        assertTrue(settings.settings.first().logDebug)
        assertFalse(settings.settings.first().logInfo)
        assertTrue(vm.state.value.debugEnabled)
        assertFalse(vm.state.value.infoEnabled)
    }

    @Test
    fun levelSwitchesOnlyFilterTheShownEntries() = runTest(dispatcher) {
        val log = appLog().also {
            it.debug("Net") { "details" }
            it.info("Queue", "hello")
        }
        val vm = LogViewModel(log, settings)
        advanceUntilIdle()
        assertEquals(listOf("hello"), vm.state.value.entries.map { it.message })

        vm.onDebugChange(true)
        vm.onInfoChange(false)
        advanceUntilIdle()

        assertEquals(listOf("details"), vm.state.value.entries.map { it.message })
        assertTrue(vm.exportText().let { it.contains("D/Net: details") && it.contains("I/Queue: hello") })
    }

    @Test
    fun clearEmptiesTheLog() = runTest(dispatcher) {
        val log = appLog().also { it.info("Queue", "hello") }
        val vm = LogViewModel(log, settings)
        advanceUntilIdle()

        vm.onClear()
        advanceUntilIdle()

        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test
    fun exportTextHoldsTheWholeLogWithAHeader() = runTest(dispatcher) {
        val log = appLog().also { it.info("Queue", "hello") }
        val vm = LogViewModel(log, settings, header = { "Book Recon 1.0" })
        advanceUntilIdle()

        val text = vm.exportText()

        assertTrue(text.startsWith("Book Recon 1.0\n"))
        assertTrue(text.contains("I/Queue: hello"))
    }
}
