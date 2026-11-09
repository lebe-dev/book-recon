package ru.tinyops.android.bookrecon.net

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.HttpClient

class CloudflareInterceptorTest {
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
    fun `ordinary response passes through without a check`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val solver = FakeSolver(store, succeeds = true)

        val text = client(solver).get(server.url("/").toString()).use { it.text() }

        assertEquals("ok", text)
        assertEquals(0, solver.calls.get())
    }

    @Test
    fun `challenge header triggers a check and the request is repeated with the clearance`() = runBlocking {
        server.dispatcher = ChallengeUntilCleared(challenge = headerChallenge())
        val solver = FakeSolver(store, succeeds = true)

        val response = client(solver).get(server.url("/forum/tracker.php").toString())

        assertEquals(200, response.use { it.code })
        assertEquals(1, solver.calls.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `challenge page markers are detected without the header`() = runBlocking {
        val challenge = MockResponse.Builder()
            .code(503)
            .addHeader("Content-Type", "text/html")
            .body("<html><title>Just a moment...</title><script src=\"/cdn-cgi/challenge-platform/x.js\"></script></html>")
            .build()
        server.dispatcher = ChallengeUntilCleared(challenge)
        val solver = FakeSolver(store, succeeds = true)

        assertEquals(200, client(solver).get(server.url("/").toString()).use { it.code })
        assertEquals(1, solver.calls.get())
    }

    @Test
    fun `plain 403 is returned to the caller as is`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("Forbidden").build())
        val solver = FakeSolver(store, succeeds = true)

        assertEquals(403, client(solver).get(server.url("/").toString()).use { it.code })
        assertEquals(0, solver.calls.get())
    }

    @Test
    fun `challenge header on a 200 response is not a challenge`() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("cf-mitigated", "challenge").body("ok").build())
        val solver = FakeSolver(store, succeeds = true)

        assertEquals(200, client(solver).get(server.url("/").toString()).use { it.code })
        assertEquals(0, solver.calls.get())
    }

    @Test
    fun `failed check surfaces as a cloudflare challenge error`() {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = FakeSolver(store, succeeds = false)
        val url = server.url("/forum/login.php").toString()

        val error = assertThrows(AppError.CloudflareChallenge::class.java) {
            runBlocking { client(solver).get(url) }
        }

        assertEquals(url, error.url)
        assertEquals(1, solver.calls.get())
    }

    @Test
    fun `challenge after a successful check is not solved again`() {
        server.dispatcher = AlwaysChallenge()
        val solver = FakeSolver(store, succeeds = true)

        assertThrows(AppError.CloudflareChallenge::class.java) {
            runBlocking { client(solver).get(server.url("/").toString()) }
        }

        assertEquals(1, solver.calls.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `solver exception counts as a failed check`() {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = ChallengeSolver { error("webview is unavailable") }

        assertThrows(AppError.CloudflareChallenge::class.java) {
            runBlocking { client(solver).get(server.url("/").toString()) }
        }
    }

    @Test
    fun `post form is repeated with its body after the check`() = runBlocking {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = FakeSolver(store, succeeds = true)

        val response = client(solver).postForm(server.url("/login.php").toString(), listOf("login_username" to "user"))

        assertEquals(200, response.use { it.code })
        server.takeRequest()
        assertEquals("login_username=user", server.takeRequest().body?.utf8())
    }

    @Test
    fun `concurrent requests to one host wait for a single check`() = runBlocking {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = FakeSolver(store, succeeds = true, delayMillis = 300)
        val http = client(solver)

        val codes = (1..5).map { i ->
            async { http.get(server.url("/page/$i").toString()).use { it.code } }
        }.awaitAll()

        assertEquals(List(5) { 200 }, codes)
        assertEquals(1, solver.calls.get())
    }

    @Test
    fun `concurrent requests share a failed check instead of retrying it`() = runBlocking {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = FakeSolver(store, succeeds = false, delayMillis = 300)
        val http = client(solver)

        val failures = (1..4).map { i ->
            async {
                runCatching { http.get(server.url("/page/$i").toString()).close() }.exceptionOrNull()
            }
        }.awaitAll()

        assertTrue(failures.all { it is AppError.CloudflareChallenge }, failures.toString())
        assertEquals(1, solver.calls.get())
    }

    @Test
    fun `a later request after a failed check tries again`() {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = FakeSolver(store, succeeds = false)
        val http = client(solver)

        assertThrows(AppError.CloudflareChallenge::class.java) {
            runBlocking { http.get(server.url("/").toString()) }
        }
        solver.succeeds = true

        assertEquals(200, runBlocking { http.get(server.url("/").toString()).use { it.code } })
        assertEquals(2, solver.calls.get())
    }

    @Test
    fun `each host gets its own check`() = runBlocking {
        server.dispatcher = ChallengeUntilCleared(headerChallenge())
        val solver = FakeSolver(store, succeeds = true)
        val http = client(solver)
        val byName = server.url("/")
        val byAddress = byName.newBuilder().host("127.0.0.1").build()
        assertFalse(byName.host == byAddress.host)

        assertEquals(200, http.get(byName.toString()).use { it.code })
        assertEquals(200, http.get(byAddress.toString()).use { it.code })

        assertEquals(2, solver.calls.get())
    }

    private fun client(solver: ChallengeSolver): HttpClient =
        OkHttpHttpClient(buildOkHttpClient(SharedCookieJar(store), solver, userAgent = { "TestAgent/1.0" }))

    private fun headerChallenge(): MockResponse = MockResponse.Builder()
        .code(403)
        .addHeader("cf-mitigated", "challenge")
        .addHeader("Content-Type", "text/html")
        .body("<html>challenge</html>")
        .build()

    /** Answers with [challenge] until the request carries a `cf_clearance` cookie. */
    private class ChallengeUntilCleared(private val challenge: MockResponse) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val cookie = request.headers["Cookie"].orEmpty()
            if (cookieValue(cookie, CF_CLEARANCE) == null) return challenge
            return MockResponse.Builder().body("ok").build()
        }
    }

    private class AlwaysChallenge : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
            .code(403)
            .addHeader("cf-mitigated", "challenge")
            .build()
    }

    /** Stands in for the WebView: sets `cf_clearance` for the host when it succeeds. */
    private class FakeSolver(
        private val store: InMemoryCookieStore,
        @Volatile var succeeds: Boolean,
        private val delayMillis: Long = 0,
    ) : ChallengeSolver {
        val calls = AtomicInteger()

        override fun solve(url: HttpUrl): Boolean {
            calls.incrementAndGet()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            if (!succeeds) return false
            store.put(url.toString(), CF_CLEARANCE, "token-${calls.get()}")
            return true
        }
    }
}
