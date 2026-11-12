package ru.tinyops.android.bookrecon.core.rutracker

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Document
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.mirror.InMemoryMirrorMemory
import ru.tinyops.android.bookrecon.core.mirror.MirrorFallback
import ru.tinyops.android.bookrecon.core.mirror.MirrorMemory
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import java.io.IOException
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [RuTrackerClient] that talks to rutracker.org (or its mirror rutracker.net) directly.
 *
 * How the site behaves (checked in November 2026):
 * - Login is a POST of `login_username`, `login_password`, `login=вход` and `redirect` to
 *   `login.php`, form-encoded in windows-1251. Success answers 302 to `redirect` and sets the
 *   `bb_session` cookie (plus `bb_ssl`); a rejected password renders the login form again, a
 *   captcha adds its fields to that form.
 * - `login.php` sits behind a Cloudflare managed challenge; the transport solves it, a
 *   challenge that still reaches this client becomes [AppError.CloudflareChallenge].
 * - Without a session `tracker.php` and `dl.php` redirect to
 *   `login.php?redirect=<original page>`, which renders the login form with status 200.
 * - `tracker.php?f=<ids>&nm=<query>` returns at most 50 rows per page (`start=50` for the
 *   next one); `o=10&s=2` sorts by seeders, most first, so the first page holds the best
 *   results and no further page is fetched. `nm` is accepted in windows-1251 and in UTF-8.
 * - `dl.php?t=<id>` answers `application/x-bittorrent` with `Content-Disposition`; a missing
 *   topic answers 200 `text/html` with "Error: attachment data not found".
 *
 * @param baseUrls forum addresses in order of preference. A mirror that cannot be reached
 *   (no DNS name, timeout, 5xx) hands the request to the next one, and the last one that
 *   worked goes first. The session cookie belongs to a mirror, so each has its own login.
 * @param cookies returns the `Cookie` header the shared store holds for a URL, or null. The
 *   session itself is kept by the store; the client only checks for `bb_session`.
 * @param requestTimeout deadline of one request, including a Cloudflare check run by the
 *   transport, which alone can take half a minute.
 * @param mirrorMemory where the mirror that answered is kept, so it goes first after a restart.
 */
