package ru.tinyops.android.bookrecon.torrent

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** HTTP CONNECT proxy on 127.0.0.1 that relays to the target and records every target. */
class ConnectProxy : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()

    /** `host:port` of every CONNECT, in order. */
    val targets = CopyOnWriteArrayList<String>()

    /** Request line of every request, CONNECT or not, in order. */
    val requests = CopyOnWriteArrayList<String>()

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "connect-proxy") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                sockets += client
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            val input = client.getInputStream()
            val requestLine = readLine(input)
            requests += requestLine
            while (readLine(input).isNotEmpty()) Unit
            val parts = requestLine.split(' ')
            if (parts.size != 3 || parts[0] != "CONNECT") {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                client.close()
                return
            }
            targets += parts[1]
            val colon = parts[1].lastIndexOf(':')
            val upstream = Socket(parts[1].substring(0, colon).removeSurrounding("[", "]"), parts[1].substring(colon + 1).toInt())
            sockets += upstream
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            thread(isDaemon = true) { copy(upstream.getInputStream(), client, upstream) }
            copy(input, upstream, client)
        } catch (_: IOException) {
            client.close()
        }
    }

    private fun copy(from: InputStream, to: Socket, other: Socket) {
        try {
            from.copyTo(to.getOutputStream())
        } catch (_: IOException) {
            // One side is gone.
        } finally {
            to.close()
            other.close()
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            if (b == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(b.toChar())
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
