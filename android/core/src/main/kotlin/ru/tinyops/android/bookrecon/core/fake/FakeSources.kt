package ru.tinyops.android.bookrecon.core.fake

import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.port.DownloadedFile
import ru.tinyops.android.bookrecon.core.port.FlibustaSource
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP client that answers from a table filled by [respond]; unknown URLs get 404. Every
 * request is recorded in [requests].
 */
class FakeHttpClient(val behavior: FakeBehavior = FakeBehavior()) : HttpClient {
    /** A recorded request; [form] is empty for GET. */
    data class Request(val method: String, val url: String, val form: List<Pair<String, String>> = emptyList())

    private class Canned(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>, val charset: Charset?)

    private val responses = ConcurrentHashMap<String, Canned>()
    private val recorded = mutableListOf<Request>()

    val requests: List<Request>
        get() = synchronized(recorded) { recorded.toList() }

    /** Makes requests to [url] answer with [body]. Header names are matched case-insensitively. */
    fun respond(
        url: String,
        body: ByteArray,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
        charset: Charset? = Charsets.UTF_8,
    ) {
        val normalized = headers.entries.associate { (name, value) -> name.lowercase() to listOf(value) }
        responses[url] = Canned(code, body, normalized, charset)
    }

    fun respond(url: String, body: String, code: Int = 200, charset: Charset = Charsets.UTF_8) =
        respond(url, body.toByteArray(charset), code, charset = charset)

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        record(Request("GET", url))
        behavior.step()
        return answer(url)
    }

    override suspend fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        headers: Map<String, String>,
        charset: Charset,
    ): HttpResponse {
        record(Request("POST", url, form))
        behavior.step()
        return answer(url)
    }

    private fun record(request: Request) {
        synchronized(recorded) { recorded += request }
    }

    private fun answer(url: String): HttpResponse {
        val canned = responses[url] ?: Canned(404, ByteArray(0), emptyMap(), null)
        return HttpResponse(canned.code, url, canned.headers, canned.charset, canned.body.inputStream())
    }
}

/**
 * Flibusta over [books]: search matches title or author by substring, ignoring case;
 * download writes a small placeholder file into the given folder.
 */
class FakeFlibustaSource(
    private val books: List<FlibustaBookDetails> = FakeData.flibustaBooks,
    val behavior: FakeBehavior = FakeBehavior(),
) : FlibustaSource {

    /** How many times [search] was called. */
    @Volatile
    var searchCalls: Int = 0
        private set

    override suspend fun search(query: String, limit: Int): List<BookResult> {
        searchCalls++
        behavior.step()
        val needle = query.trim().lowercase()
        return books
            .filter { needle in it.title.lowercase() || needle in it.author.lowercase() }
            .take(limit)
            .map { BookResult(Source.FLIBUSTA, it.id, it.title, it.author, MediaKind.BOOK, it.formats) }
    }

    override suspend fun details(bookId: String): FlibustaBookDetails {
        behavior.step()
        return find(bookId)
    }

    override suspend fun download(bookId: String, format: Format, dir: File): DownloadedFile {
        behavior.step()
        val book = find(bookId)
        if (format !in book.formats) throw AppError.FormatNotAvailable(format)
        dir.mkdirs()
        val file = File.createTempFile("flibusta-$bookId-", ".part", dir)
        file.writeText("${book.author}. ${book.title}\n")
        return DownloadedFile(file, "${book.author} - ${book.title}.${format.extension}")
    }

    private fun find(bookId: String): FlibustaBookDetails =
        books.firstOrNull { it.id == bookId } ?: throw AppError.NotFound("flibusta book $bookId not found")
}

/**
 * RuTracker over [topics]. Starts logged in; with [credentialsSet] false login fails with
 * RuTrackerAuthRequired, with [captcha] true it fails with RuTrackerCaptcha. Search logs in
 * automatically when needed, like the real client.
 */
class FakeRuTrackerClient(
    private val topics: List<TorrentTopic> = FakeData.topics,
    val behavior: FakeBehavior = FakeBehavior(),
) : RuTrackerClient {
    @Volatile
    var loggedIn: Boolean = true

    @Volatile
    var credentialsSet: Boolean = true

    @Volatile
    var captcha: Boolean = false

    /** Number of successful [login] calls. */
    @Volatile
    var loginCount: Int = 0
        private set

    override suspend fun login() {
        behavior.step()
        if (!credentialsSet) throw AppError.RuTrackerAuthRequired()
        if (captcha) throw AppError.RuTrackerCaptcha(LOGIN_URL)
        loggedIn = true
        loginCount++
    }

    override suspend fun isLoggedIn(): Boolean = loggedIn

    override suspend fun search(query: String, kind: MediaKind, limit: Int): List<TorrentTopic> {
        if (!loggedIn) login()
        behavior.step()
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        return topics
            .filter { it.kind == kind && it.seeders > 0 }
            .filter { topic -> words.all { it in topic.title.lowercase() } }
            .take(limit)
    }

    override suspend fun fetchTorrent(topicId: String): ByteArray {
        if (!loggedIn) login()
        behavior.step()
        if (topics.none { it.topicId == topicId }) throw AppError.NotFound("topic $topicId not found")
        return FakeData.torrentBytes(topicId)
    }

    companion object {
        const val LOGIN_URL = "https://rutracker.org/forum/login.php"
    }
}