class RuTrackerClientImpl(
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val cookies: (url: String) -> String?,
    private val baseUrls: List<String> = DEFAULT_BASE_URLS,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    mirrorMemory: MirrorMemory = InMemoryMirrorMemory(),
) : RuTrackerClient {

    private val bases = baseUrls.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    private val mirrors = MirrorFallback("RuTracker", mirrorMemory)

    private val loginMutex = Mutex()

    /** Bumped after every successful login, so concurrent callers log in only once. */
    @Volatile
    private var sessionGeneration = 0

    override suspend fun login() {
        onMirrors { base -> loginMutex.withLock { loginLocked(base) } }
    }

    override suspend fun isLoggedIn(): Boolean = bases.any { hasSession(it) }

    private fun hasSession(base: String): Boolean =
        cookies("$base/").orEmpty().split(';').any { it.trim().startsWith("$SESSION_COOKIE=") }

    override suspend fun search(query: String, kind: MediaKind, limit: Int): List<TorrentTopic> {
        val text = query.trim().take(MAX_QUERY_LENGTH)
        if (limit <= 0 || text.isEmpty()) return emptyList()

        val doc = onMirrors { base ->
            val url = "$base/tracker.php?f=" + RuTrackerForums.forKind(kind).joinToString(",") +
                "&o=$ORDER_BY_SEEDERS&s=$SORT_DESCENDING&nm=" + URLEncoder.encode(text, RuTrackerParser.CHARSET)
            withSession(base) { fetchPage(url, "search") }
        }
        return RuTrackerParser.parseSearchResults(doc, kind).take(limit)
    }

    override suspend fun fetchTorrent(topicId: String): ByteArray {
        if (topicId.isEmpty() || !topicId.all(Char::isDigit)) {
            throw AppError.NotFound("rutracker topic id '$topicId' is not numeric")
        }
        return onMirrors { base ->
            val url = "$base/dl.php?t=$topicId"
            val headers = mapOf("Referer" to "$base/viewtopic.php?t=$topicId")
            withSession(base) {
                request("torrent download") { http.get(url, headers) }.use { response ->
                    checkStatus(response, "torrent download")
                    val bytes = readBody(response) { it.bytes(MAX_TORRENT_SIZE) }
                    torrentOrPage(response, bytes, topicId)
                }
            }
        }
    }

    private suspend fun <T> onMirrors(block: suspend (base: String) -> T): T = mirrors.run(bases, block)

    /**
     * Runs [block], which returns [Page.LoginRequired] when the session is gone. Then logs in
     * once, unless another caller already did, and runs [block] again.
     */
    private suspend fun <T> withSession(base: String, block: suspend () -> Page<T>): T {
        val generation = sessionGeneration
        val first = block()
        if (first is Page.Content) return first.value

        relogin(base, generation)
        val second = block()
        if (second is Page.Content) return second.value
        throw AppError.RuTrackerLoginFailed()
    }

    private suspend fun relogin(base: String, staleGeneration: Int) {
        loginMutex.withLock {
            if (sessionGeneration != staleGeneration) return
            loginLocked(base)
        }
    }

    private suspend fun loginLocked(base: String) {
        val loginUrl = "$base/login.php"
        val username = settings.settings.first().rutrackerLogin.trim()
        val password = settings.rutrackerPassword()
        if (username.isEmpty() || password.isNullOrEmpty()) throw AppError.RuTrackerAuthRequired()

        val form = listOf(
            "redirect" to "index.php",
            "login_username" to username,
            "login_password" to password,
            "login" to LOGIN_BUTTON,
        )
        val doc = request("login") { http.postForm(loginUrl, form, charset = RuTrackerParser.CHARSET) }
            .use { response ->
                checkStatus(response, "login")
                parseBody(response)
            }

        if (RuTrackerParser.hasCaptcha(doc)) throw AppError.RuTrackerCaptcha(loginUrl)
        if (RuTrackerParser.isLoginPage(doc)) throw AppError.RuTrackerLoginFailed()
        if (!RuTrackerParser.isLoggedIn(doc) && !hasSession(base)) {
            throw AppError.ParseError("rutracker login answered with an unexpected page")
        }
        sessionGeneration++
    }

    private suspend fun fetchPage(url: String, stage: String): Page<Document> =
        request(stage) { http.get(url) }.use { response ->
            checkStatus(response, stage)
            val doc = parseBody(response)
            if (isLoginRedirect(response) || RuTrackerParser.isLoginPage(doc)) Page.LoginRequired else Page.Content(doc)
        }

    /** The torrent itself, a login page for an expired session, or NotFound for anything else. */
    private fun torrentOrPage(response: HttpResponse, bytes: ByteArray, topicId: String): Page<ByteArray> {
        val type = response.header("Content-Type").orEmpty()
        if (type.contains("bittorrent", ignoreCase = true)) {
            if (bytes.firstOrNull() != BENCODE_DICT) throw AppError.ParseError("rutracker sent a torrent that is not bencoded")
            return Page.Content(bytes)
        }
        if (isLoginRedirect(response)) return Page.LoginRequired
        val doc = RuTrackerParser.parse(String(bytes, response.charset ?: RuTrackerParser.CHARSET), response.finalUrl)
        if (RuTrackerParser.isLoginPage(doc)) return Page.LoginRequired
        throw AppError.NotFound("rutracker has no torrent for topic $topicId")
    }

    private suspend fun parseBody(response: HttpResponse): Document {
        val html = readBody(response) { it.text(RuTrackerParser.CHARSET) }
        return RuTrackerParser.parse(html, response.finalUrl)
    }

    private suspend fun <T> readBody(response: HttpResponse, read: (HttpResponse) -> T): T = withContext(io) {
        try {
            read(response)
        } catch (e: IOException) {
            throw AppError.Network("reading the rutracker response failed", e)
        }
    }

    private fun isLoginRedirect(response: HttpResponse): Boolean =
        response.finalUrl.substringBefore('?').endsWith("/login.php")

    private suspend fun request(stage: String, call: suspend () -> HttpResponse): HttpResponse = try {
        withTimeout(requestTimeout) { call() }
    } catch (e: TimeoutCancellationException) {
        throw AppError.Timeout("rutracker $stage did not finish within $requestTimeout", e)
    }

    private fun checkStatus(response: HttpResponse, stage: String) {
        if (response.code == 200) return
        response.close()
        if (response.code == 403 && response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
            throw AppError.CloudflareChallenge(response.finalUrl)
        }
        if (response.code == 404) throw AppError.NotFound("rutracker $stage returned 404")
        throw AppError.ServiceDown("rutracker $stage returned status ${response.code}")
    }

    /** A page that either holds what was asked for or tells that the session is gone. */
    private sealed interface Page<out T> {
        data class Content<T>(val value: T) : Page<T>
        data object LoginRequired : Page<Nothing>
    }

    companion object {
        val DEFAULT_BASE_URLS = listOf("https://rutracker.org/forum", "https://rutracker.net/forum")

        /** One request may wait for a Cloudflare check, which alone takes up to half a minute. */
        val DEFAULT_REQUEST_TIMEOUT = 60.seconds

        /** `.torrent` files above this size are rejected; real ones are a few hundred KB. */
        const val MAX_TORRENT_SIZE = 10L * 1024 * 1024

        const val SESSION_COOKIE = "bb_session"

        /** The search field of `tracker.php` takes at most 100 characters. */
        private const val MAX_QUERY_LENGTH = 100

        private const val ORDER_BY_SEEDERS = 10
        private const val SORT_DESCENDING = 2
        private const val LOGIN_BUTTON = "вход"
        private const val BENCODE_DICT = 'd'.code.toByte()
    }
}
