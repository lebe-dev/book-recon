package ru.tinyops.android.bookrecon.net

import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.HttpClient

class OkHttpHttpClientTest {
    private val server = MockWebServer()
    private val store = InMemoryCookieStore()

    @BeforeEach
    fun start() {
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `response carries code, lowercase headers and the final url after redirects`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/forum/login.php?redirect=x").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Custom", "a").addHeader("X-Custom", "b").body("form").build())

        client().get(server.url("/forum/tracker.php").toString()).use { response ->
            assertEquals(200, response.code)
            assertTrue(response.isSuccessful)
            assertEquals(server.url("/forum/login.php?redirect=x").toString(), response.finalUrl)
            assertEquals(listOf("a", "b"), response.headers["x-custom"])
            assertEquals("form", response.text())
        }
    }

    @Test
    fun `error status codes are returned, not thrown`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("missing").build())

        assertEquals(404, client().get(server.url("/").toString()).use { it.code })
    }

    @Test
    fun `charset comes from content type, windows-1251 included`() = runBlocking {
        val cp1251 = Charset.forName("windows-1251")
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html; charset=Windows-1251")
                .body(Buffer().write("Аудиокниги".toByteArray(cp1251)))
                .build(),
        )
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("x").build())

        client().get(server.url("/").toString()).use { response ->
            assertEquals(cp1251, response.charset)
            assertEquals("Аудиокниги", response.text())
        }
        assertNull(client().get(server.url("/").toString()).use { it.charset })
    }

    @Test
    fun `body is streamed as it arrives`() = runBlocking {
        val size = 4 * 1024 * 1024
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(size))).build())

        val read = client().get(server.url("/book.fb2").toString()).use { response ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = response.body.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }

        assertEquals(size.toLong(), read)
    }

    @Test
    fun `form is encoded in the requested charset`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("ok").build())

        client().postForm(
            server.url("/forum/login.php").toString(),
            listOf("login_username" to "user", "login" to "вход"),
            charset = Charset.forName("windows-1251"),
        ).close()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("login_username=user&login=%E2%F5%EE%E4", request.body?.utf8())
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `user agent is the webview one unless the caller sets it`() = runBlocking {
        server.enqueue(MockResponse.Builder().build())
        server.enqueue(MockResponse.Builder().build())

        client().get(server.url("/").toString()).close()
        client().get(server.url("/").toString(), mapOf("User-Agent" to "Custom/2", "Referer" to "https://x/")).close()

        assertEquals("TestAgent/1.0", server.takeRequest().headers["User-Agent"])
        val second = server.takeRequest()
        assertEquals("Custom/2", second.headers["User-Agent"])
        assertEquals("https://x/", second.headers["Referer"])
    }

    @Test
    fun `cookies set by the server are kept in the shared store`() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Set-Cookie", "bb_session=abc; path=/").build())
        server.enqueue(MockResponse.Builder().build())
        val http = client()

        http.get(server.url("/login").toString()).close()
        http.get(server.url("/next").toString()).close()

        assertEquals("abc", cookieValue(store.cookieHeader(server.url("/").toString()), "bb_session"))
        server.takeRequest()
        assertEquals("bb_session=abc", server.takeRequest().headers["Cookie"])
    }

    @Test
    fun `refused connection is a network error`() {
        val port = ServerSocket(0).use { it.localPort }

        assertThrows(AppError.Network::class.java) {
            runBlocking { client().get("http://127.0.0.1:$port/") }
        }
    }

    @Test
    fun `invalid url is a validation error`() {
        assertThrows(AppError.Validation::class.java) {
            runBlocking { client().get("not a url") }
        }
    }

    @Test
    fun `slow headers time out on ordinary hosts`() {
        server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).build())
        val config = NetworkConfig(readTimeout = 200.milliseconds, slowReadTimeout = 5.seconds, slowHosts = emptySet())

        assertThrows(AppError.Timeout::class.java) {
            runBlocking { client(config).get(server.url("/").toString()) }
        }
    }

    @Test
    fun `slow hosts get the longer read timeout`() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(600, TimeUnit.MILLISECONDS).body("ok").build())
        val config = NetworkConfig(
            readTimeout = 200.milliseconds,
            slowReadTimeout = 5.seconds,
            slowHosts = setOf(server.hostName),
        )

        assertEquals("ok", client(config).get(server.url("/").toString()).use { it.text() })
    }

    @Test
    fun `slow host matching covers subdomains only`() {
        val config = NetworkConfig()

        assertTrue(config.isSlowHost("rutracker.org"))
        assertTrue(config.isSlowHost("bt.rutracker.org"))
        assertFalse(config.isSlowHost("notrutracker.org"))
        assertFalse(config.isSlowHost("flibusta.is"))
    }

    @Test
    fun `cancelling the caller cancels the request`() {
        server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).build())

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(200.milliseconds) { client().get(server.url("/").toString()) } }
        }
    }

    private fun client(config: NetworkConfig = NetworkConfig()): HttpClient = OkHttpHttpClient(
        buildOkHttpClient(
            SharedCookieJar(store),
            solver = { false },
            userAgent = { "TestAgent/1.0" },
            config = config,
        ),
    )
}
