package ru.tinyops.android.bookrecon.diagnostics

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeProblem
import ru.tinyops.android.bookrecon.core.route.FlibustaRouteCheck
import ru.tinyops.android.bookrecon.core.route.RouteMemory

class OkHttpAddressProberTest {
    private val server = MockWebServer()

    /** The system resolver must not be asked: the host does not exist. */
    private val dns = Dns { host ->
        if (host == "flibusta.test") listOf(InetAddress.getByName(server.hostName)) else throw UnknownHostException(host)
    }

    @BeforeEach
    fun start() {
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private val base: String get() = "http://flibusta.test:${server.port}"

    private val loopback: String get() = InetAddress.getByName(server.hostName).hostAddress!!

    @Test
    fun `resolve gives the addresses as text, nothing for an unknown host`() = runBlocking {
        val prober = OkHttpAddressProber(OkHttpClient(), dns)

        assertEquals(listOf(loopback), prober.resolve("flibusta.test"))
        assertEquals(emptyList<String>(), prober.resolve("nowhere.test"))
    }

    @Test
    fun `a probe connects to the given address and keeps the host of the mirror`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<title>Флибуста</title>").build())
        // The client resolves nothing itself: a probe pins the address.
        val prober = OkHttpAddressProber(OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build(), dns)

        assertTrue(prober.probe(base, loopback) is ProbeOutcome.Reachable)
        val request = server.takeRequest()
        assertEquals("flibusta.test:${server.port}", request.headers["Host"])
        assertEquals("/booksearch", request.url.encodedPath)
        assertTrue(request.url.queryParameter("ask").orEmpty().all { it in 'a'..'z' })
    }

    @Test
    fun `a stub, a server error and a dead address are failures`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("parked domain").build())
        server.enqueue(MockResponse.Builder().code(503).build())
        val prober = OkHttpAddressProber(OkHttpClient(), dns)

        assertEquals(ProbeOutcome.Failed(ProbeProblem.BAD_ANSWER), prober.probe(base, loopback))
        assertEquals(ProbeOutcome.Failed(ProbeProblem.BAD_ANSWER), prober.probe(base, loopback))
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION), prober.probe("http://flibusta.test:$closed", loopback))
    }

    @Test
    fun `a route check over real requests remembers the mirror and the address`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<title>Флибуста</title>").build())
        val memory = RouteMemory()

        val report = FlibustaRouteCheck(OkHttpAddressProber(OkHttpClient(), dns), memory).run(listOf(base, "http://nowhere.test"))

        assertEquals(base, report.bestMirror)
        assertEquals(base, memory.preferred("Flibusta $base"))
        assertTrue(memory.knowsWorkingAddress(listOf("flibusta.test")))
    }
}
