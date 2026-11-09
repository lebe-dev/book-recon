package ru.tinyops.android.bookrecon.core.flibusta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * Runs against the live site. Enable with `FLIBUSTA_LIVE=1`; `FLIBUSTA_BASE_URL` picks a
 * mirror when the default one is unreachable, for example `https://flibusta.site`.
 */
@EnabledIfEnvironmentVariable(named = "FLIBUSTA_LIVE", matches = "1|true")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class FlibustaLiveTest {
    private val base = System.getenv("FLIBUSTA_BASE_URL")?.takeIf { it.isNotBlank() } ?: Settings.DEFAULT_FLIBUSTA_BASE_URL
    private val source = FlibustaSourceImpl(JdkClient(), baseUrl = { base })

    @TempDir
    lateinit var dir: File

    @Test
    fun `search by author name`() = runBlocking {
        // Flibusta matches the query as a substring, so "Пушкин Тынянов" finds nothing.
        val results = source.search("Тынянов", 10)

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.formats.isNotEmpty() })
    }

    @Test
    fun `search that matches only an author`() = runBlocking {
        val results = source.search("Шинзен", 10)

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.author == "Шинзен Янг" })
    }

    @Test
    fun `book with converted formats downloads fb2 and epub`() = runBlocking {
        val details = source.details("435845")
        assertEquals("Пушкин", details.title)

        for (format in listOf(Format.FB2, Format.EPUB)) {
            val file = source.download("435845", format, dir)
            assertTrue(file.tempFile.length() > 0)
            assertTrue(file.suggestedName.endsWith(".${format.extension}"), file.suggestedName)
        }
    }

    @Test
    fun `book with the original pdf only`() = runBlocking {
        val book = source.search("Высоконагруженные приложения", 5).first()
        assertTrue(Format.PDF in book.formats)
        assertFalse(Format.FB2 in book.formats)

        val file = source.download(book.sourceId, Format.PDF, dir)
        assertEquals("%PDF", file.tempFile.inputStream().use { String(it.readNBytes(4)) })
    }
}

/** A minimal [HttpClient] over the JDK client, for the live test only. */
private class JdkClient : HttpClient {
    private val client = JdkHttpClient.newBuilder()
        .followRedirects(JdkHttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().header("User-Agent", "book-recon-test").build()
        val response = client.send(request, JdkHttpResponse.BodyHandlers.ofInputStream())
        val responseHeaders = response.headers().map().mapKeys { it.key.lowercase() }
        val charset = response.headers().firstValue("content-type").orElse("")
            .substringAfter("charset=", "").takeIf { it.isNotBlank() }
            ?.let { runCatching { Charset.forName(it.trim()) }.getOrNull() }
        HttpResponse(response.statusCode(), response.uri().toString(), responseHeaders, charset, response.body())
    }

    override suspend fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        headers: Map<String, String>,
        charset: Charset,
    ): HttpResponse = throw UnsupportedOperationException("not needed for flibusta")
}
