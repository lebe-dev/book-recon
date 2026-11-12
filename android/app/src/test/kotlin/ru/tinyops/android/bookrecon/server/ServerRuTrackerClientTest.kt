package ru.tinyops.android.bookrecon.server

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.fake.FakeRuTrackerClient

class ServerRuTrackerClientTest {
    private val server = MockWebServer()
    private var route: TunnelRoute? = null
    private val client = ServerRuTrackerClient(OkHttpClient()) { route }

    @BeforeEach
    fun start() {
        server.start()
        route = TunnelRoute(server.url("/books").toString().trimEnd('/'), "tok", setOf("rutracker.org"))
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `search asks the server and maps topics`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body(
                """{"topics":[{"topicId":"6543210","title":"Дозор","size":1024,"seeders":7,"peers":9}]}""",
            ).build(),
        )

        val topics = client.search("ночной дозор", MediaKind.AUDIO, 20)

        assertEquals(listOf(TorrentTopic("6543210", "Дозор", 1024, 7, 9, MediaKind.AUDIO)), topics)
        val request = server.takeRequest()
        assertEquals("/books/api/v1/rutracker/search", request.url.encodedPath)
        assertEquals("ночной дозор", request.url.queryParameter("q"))
        assertEquals("audio", request.url.queryParameter("kind"))
        assertEquals("20", request.url.queryParameter("limit"))
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    @Test
    fun `book searches name the book kind`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"topics":[]}""").build())
        client.search("дозор", MediaKind.BOOK, 5)
        assertEquals("book", server.takeRequest().url.queryParameter("kind"))
    }

    @Test
    fun `torrent is fetched by topic`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(1, 2, 3))).build())

        assertArrayEquals(byteArrayOf(1, 2, 3), client.fetchTorrent("6543210"))
        assertEquals("/books/api/v1/rutracker/topics/6543210/torrent", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `errors of the server become app errors`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"topic_unknown"}""").build())
        assertThrows<AppError.NotFound> { client.fetchTorrent("1") }

        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"rutracker_disabled"}""").build())
        assertThrows<AppError.ServiceDown> { client.search("дозор", MediaKind.BOOK, 5) }

        server.enqueue(MockResponse.Builder().code(502).body("""{"error":"rutracker_unavailable"}""").build())
        assertThrows<AppError.ServiceDown> { client.search("дозор", MediaKind.BOOK, 5) }

        server.enqueue(MockResponse.Builder().code(200).body("not json").build())
        assertThrows<AppError.ParseError> { client.search("дозор", MediaKind.BOOK, 5) }
        Unit
    }

    @Test
    fun `an unreachable server is a network error`() = runBlocking {
        server.close()
        assertThrows<AppError.Network> { client.search("дозор", MediaKind.BOOK, 5) }
        Unit
    }

    @Test
    fun `without a route the server cannot be used`() = runBlocking {
        route = null
        assertThrows<AppError.ServiceDown> { client.search("дозор", MediaKind.BOOK, 5) }
        Unit
    }

    @Test
    fun `routing uses the server only while rutracker goes through it`() = runBlocking {
        val local = FakeRuTrackerClient().apply { credentialsSet = false }
        var throughServer = true
        val routing = RoutingRuTrackerClient(local, client) { throughServer }

        assertFalse(routing.needsCredentials())
        assertTrue(routing.isLoggedIn())
        routing.login() // the server's account: nothing to do, no error without credentials
        server.enqueue(MockResponse.Builder().body("""{"topics":[]}""").build())
        routing.search("дозор", MediaKind.BOOK, 5)
        assertEquals(1, server.requestCount)

        throughServer = false
        assertTrue(routing.needsCredentials())
        assertThrows<AppError.RuTrackerAuthRequired> { routing.login() }
        Unit
    }
}
