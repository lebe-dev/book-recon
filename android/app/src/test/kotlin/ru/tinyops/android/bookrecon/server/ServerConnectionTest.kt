package ru.tinyops.android.bookrecon.server

import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerConnectionTest {
    private val api = FakeServerApi()
    private val store = MemoryServerStore()
    private var clock = Instant.parse("2026-11-10T10:00:00Z")

    private fun TestScope.connection() = ServerConnection(
        api = api,
        store = store,
        deviceName = "Pixel",
        scope = backgroundScope,
        now = { clock },
        fastPoll = 3.seconds,
        slowPoll = 60.seconds,
        fastPollWindow = 1.minutes,
    )

    @Test
    fun `the server is off by default and off means no tunnel`() = runTest {
        store.server = StoredServer()
        val connection = connection()
        connection.start()

        assertEquals(ServerState.Off, connection.state.value)
        connection.setUrl("https://b")
        assertEquals(ServerState.Off, connection.state.value)
        assertNull(connection.connect())
        assertEquals(0, api.registered)

        connection.setEnabled(true)
        assertEquals(ServerState.Disconnected, connection.state.value)
        assertEquals(StoredServer(url = "https://b", enabled = true), store.server)
    }

    @Test
    fun `turning off removes the installation and stops the tunnel`() = runTest {
        store.server = StoredServer("https://b", "token1", setOf("rutracker.org"), "reader", enabled = true)
        api.knownTokens += "token1"
        api.status = StatusResponse(ClientStatus.APPROVED, "reader", listOf("rutracker.org"))
        val connection = connection()
        connection.start()

        connection.setEnabled(false)

        assertEquals(ServerState.Off, connection.state.value)
        assertNull(connection.route.value)
        assertEquals(listOf("token1"), api.deleted)
        assertEquals(StoredServer(url = "https://b"), store.server)
    }

    @Test
    fun `no url means not configured`() = runTest {
        val connection = connection()
        connection.start()
        assertEquals(ServerState.NotConfigured, connection.state.value)
        assertNull(connection.connect())
    }

    @Test
    fun `connect registers, returns the telegram link and waits`() = runTest {
        val connection = connection()
        connection.start()
        connection.setUrl(" https://books.example.com/ ")
        assertEquals("https://books.example.com", connection.url.value)
        assertEquals(ServerState.Disconnected, connection.state.value)

        val link = connection.connect()

        assertEquals("https://t.me/bot?start=link_token1", link)
        assertEquals(
            ServerState.AwaitingTelegram(link!!, Instant.parse("2026-11-10T10:15:00Z")),
            connection.state.value,
        )
        assertEquals("token1", store.server.token)
        assertNull(connection.route.value)
    }

    @Test
    fun `polling follows the decision and turns the tunnel on`() = runTest {
        val connection = connection()
        connection.start()
        connection.setUrl("https://books.example.com")
        connection.connect()

        api.status = StatusResponse(ClientStatus.PENDING, "reader")
        advanceTimeBy(3.seconds + 1.seconds / 10)
        assertEquals(ServerState.Pending, connection.state.value)

        api.status = StatusResponse(ClientStatus.APPROVED, "reader", listOf("RuTracker.org", "flibusta.is"))
        advanceTimeBy(3.seconds)
        runCurrent()
        val approved = ServerState.Approved(setOf("rutracker.org", "flibusta.is"), "reader")
        assertEquals(approved, connection.state.value)
        assertEquals(TunnelRoute("https://books.example.com", "token1", approved.hosts), connection.route.value)
        assertEquals(approved.hosts, store.server.approvedHosts)

        // Polling stops once decided.
        val calls = api.statusCalls
        advanceTimeBy(10.minutes)
        assertEquals(calls, api.statusCalls)
    }

    @Test
    fun `polling slows down after the fast window`() = runTest {
        val connection = connection()
        connection.start()
        connection.setUrl("https://books.example.com")
        connection.connect()
        api.status = StatusResponse(ClientStatus.PENDING)
        val beforePolling = api.statusCalls

        advanceTimeBy(1.minutes + 1.seconds / 10)
        val afterFastWindow = api.statusCalls
        assertEquals(beforePolling + 20, afterFastWindow)
        advanceTimeBy(59.seconds)
        assertEquals(afterFastWindow, api.statusCalls)
        advanceTimeBy(2.seconds)
        assertEquals(afterFastWindow + 1, api.statusCalls)
    }

    @Test
    fun `an expired link falls back to disconnected`() = runTest {
        val connection = connection()
        connection.start()
        connection.setUrl("https://books.example.com")
        connection.connect()

        clock = Instant.parse("2026-11-10T10:16:00Z")
        advanceTimeBy(3.seconds + 1.seconds / 10)

        assertEquals(ServerState.Disconnected, connection.state.value)
    }

    @Test
    fun `denied clears the tunnel`() = runTest {
        store.server = StoredServer("https://b", "token1", setOf("rutracker.org"), "reader", enabled = true)
        api.knownTokens += "token1"
        api.status = StatusResponse(ClientStatus.DENIED)
        val connection = connection()

        connection.start()

        assertEquals(ServerState.Denied, connection.state.value)
        assertNull(connection.route.value)
        assertNull(store.server.approvedHosts)
    }

    @Test
    fun `cached approval works offline at start`() = runTest {
        store.server = StoredServer("https://b", "token1", setOf("rutracker.org"), "reader", enabled = true)
        api.failure = ServerError.Network(IOException("offline"))
        val connection = connection()

        connection.start()

        assertEquals(ServerState.Approved(setOf("rutracker.org"), "reader"), connection.state.value)
        assertEquals(TunnelRoute("https://b", "token1", setOf("rutracker.org")), connection.route.value)
    }

    @Test
    fun `forgotten token disconnects`() = runTest {
        store.server = StoredServer("https://b", "lost", setOf("rutracker.org"), "reader", enabled = true)
        val connection = connection()

        connection.start()

        assertEquals(ServerState.Disconnected, connection.state.value)
        assertNull(store.server.token)
        assertNull(connection.route.value)
    }

    @Test
    fun `connect with a forgotten token registers again`() = runTest {
        store.server = StoredServer("https://b", "lost", enabled = true)
        val connection = connection()
        connection.start()

        val link = connection.connect()

        assertEquals("https://t.me/bot?start=link_token1", link)
        assertEquals("token1", store.server.token)
    }

    @Test
    fun `connect reports why it failed`() = runTest {
        val connection = connection()
        connection.start()
        connection.setUrl("https://b")

        api.failure = ServerError.Network(IOException("down"))
        assertNull(connection.connect())
        assertEquals(ServerState.Failed(ServerProblem.UNREACHABLE), connection.state.value)

        api.failure = ServerError.NotBookRecon("nginx")
        connection.connect()
        assertEquals(ServerState.Failed(ServerProblem.NOT_BOOK_RECON), connection.state.value)
    }

    @Test
    fun `a linked installation reconnects without a new link`() = runTest {
        store.server = StoredServer("https://b", "token1", enabled = true)
        api.knownTokens += "token1"
        api.status = StatusResponse(ClientStatus.APPROVED, "boss", listOf("rutracker.org"))
        val connection = connection()
        connection.start()

        assertNull(connection.connect())

        assertEquals(ServerState.Approved(setOf("rutracker.org"), "boss"), connection.state.value)
        assertEquals(0, api.registered)
    }

    @Test
    fun `disconnect removes the installation and keeps the url`() = runTest {
        store.server = StoredServer("https://b", "token1", setOf("rutracker.org"), "reader", enabled = true)
        api.knownTokens += "token1"
        api.status = StatusResponse(ClientStatus.APPROVED, "reader", listOf("rutracker.org"))
        val connection = connection()
        connection.start()

        connection.disconnect()

        assertEquals(listOf("token1"), api.deleted)
        assertEquals(StoredServer(url = "https://b", enabled = true), store.server)
        assertEquals(ServerState.Disconnected, connection.state.value)
        assertNull(connection.route.value)
    }

    @Test
    fun `changing the url forgets the old registration`() = runTest {
        store.server = StoredServer("https://old", "token1", setOf("rutracker.org"), enabled = true)
        api.knownTokens += "token1"
        api.status = StatusResponse(ClientStatus.APPROVED, null, listOf("rutracker.org"))
        val connection = connection()
        connection.start()

        connection.setUrl("https://new")

        assertEquals(listOf("token1"), api.deleted)
        assertEquals(StoredServer(url = "https://new", enabled = true), store.server)
        assertEquals(ServerState.Disconnected, connection.state.value)

        connection.setUrl("")
        assertEquals(ServerState.NotConfigured, connection.state.value)
    }

    @Test
    fun `host matching covers subdomains only`() {
        val hosts = setOf("rutracker.org")
        assertEquals(true, hostMatches("rutracker.org", hosts))
        assertEquals(true, hostMatches("Static.RuTracker.org.", hosts))
        assertEquals(false, hostMatches("evilrutracker.org", hosts))
        assertEquals(false, hostMatches("rutracker.org.evil.com", hosts))
    }
}
