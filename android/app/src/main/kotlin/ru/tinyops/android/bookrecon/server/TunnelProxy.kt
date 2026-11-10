package ru.tinyops.android.bookrecon.server

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * HTTP CONNECT proxy on the loopback interface. Each CONNECT to a host the [route] serves
 * becomes a WebSocket to the server's `/api/v1/tunnel`, which opens the TCP connection on the
 * server side; bytes are copied both ways untouched, so TLS stays end to end between the app
 * and the site. OkHttp and WebView use this proxy only for hosts of the route.
 *
 * Other apps on the device can reach a loopback port too; the server still limits the tunnel
 * to its host list and to approved installations.
 *
 * @param client builds the WebSockets; it must not use this proxy itself.
 */
class TunnelProxy(
    private val client: OkHttpClient,
    private val route: () -> TunnelRoute?,
    private val log: (String) -> Unit = {},
    private val openTimeoutMillis: Long = 30_000,
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "tunnel-proxy").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    /** Port on 127.0.0.1 the proxy listens on. */
    val port: Int get() = server.localPort

    fun start() {
        executor.execute(::acceptLoop)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.closeQuietly()
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!closed.get()) log("Tunnel proxy stopped accepting connections: ${e.message}")
                return
            }
            executor.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = HEADER_TIMEOUT_MILLIS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val target = readConnect(input, output) ?: return
            val current = route() ?: return respond(output, 503, "Tunnel Off")
            if (!current.serves(target.host)) return respond(output, 403, "Host Not Served")
            socket.soTimeout = 0
            Tunnel(socket, input, output, target, current).run()
        } catch (e: IOException) {
            log("Tunnel proxy connection failed: ${e.message}")
        } finally {
            socket.closeQuietly()
        }
    }

    /** Reads the request head; answers and returns null unless it is a CONNECT. */
    private fun readConnect(input: InputStream, output: OutputStream): Target? {
        val requestLine = readLine(input) ?: return null
        var headerLines = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (++headerLines > MAX_HEADER_LINES) {
                respond(output, 431, "Request Header Fields Too Large")
                return null
            }
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] != "CONNECT") {
            respond(output, 405, "Method Not Allowed")
            return null
        }
        val target = Target.parse(parts[1])
        if (target == null) respond(output, 400, "Bad Request")
        return target
    }

    /** One CONNECT: a WebSocket to the server and the copy loops. */
    private inner class Tunnel(
        private val socket: Socket,
        private val input: InputStream,
        private val output: OutputStream,
        private val target: Target,
        private val route: TunnelRoute,
    ) : WebSocketListener() {
        private val opened = CompletableFuture<Int>()
        private val done = AtomicBoolean(false)

        fun run() {
            val url = OkHttpServerApi.url(route.baseUrl, "tunnel").newBuilder()
                .addQueryParameter("host", target.host)
                .addQueryParameter("port", target.port.toString())
                .build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${route.token}").build()
            val ws = client.newWebSocket(request, this)

            val code = try {
                opened.get(openTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                ws.cancel()
                GATEWAY_TIMEOUT
            } catch (_: Exception) {
                ws.cancel()
                BAD_GATEWAY
            }
            if (code != OPEN) {
                log("Server refused a tunnel to ${target.host}:${target.port} (HTTP $code)")
                val status = when (code) {
                    401, 403 -> 403
                    GATEWAY_TIMEOUT -> GATEWAY_TIMEOUT
                    else -> BAD_GATEWAY
                }
                respond(output, status, "Tunnel Refused")
                return
            }
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            pumpToServer(ws)
        }

        private fun pumpToServer(ws: WebSocket) {
            val buffer = ByteArray(CHUNK)
            try {
                while (!done.get()) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    while (ws.queueSize() > MAX_QUEUE && !done.get()) Thread.sleep(QUEUE_WAIT_MILLIS)
                    if (!ws.send(buffer.toByteString(0, read))) break
                }
            } catch (_: IOException) {
                // The app closed its side; the server side is closed below.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                finish()
                ws.close(NORMAL_CLOSURE, null)
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(OPEN)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                output.write(bytes.toByteArray())
                output.flush()
            } catch (_: IOException) {
                webSocket.cancel()
                finish()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            finish()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isDone) {
                opened.complete(response?.code ?: BAD_GATEWAY)
                return
            }
            log("Tunnel to ${target.host} broke: ${t.message}")
            finish()
        }

        /** Closing the socket also ends the read loop of [pumpToServer]. */
        private fun finish() {
            if (done.compareAndSet(false, true)) socket.closeQuietly()
        }
    }

    /** Host and port of a CONNECT request. */
    internal data class Target(val host: String, val port: Int) {
        companion object {
            fun parse(authority: String): Target? {
                val colon = authority.lastIndexOf(':')
                if (colon <= 0) return null
                val host = authority.substring(0, colon).removePrefix("[").removeSuffix("]")
                val port = authority.substring(colon + 1).toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return Target(host.lowercase(), port)
            }
        }
    }

    private companion object {
        const val BACKLOG = 50
        const val HEADER_TIMEOUT_MILLIS = 30_000
        const val MAX_HEADER_LINES = 100
        const val MAX_LINE = 8 * 1024
        const val CHUNK = 16 * 1024
        const val MAX_QUEUE = 1L shl 20
        const val QUEUE_WAIT_MILLIS = 5L
        const val NORMAL_CLOSURE = 1000
        const val OPEN = 0
        const val BAD_GATEWAY = 502
        const val GATEWAY_TIMEOUT = 504

        fun respond(output: OutputStream, code: Int, reason: String) {
            try {
                output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            } catch (_: IOException) {
                // The client is gone; nothing to tell it.
            }
        }

        /** Reads one CRLF-terminated line in ISO-8859-1; null at the end of the stream. */
        fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length >= MAX_LINE) throw IOException("request line too long")
                line.append(b.toChar())
            }
        }

        fun Closeable.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }
}
