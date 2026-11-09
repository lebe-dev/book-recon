package ru.tinyops.android.bookrecon.core.port

import ru.tinyops.android.bookrecon.core.domain.AppError
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.Charset

/**
 * HTTP transport shared by the Flibusta and RuTracker sources.
 *
 * Redirects are followed; [HttpResponse.finalUrl] tells where they ended, so a redirect to a
 * login page can be detected. Cookies live in a store shared with WebView, callers never
 * pass them by hand.
 *
 * Any status code is returned as a response, the caller decides what an error is. Transport
 * failures throw [AppError.Network] or [AppError.Timeout]; a Cloudflare challenge that could
 * not be solved throws [AppError.CloudflareChallenge].
 *
 * The overall deadline is set by the caller with `withTimeout`; the client only enforces
 * connect and first-byte timeouts.
 */
interface HttpClient {
    /** Sends a GET request. [url] is absolute and already percent-encoded. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Sends an `application/x-www-form-urlencoded` POST. Field names and values are encoded
     * with [charset]: RuTracker expects windows-1251.
     */
    suspend fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
        charset: Charset = Charsets.UTF_8,
    ): HttpResponse
}

/**
 * An HTTP response with a streaming body. Must be closed; closing it releases the
 * connection. The body is read once: either [body], [bytes] or [text].
 *
 * @property finalUrl URL of the last request after redirects.
 * @property headers response headers with lowercase names.
 * @property charset charset from `Content-Type`, null when the server did not send one.
 */
class HttpResponse(
    val code: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val charset: Charset?,
    val body: InputStream,
) : Closeable {

    /** True for 2xx codes. */
    val isSuccessful: Boolean
        get() = code in 200..299

    /** First value of the header [name], case-insensitive. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /**
     * Reads the whole body into memory. Throws [AppError.TorrentTooLarge] if it is longer
     * than [limit] bytes, so a hostile server cannot exhaust memory.
     */
    fun bytes(limit: Long = Long.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = body.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw AppError.TorrentTooLarge("response body is larger than $limit bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Reads the whole body as text in [charset], or [fallback] when the server sent none. */
    fun text(fallback: Charset = Charsets.UTF_8): String = String(bytes(), charset ?: fallback)

    override fun close() {
        body.close()
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
