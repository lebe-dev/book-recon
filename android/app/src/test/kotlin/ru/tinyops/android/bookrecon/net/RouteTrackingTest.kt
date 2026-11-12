package ru.tinyops.android.bookrecon.net

import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.route.RouteMemory

class RouteTrackingTest {
    private val server = MockWebServer()
    private var time = 1L
    private val routes = RouteMemory(now = { time++ })

    private val a = InetAddress.getByAddress("flibusta.test", byteArrayOf(10, 0, 0, 1))
    private val b = InetAddress.getByAddress("flibusta.test", byteArrayOf(10, 0, 0, 2))
    private val c = InetAddress.getByAddress("flibusta.test", byteArrayOf(10, 0, 0, 3))

    @BeforeEach
    fun start() {
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `the resolver puts the address that worked first and the failed one last`() {
        val dns = RememberingDns(routes) { listOf(a, b, c) }
        routes.addressFailed("flibusta.test", "10.0.0.1")
        routes.addressWorked("flibusta.test", "10.0.0.3")

        assertEquals(listOf(c, b, a), dns.lookup("flibusta.test"))
    }

    @Test
    fun `a single address is passed through`() {
        assertEquals(listOf(a), RememberingDns(routes) { listOf(a) }.lookup("flibusta.test"))
    }

    @Test
    fun `an answer marks the address as working`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("ok").build())

        client().get(url()).use { assertEquals(200, it.code) }

        assertTrue(routes.snapshot().addresses.getValue("flibusta.test").getValue(loopback()).works)
    }

    @Test
    fun `a server error or a timeout marks the address as failed`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        client().get(url()).use { assertEquals(503, it.code) }
        assertEquals(false, routes.snapshot().addresses.getValue("flibusta.test").getValue(loopback()).works)

        server.enqueue(MockResponse.Builder().body("ok").build())
        client().get(url()).use { assertEquals(200, it.code) }
        assertEquals(true, routes.snapshot().addresses.getValue("flibusta.test").getValue(loopback()).works)

        server.enqueue(MockResponse.Builder().headersDelay(1, java.util.concurrent.TimeUnit.SECONDS).build())
        runCatching { client().get(url()).close() }
        assertEquals(false, routes.snapshot().addresses.getValue("flibusta.test").getValue(loopback()).works)
    }

    private fun loopback(): String = InetAddress.getByName(server.hostName).hostAddress!!

    private fun url(): String = "http://flibusta.test:${server.port}/"

    private fun client(): HttpClient = OkHttpHttpClient(
        buildOkHttpClient(
            SharedCookieJar(InMemoryCookieStore()),
            solver = { false },
            userAgent = { "TestAgent/1.0" },
            config = NetworkConfig(readTimeout = 200.milliseconds, slowHosts = emptySet()),
            routes = routes,
            dns = Dns { listOf(InetAddress.getByName(server.hostName)) },
        ),
    )
}
