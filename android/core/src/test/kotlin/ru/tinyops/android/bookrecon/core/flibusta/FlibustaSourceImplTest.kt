package ru.tinyops.android.bookrecon.core.flibusta

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeHttpClient
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val BASE = "https://flibusta.test"

private fun searchUrl(query: String) = "$BASE/booksearch?ask=" + java.net.URLEncoder.encode(query, Charsets.UTF_8)

private fun bookPage(id: String, vararg formats: String) =
    "<html><body><script>var bookId = $id</script><h1 class=\"title\">Книга $id</h1><a href=\"/a/1\">Автор</a> " +
        formats.joinToString(" ") { "<a href=\"/b/$id/$it\">($it)</a>" } + "</body></html>"

private fun zipOf(vararg entries: Pair<String, ByteArray>, charset: Charset = Charsets.UTF_8): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out, charset).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

class FlibustaSourceImplTest {
    private val http = FakeHttpClient()

    /** The source under test; blocking work runs on the test scheduler, so virtual time holds. */
    private fun TestScope.flibusta(
        client: HttpClient = http,
        baseUrl: suspend () -> String = { "$BASE/" },
        fallbackUrls: suspend () -> List<String> = { emptyList() },
        timeout: Duration = 30.seconds,
    ) = FlibustaSourceImpl(client, baseUrl, fallbackUrls, timeout, StandardTestDispatcher(testScheduler))

    @TempDir
    lateinit var dir: File

    // --- search ---

    @Test
    fun `search resolves formats from book pages and drops books without downloads`() = runTest {
        http.respond(searchUrl("Тынянов"), fixture("search-books-authors.html"))
        http.respond("$BASE/b/692119", bookPage("692119", "fb2", "epub"))
        http.respond("$BASE/b/443953", fixture("book-no-downloads.html").replace("435845", "443953"))
        http.respond("$BASE/b/507392", fixture("book-multi-author.html"))
        http.respond("$BASE/b/458415", bookPage("458415", "mobi"))
        http.respond("$BASE/b/456557", bookPage("456557", "fb2"))
        http.respond("$BASE/b/57146", bookPage("57146", "epub"))

        val results = flibusta().search("Тынянов", 20)

        assertEquals(listOf("692119", "507392", "458415", "456557", "57146"), results.map { it.sourceId })
        val first = results.first()
        assertEquals(Source.FLIBUSTA, first.source)
        assertEquals(MediaKind.BOOK, first.kind)
        assertEquals("Тынянов. Писатель и ученый", first.title)
        assertEquals(listOf(Format.FB2, Format.EPUB), first.formats)
        assertEquals(listOf(Format.PDF), results[1].formats)
        assertTrue(results.none { it.alreadyDownloaded })
    }

    @Test
    fun `book page failure falls back to common formats`() = runTest {
        http.respond(searchUrl("Пушкин"), fixture("search-alt-title.html"))
        http.respond("$BASE/b/435845", fixture("book-converted.html"))
        http.respond("$BASE/b/552916", "oops", code = 500)
        // /b/123456 is not answered: the fake replies 404.

        val results = flibusta().search("Пушкин", 10)

        assertEquals(3, results.size)
        assertEquals(listOf(Format.FB2, Format.EPUB, Format.MOBI), results[1].formats)
        assertEquals(listOf(Format.FB2, Format.EPUB, Format.MOBI), results[2].formats)
    }

    @Test
    fun `search falls back to the books of matched authors`() = runTest {
        http.respond(searchUrl("Шинзен"), fixture("search-authors-only.html"))
        http.respond("$BASE/a/151853", fixture("author-mixed.html"))

        val results = flibusta().search("Шинзен", 10)

        assertEquals(listOf("617927", "112458", "888888"), results.map { it.sourceId })
        assertEquals("Шинзен Янг", results.first().author)
        // Author pages already carry formats: no book page is fetched.
        assertTrue(http.requests.none { it.url.contains("/b/") })
    }

    @Test
    fun `author fallback follows at most three authors and respects the limit`() = runTest {
        val authors = (1..5).joinToString("") { "<li><a href=\"/a/$it\">Автор $it</a></li>" }
        http.respond(searchUrl("x"), "<html><body><h3>Найденные писатели (1 - 5 из 5):</h3><ul>$authors</ul></body></html>")
        http.respond("$BASE/a/1", "oops", code = 500)
        for (i in 2..5) http.respond("$BASE/a/$i", fixture("author-mixed.html"))

        val results = flibusta().search("x", 4)

        assertEquals(4, results.size)
        assertEquals(listOf("Автор 2", "Автор 2", "Автор 2", "Автор 3"), results.map { it.author })
        val followed = http.requests.map { it.url }.filter { it.contains("/a/") }
        assertEquals(listOf("$BASE/a/1", "$BASE/a/2", "$BASE/a/3"), followed)
    }

