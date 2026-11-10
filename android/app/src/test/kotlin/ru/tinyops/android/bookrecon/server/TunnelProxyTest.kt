package ru.tinyops.android.bookrecon.server

import java.io.InputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TunnelProxyTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var route: TunnelRoute? = null
    private lateinit var proxy: TunnelProxy

    /** Answers the tunnel upgrade and echoes every binary message. */
    private val echo = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.send(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @BeforeEach
    fun start() {
        server.start()
        route = TunnelRoute(server.url("/").toString().trimEnd('/'), "tok", setOf("rutracker.org"))
        proxy = TunnelProxy(client, { route }, openTimeoutMillis = 5_000).also { it.start() }
    }

    @AfterEach
    fun stop() {
        proxy.close()
        server.close()
    }

    private fun connect(request: String): Pair<Socket, String> {
        val socket = Socket("127.0.0.1", proxy.port)
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        return socket to readHead(socket.getInputStream())
    }

    private fun readHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
        }
        return head.toString()
    }

    @Test
    fun `connect opens an authorized tunnel and copies bytes both ways`() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(echo).build())

        val (socket, head) = connect("CONNECT static.rutracker.org:443 HTTP/1.1\r\nHost: static.rutracker.org:443\r\n\r\n")
        socket.use {
            assertEquals("HTTP/1.1 200 Connection established\r\n\r\n", head)
            val payload = ByteArray(100_000) { (it % 251).toByte() }
            it.getOutputStream().write(payload)
            it.getOutputStream().flush()
            val back = ByteArray(payload.size)
            var read = 0
            while (read < back.size) {
                val n = it.getInputStream().read(back, read, back.size - read)
                if (n < 0) break
                read += n
            }
            assertArrayEquals(payload, back)
        }

        val upgrade = server.takeRequest()
        assertEquals("/api/v1/tunnel", upgrade.url.encodedPath)
        assertEquals("static.rutracker.org", upgrade.url.queryParameter("host"))
        assertEquals("443", upgrade.url.queryParameter("port"))
        assertEquals("Bearer tok", upgrade.headers["Authorization"])
    }

    @Test
    fun `server refusal becomes 403`() {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"not_approved"}""").build())

        val (socket, head) = connect("CONNECT rutracker.org:443 HTTP/1.1\r\n\r\n")
        socket.use { assertEquals("HTTP/1.1 403", head.take(12)) }
    }

    @Test
    fun `unreachable target becomes 502`() {
        server.enqueue(MockResponse.Builder().code(502).build())

        val (socket, head) = connect("CONNECT rutracker.org:443 HTTP/1.1\r\n\r\n")
        socket.use { assertEquals("HTTP/1.1 502", head.take(12)) }
    }

    @Test
    fun `hosts outside the route are refused locally`() {
        val (socket, head) = connect("CONNECT example.com:443 HTTP/1.1\r\n\r\n")
        socket.use { assertEquals("HTTP/1.1 403", head.take(12)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no route means the tunnel is off`() {
        route = null
        val (socket, head) = connect("CONNECT rutracker.org:443 HTTP/1.1\r\n\r\n")
        socket.use { assertEquals("HTTP/1.1 503", head.take(12)) }
    }

    @Test
    fun `plain proxy requests are not supported`() {
        val (socket, head) = connect("GET http://rutracker.org/ HTTP/1.1\r\nHost: rutracker.org\r\n\r\n")
        socket.use { assertEquals("HTTP/1.1 405", head.take(12)) }
    }

    @Test
    fun `server closing the tunnel closes the app socket`() {
        val closer = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1000, null)
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(closer).build())

        val (socket, head) = connect("CONNECT rutracker.org:443 HTTP/1.1\r\n\r\n")
        socket.use {
            assertEquals("HTTP/1.1 200 Connection established\r\n\r\n", head)
            assertEquals(-1, it.getInputStream().read())
        }
    }

    @Test
    fun `the proxy listens on IPv4 loopback, where WebView looks for it`() {
        assertEquals("127.0.0.1", proxy.address.hostAddress)
    }

    @Test
    fun `connect targets are parsed`() {
        assertEquals(TunnelProxy.Target("rutracker.org", 443), TunnelProxy.Target.parse("RuTracker.org:443"))
        assertEquals(TunnelProxy.Target("::1", 8443), TunnelProxy.Target.parse("[::1]:8443"))
        assertNull(TunnelProxy.Target.parse("rutracker.org"))
        assertNull(TunnelProxy.Target.parse(":443"))
        assertNull(TunnelProxy.Target.parse("rutracker.org:0"))
        assertNull(TunnelProxy.Target.parse("rutracker.org:x"))
    }
}
