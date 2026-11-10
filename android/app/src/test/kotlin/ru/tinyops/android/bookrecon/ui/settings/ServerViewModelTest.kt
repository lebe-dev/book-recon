package ru.tinyops.android.bookrecon.ui.settings

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import ru.tinyops.android.bookrecon.server.ClientStatus
import ru.tinyops.android.bookrecon.server.FakeServerApi
import ru.tinyops.android.bookrecon.server.MemoryServerStore
import ru.tinyops.android.bookrecon.server.ServerConnection
import ru.tinyops.android.bookrecon.server.ServerState
import ru.tinyops.android.bookrecon.server.StatusResponse
import ru.tinyops.android.bookrecon.server.StoredServer

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeServerApi()
    private val store = MemoryServerStore()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun runVmTest(block: suspend TestScope.(ServerViewModel, ServerConnection) -> Unit) = runTest(dispatcher) {
        val connection = ServerConnection(
            api = api,
            store = store,
            deviceName = "Pixel",
            scope = backgroundScope,
            now = { Instant.parse("2026-11-10T10:00:00Z") },
        )
        connection.start()
        val vm = ServerViewModel(connection)
        advanceUntilIdle()
        block(vm, connection)
    }

    @Test
    fun `the stored url fills the field`() {
        store.server = StoredServer(url = "https://books.example.com", enabled = true)
        runVmTest { vm, _ ->
            assertEquals("https://books.example.com", vm.state.value.url)
            assertFalse(vm.state.value.urlChanged)
            assertEquals(ServerState.Disconnected, vm.state.value.connection)
        }
    }

    @Test
    fun `turning the server on with a url asks for access right away`() {
        store.server = StoredServer(url = "https://books.example.com")
        runVmTest { vm, _ ->
            assertFalse(vm.state.value.enabled)

            vm.onEnabledChange(true)
            advanceUntilIdle()

            assertTrue(vm.state.value.enabled)
            assertEquals("https://t.me/bot?start=link_token1", vm.links.first())

            api.status = StatusResponse(ClientStatus.DENIED)
            vm.onResume()
            advanceUntilIdle()
            assertEquals(ServerState.Denied, vm.state.value.connection)

            vm.onEnabledChange(false)
            advanceUntilIdle()
            assertEquals(ServerState.Off, vm.state.value.connection)
        }
    }

    @Test
    fun `an invalid url is not saved`() = runVmTest { vm, connection ->
        vm.onUrlChange("books example")
        vm.onSaveUrl()
        advanceUntilIdle()

        assertTrue(vm.state.value.urlError)
        assertEquals("", connection.url.value)
    }

    @Test
    fun `saving a url while on opens the telegram link`() = runVmTest { vm, connection ->
        vm.onUrlChange("https://books.example.com/")
        vm.onSaveUrl()
        advanceUntilIdle()
        assertEquals("https://books.example.com", connection.url.value)
        assertEquals("https://books.example.com", vm.state.value.url)

        // Saving the URL of a server that is on asks for access at once.
        assertEquals("https://t.me/bot?start=link_token1", vm.links.first())
        assertTrue(vm.state.value.connection is ServerState.AwaitingTelegram)

        vm.onOpenTelegram()
        assertEquals("https://t.me/bot?start=link_token1", vm.links.first())
    }

    @Test
    fun `resume refreshes the decision and disconnect forgets it`() {
        store.server = StoredServer(url = "https://b", token = "token1", enabled = true)
        api.knownTokens += "token1"
        api.status = StatusResponse(ClientStatus.PENDING)
        runVmTest { vm, _ ->
            assertEquals(ServerState.Pending, vm.state.value.connection)

            api.status = StatusResponse(ClientStatus.APPROVED, "reader", listOf("rutracker.org"))
            vm.onResume()
            advanceUntilIdle()
            assertEquals(ServerState.Approved(setOf("rutracker.org"), "reader"), vm.state.value.connection)

            vm.onDisconnect()
            advanceUntilIdle()
            assertEquals(ServerState.Disconnected, vm.state.value.connection)
            assertEquals(listOf("token1"), api.deleted)
        }
    }
}