    @Test
    fun `empty search returns nothing`() = runTest {
        http.respond(searchUrl("zzqq"), fixture("search-empty.html"))

        assertTrue(flibusta().search("zzqq", 10).isEmpty())
        assertTrue(flibusta().search("   ", 10).isEmpty())
        assertTrue(flibusta().search("Пушкин", 0).isEmpty())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `an unreachable mirror gives way to the next one, which is then tried first`() = runTest {
        val other = "https://flibusta.other"
        http.respond("$BASE/b/1", "oops", code = 503)
        http.respond("$other/b/1", bookPage("1", "fb2"))
        val source = flibusta(fallbackUrls = { listOf(other) })

        assertEquals(1, source.details("1").id.toInt())
        assertEquals(listOf("$BASE/b/1", "$other/b/1"), http.requests.map { it.url })

        source.details("1")
        assertEquals("$other/b/1", http.requests.last().url)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun `a missing book does not move on to another mirror`() = runTest {
        val other = "https://flibusta.other"
        http.respond("$BASE/b/1", fixture("book-missing.html"))
        http.respond("$other/b/1", bookPage("1", "fb2"))

        assertThrows<AppError.NotFound> { flibusta(fallbackUrls = { listOf(other) }).details("1") }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `the last failure is thrown when every mirror is down`() = runTest {
        http.respond("$BASE/b/1", "oops", code = 503)
        http.respond("https://flibusta.other/b/1", "oops", code = 502)

        assertThrows<AppError.ServiceDown> { flibusta(fallbackUrls = { listOf("https://flibusta.other") }).details("1") }
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `search errors are app errors`() = runTest {
        http.respond(searchUrl("down"), "oops", code = 503)

        val error = assertThrows<AppError> { flibusta().search("down", 10) }
        assertEquals(ErrorCode.SERVICE_DOWN, error.code)

        http.behavior.failNext(AppError.Network())
        assertEquals(ErrorCode.NETWORK, assertThrows<AppError> { flibusta().search("down", 10) }.code)
    }

    @Test
    fun `book pages are fetched at most four at a time`() = runTest {
        val items = (1..12).joinToString("") { "<li><a href=\"/b/$it\">Книга $it</a> - <a href=\"/a/1\">Автор</a></li>" }
        http.respond(searchUrl("q"), "<html><body><h3>Найденные книги</h3><ul>$items</ul></body></html>")
        for (i in 1..12) http.respond("$BASE/b/$i", bookPage("$i", "fb2"))
        val counting = ConcurrencyCountingClient(http)

        val results = flibusta(counting).search("q", 20)

        assertEquals(12, results.size)
        assertEquals(4, counting.maxInFlight)
    }

    @Test
    fun `slow search ends with a timeout error`() = runTest {
        http.respond(searchUrl("q"), fixture("search-empty.html"))
        http.behavior.delay = 1.minutes
        val slow = flibusta(timeout = 30.seconds)

        assertEquals(ErrorCode.TIMEOUT, assertThrows<AppError> { slow.search("q", 10) }.code)
    }

    @Test
    fun `base url is read on every call`() = runTest {
        var base = "https://one.test"
        val switching = flibusta(baseUrl = { base })

        runCatching { switching.search("q", 1) }
        base = "https://two.test"
        runCatching { switching.search("q", 1) }

        assertEquals(listOf("https://one.test", "https://two.test"), http.requests.map { it.url.substringBefore("/booksearch") })
    }

    // --- details ---

    @Test
    fun `details parse the book page`() = runTest {
        http.respond("$BASE/b/617927", fixture("book-pdf-only.html"))

        val details = flibusta().details("617927")

        assertEquals("Мартин Клеппман", details.author)
        assertEquals(listOf(Format.PDF), details.formats)
    }

    @Test
    fun `details of a missing book throw not found`() = runTest {
        http.respond("$BASE/b/99999999", fixture("book-missing.html"))

        assertEquals(ErrorCode.NOT_FOUND, assertThrows<AppError> { flibusta().details("99999999") }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<AppError> { flibusta().details("../etc") }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<AppError> { flibusta().details("123") }.code)
    }

    // --- download ---

    @Test
    fun `zip with one fb2 is unpacked under the entry name`() = runTest {
        val zip = zipOf("Author. Title.fb2" to "fake-fb2".toByteArray())
        http.respond("$BASE/b/435845/fb2", zip, headers = mapOf("Content-Type" to "application/zip"))

        val file = flibusta().download("435845", Format.FB2, dir)

        assertEquals("Author. Title.fb2", file.suggestedName)
        assertEquals("fake-fb2", file.tempFile.readText())
        assertEquals(dir, file.tempFile.parentFile)
        assertEquals(listOf(file.tempFile), dir.listFiles()!!.toList())
    }

    @Test
    fun `zip entry name in cp866 is decoded`() = runTest {
        val raw = String("Книга.fb2".toByteArray(Charset.forName("IBM866")), Charsets.ISO_8859_1)
        val zip = zipOf(raw to "x".toByteArray(), charset = Charsets.ISO_8859_1)
        http.respond("$BASE/b/1/fb2", zip)

        assertEquals("Книга.fb2", flibusta().download("1", Format.FB2, dir).suggestedName)
    }

    @Test
    fun `epub is kept whole`() = runTest {
        val epub = zipOf("mimetype" to "application/epub+zip".toByteArray(), "OEBPS/content.opf" to "<package/>".toByteArray())
        http.respond("$BASE/b/435845/epub", epub)
        http.respond("$BASE/b/435845", fixture("book-converted.html"))

        val file = flibusta().download("435845", Format.EPUB, dir)

        assertArrayEquals(epub, file.tempFile.readBytes())
        assertEquals("Юрий Николаевич Тынянов. Пушкин.epub", file.suggestedName)
    }

    @Test
    fun `plain file takes the name from content disposition`() = runTest {
        http.respond(
            "$BASE/b/617927/download",
            "%PDF-1.4 fake".toByteArray(),
            headers = mapOf("Content-Type" to "application/pdf", "Content-Disposition" to "attachment; filename=\"Kleppmann.pdf\""),
        )

        val file = flibusta().download("617927", Format.PDF, dir)

        assertEquals("Kleppmann.pdf", file.suggestedName)
        assertEquals("%PDF-1.4 fake", file.tempFile.readText())
        assertEquals("$BASE/b/617927/download", http.requests.single().url)
    }

    @Test
    fun `plain file without disposition gets a name from author and title`() = runTest {
        http.respond("$BASE/b/507392/download", "%PDF".toByteArray())
        http.respond("$BASE/b/507392", fixture("book-multi-author.html"))

        val name = flibusta().download("507392", Format.PDF, dir).suggestedName

        assertTrue(name.startsWith("Вениамин Александрович Каверин, "))
        assertTrue(name.endsWith(". Юрий Тынянов. Писатель и ученый.pdf"))
    }

    @Test
    fun `fallback name when the book page is unavailable`() = runTest {
        http.respond("$BASE/b/5/fb2", "content".toByteArray())

        assertEquals("book.fb2", flibusta().download("5", Format.FB2, dir).suggestedName)
    }

    @Test
    fun `html answer means the format is not available`() = runTest {
        http.respond(
            "$BASE/b/617927/fb2",
            "<html>Книга не найдена</html>".toByteArray(),
            headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
        )

        val error = assertThrows<AppError> { flibusta().download("617927", Format.FB2, dir) }

        assertEquals(AppError.FormatNotAvailable(Format.FB2), error)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `empty zip is an error and leaves no files`() = runTest {
        http.respond("$BASE/b/2/fb2", zipOf())

        assertEquals(ErrorCode.PARSE_ERROR, assertThrows<AppError> { flibusta().download("2", Format.FB2, dir) }.code)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `http errors and broken streams are app errors`() = runTest {
        http.respond("$BASE/b/3/fb2", ByteArray(0), code = 404)
        http.respond("$BASE/b/4/fb2", ByteArray(0), code = 502)

        assertEquals(ErrorCode.NOT_FOUND, assertThrows<AppError> { flibusta().download("3", Format.FB2, dir) }.code)
        assertEquals(ErrorCode.SERVICE_DOWN, assertThrows<AppError> { flibusta().download("4", Format.FB2, dir) }.code)

        val broken = object : HttpClient by http {
            override suspend fun get(url: String, headers: Map<String, String>) =
                HttpResponse(200, url, emptyMap(), null, FailingStream())
        }
        val error = assertThrows<AppError> { flibusta(broken).download("6", Format.FB2, dir) }
        assertEquals(ErrorCode.NETWORK, error.code)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `download streams to disk`() = runTest {
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        http.respond("$BASE/b/7/download", payload, headers = mapOf("Content-Type" to "application/pdf"))

        val file = flibusta().download("7", Format.PDF, dir)

        assertArrayEquals(payload, file.tempFile.readBytes())
    }
}

/** Delegates to [delegate] and records the highest number of requests running at once. */
private class ConcurrencyCountingClient(private val delegate: HttpClient) : HttpClient by delegate {
    private val inFlight = AtomicInteger()

    @Volatile
    var maxInFlight = 0
        private set

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val now = inFlight.incrementAndGet()
        synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
        try {
            delay(100)
            return delegate.get(url, headers)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private class FailingStream : InputStream() {
    private var sent = false

    override fun read(): Int = throw java.io.IOException("connection reset")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (sent) throw java.io.IOException("connection reset")
        sent = true
        b[off] = 1
        return 1
    }
}
