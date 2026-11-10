package ru.tinyops.android.bookrecon.server

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpServerApiTest {
    private val server = MockWebServer()
    private val api = OkHttpServerApi(OkHttpClient())

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private val base get() = server.url("/books").toString().trimEnd('/')

    @Test
    fun `register posts the device name and returns the token`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("""{"token":"abc"}""").build())

        assertEquals("abc", api.register(base, "Google Pixel"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/books/api/v1/clients", request.url.encodedPath)
        assertEquals("""{"deviceName":"Google Pixel"}""", request.body?.utf8())
    }

    @Test
    fun `status sends the bearer token and parses the answer`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"status":"approved","telegramUsername":"reader","tunnelHosts":["rutracker.org"],"extra":1}""")
                .build(),
        )

        val status = api.status(base, "tok")

        assertEquals(StatusResponse(ClientStatus.APPROVED, "reader", listOf("rutracker.org")), status)
        val request = server.takeRequest()
        assertEquals("/books/api/v1/clients/me", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    @Test
    fun `link and delete hit their routes`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"url":"https://t.me/bot?start=link_1","expiresAt":"2026-11-10T10:00:00Z"}""").build())
        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals("https://t.me/bot?start=link_1", api.link(base, "tok").url)
        api.delete(base, "tok")

        assertEquals("POST /books/api/v1/clients/me/link", server.takeRequest().let { "${it.method} ${it.url.encodedPath}" })
        assertEquals("DELETE /books/api/v1/clients/me", server.takeRequest().let { "${it.method} ${it.url.encodedPath}" })
    }

    @Test
    fun `ping accepts only a book recon server`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"service":"book-recon","version":"1"}""").build())
        api.ping(base)

        server.enqueue(MockResponse.Builder().body("<html>nginx</html>").build())
        assertThrows(ServerError.NotBookRecon::class.java) { runBlocking { api.ping(base) } }
        Unit
    }

    @Test
    fun `errors map to server errors`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        assertThrows(ServerError.Unauthorized::class.java) { runBlocking { api.status(base, "x") } }

        server.enqueue(MockResponse.Builder().code(404).build())
        assertThrows(ServerError.NotBookRecon::class.java) { runBlocking { api.status(base, "x") } }

        server.enqueue(MockResponse.Builder().code(500).build())
        val http = assertThrows(ServerError.Http::class.java) { runBlocking { api.status(base, "x") } }
        assertEquals(500, http.code)

        server.enqueue(MockResponse.Builder().body("not json").build())
        assertThrows(ServerError.NotBookRecon::class.java) { runBlocking { api.status(base, "x") } }
    }

    @Test
    fun `unreachable server is a network error`() {
        val port = server.port
        server.close()
        assertThrows(ServerError.Network::class.java) {
            runBlocking { api.status("http://127.0.0.1:$port", "x") }
        }
    }
}
