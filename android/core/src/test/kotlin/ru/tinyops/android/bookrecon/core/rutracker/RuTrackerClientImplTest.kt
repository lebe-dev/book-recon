package ru.tinyops.android.bookrecon.core.rutracker

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.rutracker.ScriptedHttpClient.Call
import ru.tinyops.android.bookrecon.core.rutracker.ScriptedHttpClient.Reply
import kotlin.time.Duration.Companion.seconds

class RuTrackerClientImplTest {
    private val base = "https://rutracker.org/forum"
    private val loginUrl = "$base/login.php"

    /** Cookie header of the shared store, as the client sees it. */
    private var cookieHeader: String? = null

    /** Number of login POSTs the site answered with a session. */
    private var sessions = 0

    private val http = ScriptedHttpClient()
    private val settings = FakeSettingsRepository(Settings(rutrackerLogin = "reader"), password = "secret")

    private fun client(timeout: kotlin.time.Duration = 60.seconds) = RuTrackerClientImpl(
        http = http,
        settings = settings,
        cookies = { url -> cookieHeader.takeIf { url.startsWith("https://rutracker.org/") } },
        requestTimeout = timeout,
    )

    /** A site where login always succeeds and pages need a session. */
    private fun site(searchPage: String = "search-audio.html", torrent: ByteArray = TORRENT) {
        http.route = { call ->
            when {
                call.method == "POST" && call.url == loginUrl -> {
                    sessions++
                    cookieHeader = "bb_ssl=1; bb_session=0-1-abc"
                    Reply.page("search-empty.html", finalUrl = "$base/index.php")
                }
                cookieHeader == null -> Reply.page("login-required.html", finalUrl = "$loginUrl?redirect=tracker.php")
                call.url.startsWith("$base/tracker.php") -> Reply.page(searchPage)
                call.url.startsWith("$base/dl.php") -> Reply(body = torrent, headers = mapOf("content-type" to "application/x-bittorrent; charset=Windows-1251"))
                else -> Reply.notFound(call.url)
            }
        }
    }

    // --- login ---

    @Test
    fun `login posts the credentials in windows-1251`() = runTest {
        site()

        client().login()

        val post = http.calls.single()
        assertEquals("POST", post.method)
        assertEquals(loginUrl, post.url)
        assertEquals(
            listOf("redirect" to "index.php", "login_username" to "reader", "login_password" to "secret", "login" to "вход"),
            post.form,
        )
        assertEquals(RuTrackerParser.CHARSET, post.charset)
        assertTrue(client().isLoggedIn())
    }

    @Test
    fun `missing login or password needs credentials and sends nothing`() = runTest {
        site()
        settings.update { it.copy(rutrackerLogin = " ") }
        assertThrows<AppError.RuTrackerAuthRequired> { client().login() }

        settings.update { it.copy(rutrackerLogin = "reader") }
        settings.setRutrackerPassword(null)
        assertThrows<AppError.RuTrackerAuthRequired> { client().login() }

        assertEquals(emptyList<Call>(), http.calls)
    }

    @Test
    fun `rejected password fails the login`() = runTest {
        http.route = { Reply.page("login-failed.html", finalUrl = loginUrl) }

        assertThrows<AppError.RuTrackerLoginFailed> { client().login() }
    }

    @Test
    fun `captcha asks the user to log in by hand`() = runTest {
        http.route = { Reply.page("login-captcha.html", finalUrl = loginUrl) }

        val error = assertThrows<AppError.RuTrackerCaptcha> { client().login() }
        assertEquals(loginUrl, error.loginUrl)
    }

    @Test
    fun `unexpected page after login is a parse error`() = runTest {
        http.route = { Reply.page("dl-not-found.html", finalUrl = "$base/index.php") }

        assertThrows<AppError.ParseError> { client().login() }
    }

    @Test
    fun `cloudflare challenge that reached the client is reported with its url`() = runTest {
        http.route = { Reply(code = 403, headers = mapOf("cf-mitigated" to "challenge"), finalUrl = loginUrl) }

        val error = assertThrows<AppError.CloudflareChallenge> { client().login() }
        assertEquals(loginUrl, error.url)
    }

    @Test
    fun `server error means the service is down`() = runTest {
        http.route = { Reply(code = 502) }

        assertThrows<AppError.ServiceDown> { client().login() }
    }

    @Test
    fun `logged in state comes from the session cookie`() = runTest {
        assertFalse(client().isLoggedIn())
        cookieHeader = "bb_ssl=1"
        assertFalse(client().isLoggedIn())
        cookieHeader = "bb_ssl=1; bb_session=0-1-abc"
        assertTrue(client().isLoggedIn())
    }

    // --- mirrors ---

    private val mirrorBase = "https://rutracker.net/forum"

    private fun mirrorClient() = RuTrackerClientImpl(
        http = http,
        settings = settings,
        cookies = { url -> cookieHeader.takeIf { url.startsWith("https://rutracker.org/") || url.startsWith("https://rutracker.net/") } },
        baseUrls = listOf(base, mirrorBase),
    )

    @Test
    fun `search moves to the next mirror when the first one is unreachable and logs in there`() = runTest {
        http.route = { call ->
            when {
                call.url.startsWith(base) -> throw AppError.Network("no address for rutracker.org")
                call.method == "POST" && call.url == "$mirrorBase/login.php" -> {
                    cookieHeader = "bb_session=0-1-abc"
                    Reply.page("search-empty.html", finalUrl = "$mirrorBase/index.php")
                }
                cookieHeader == null -> Reply.page("login-required.html", finalUrl = "$mirrorBase/login.php?redirect=tracker.php")
                call.url.startsWith("$mirrorBase/tracker.php") -> Reply.page("search-audio.html")
                else -> Reply.notFound(call.url)
            }
        }

        val topics = mirrorClient().search("Дозор", MediaKind.AUDIO, 3)

        assertEquals(3, topics.size)
        assertTrue(http.calls.any { it.url.startsWith("$mirrorBase/tracker.php") })
    }

    @Test
    fun `the mirror that worked is tried first next time`() = runTest {
        http.route = { call ->
            when {
                call.url.startsWith(base) -> throw AppError.Network("no address for rutracker.org")
                else -> Reply.page("search-audio.html")
            }
        }
        cookieHeader = "bb_session=0-1-abc"
        val client = mirrorClient()

        client.search("Дозор", MediaKind.AUDIO, 3)
        val before = http.calls.size
        client.search("Дозор", MediaKind.AUDIO, 3)

        assertTrue(http.calls.drop(before).all { it.url.startsWith(mirrorBase) })
    }

    @Test
    fun `all mirrors down reports the failure`() = runTest {
        http.route = { throw AppError.Network("offline") }

        assertThrows<AppError.Network> { mirrorClient().search("Дозор", MediaKind.AUDIO, 3) }
    }

    @Test
    fun `a missing torrent does not try the other mirror`() = runTest {
        cookieHeader = "bb_session=0-1-abc"
        http.route = { call -> Reply(body = "Error: attachment data not found".toByteArray(RuTrackerParser.CHARSET), finalUrl = call.url) }

        assertThrows<AppError.NotFound> { mirrorClient().fetchTorrent("123") }
        assertEquals(1, http.calls.size)
    }

    // --- search ---

    @Test
    fun `audio search uses audiobook forums, sorts by seeders and encodes the query in windows-1251`() = runTest {
        site()
        cookieHeader = "bb_session=x"

        val topics = client().search("  Лукьяненко Дозор ", MediaKind.AUDIO, 5)

        val get = http.calls.single()
        assertEquals(
            "$base/tracker.php?f=${RuTrackerForums.AUDIO.joinToString(",")}&o=10&s=2" +
                "&nm=%CB%F3%EA%FC%FF%ED%E5%ED%EA%EE+%C4%EE%E7%EE%F0",
            get.url,
        )
        assertEquals(listOf("1224115", "4911904"), topics.take(2).map { it.topicId })
        assertEquals(5, topics.size)
        assertTrue(topics.all { it.kind == MediaKind.AUDIO })
    }

    @Test
    fun `book search uses e-book forums`() = runTest {
        site(searchPage = "search-books.html")
        cookieHeader = "bb_session=x"

        val topics = client().search("Лукьяненко", MediaKind.BOOK, 20)

        assertTrue(http.calls.single().url.startsWith("$base/tracker.php?f=${RuTrackerForums.BOOKS.joinToString(",")}&"))
        assertEquals(20, topics.size)
        assertTrue(topics.all { it.kind == MediaKind.BOOK })
    }

    @Test
    fun `blank query or zero limit returns nothing without a request`() = runTest {
        site()

        assertEquals(emptyList<Any>(), client().search("   ", MediaKind.AUDIO, 10))
        assertEquals(emptyList<Any>(), client().search("Дозор", MediaKind.AUDIO, 0))
        assertEquals(emptyList<Call>(), http.calls)
    }

    @Test
    fun `expired session logs in once and repeats the search`() = runTest {
        site()

        val topics = client().search("Дозор", MediaKind.AUDIO, 3)

        assertEquals(listOf("GET", "POST", "GET"), http.calls.map { it.method })
        assertEquals(1, sessions)
        assertEquals(3, topics.size)
    }

    @Test
    fun `login page after a fresh login fails instead of looping`() = runTest {
        http.route = { call ->
            if (call.method == "POST") {
                Reply.page("search-empty.html", finalUrl = "$base/index.php")
            } else {
                Reply.page("login-required.html", finalUrl = "$loginUrl?redirect=tracker.php")
            }
        }

        assertThrows<AppError.RuTrackerLoginFailed> { client().search("Дозор", MediaKind.AUDIO, 3) }
        assertEquals(listOf("GET", "POST", "GET"), http.calls.map { it.method })
    }

    @Test
    fun `captcha during the automatic login is passed to the caller`() = runTest {
        http.route = { call ->
            if (call.method == "POST") {
                Reply.page("login-captcha.html", finalUrl = loginUrl)
            } else {
                Reply.page("login-required.html", finalUrl = "$loginUrl?redirect=tracker.php")
            }
        }

        assertThrows<AppError.RuTrackerCaptcha> { client().search("Дозор", MediaKind.AUDIO, 3) }
    }

    @Test
    fun `expired session without credentials needs credentials`() = runTest {
        site()
        settings.setRutrackerPassword(null)

        assertThrows<AppError.RuTrackerAuthRequired> { client().search("Дозор", MediaKind.AUDIO, 3) }
        assertEquals(listOf("GET"), http.calls.map { it.method })
    }

    @Test
    fun `concurrent searches with an expired session share one login`() = runTest {
        site()
        val client = client()

        val results = listOf(MediaKind.AUDIO, MediaKind.BOOK, MediaKind.AUDIO)
            .map { kind -> async { client.search("Дозор", kind, 2) } }
            .awaitAll()

        assertEquals(1, sessions)
        assertTrue(results.all { it.size == 2 })
    }

    @Test
    fun `page that is neither results nor login is a parse error`() = runTest {
        http.route = { Reply.page("dl-not-found.html") }
        cookieHeader = "bb_session=x"

        assertThrows<AppError.ParseError> { client().search("Дозор", MediaKind.AUDIO, 3) }
    }

    @Test
    fun `slow site runs into the request timeout`() = runTest(StandardTestDispatcher()) {
        val slow = object : ru.tinyops.android.bookrecon.core.port.HttpClient by http {
            override suspend fun get(url: String, headers: Map<String, String>) = awaitCancellation()
        }
        cookieHeader = "bb_session=x"
        val client = RuTrackerClientImpl(slow, settings, cookies = { cookieHeader }, requestTimeout = 5.seconds)

        assertThrows<AppError.Timeout> { client.search("Дозор", MediaKind.AUDIO, 3) }
    }

    // --- torrent ---

    @Test
    fun `torrent file is downloaded by topic id`() = runTest {
        site()
        cookieHeader = "bb_session=x"

        val bytes = client().fetchTorrent("1224115")

        assertArrayEquals(TORRENT, bytes)
        val get = http.calls.single()
        assertEquals("$base/dl.php?t=1224115", get.url)
        assertEquals("$base/viewtopic.php?t=1224115", get.headers["Referer"])
    }

    @Test
    fun `torrent download with an expired session logs in and retries`() = runTest {
        site()

        assertArrayEquals(TORRENT, client().fetchTorrent("1224115"))
        assertEquals(listOf("GET", "POST", "GET"), http.calls.map { it.method })
    }

    @Test
    fun `torrent larger than 10 MB is rejected`() = runTest {
        site(torrent = ByteArray(10 * 1024 * 1024 + 1) { 'd'.code.toByte() })
        cookieHeader = "bb_session=x"

        assertThrows<AppError.TorrentTooLarge> { client().fetchTorrent("1224115") }
    }

    @Test
    fun `missing torrent is not found`() = runTest {
        http.route = { Reply.page("dl-not-found.html") }
        cookieHeader = "bb_session=x"

        assertThrows<AppError.NotFound> { client().fetchTorrent("1") }
    }

    @Test
    fun `non numeric topic id is not found without a request`() = runTest {
        assertThrows<AppError.NotFound> { client().fetchTorrent("12a") }
        assertThrows<AppError.NotFound> { client().fetchTorrent("") }
        assertEquals(emptyList<Call>(), http.calls)
    }

    @Test
    fun `body that is not bencoded is a parse error`() = runBlocking {
        http.route = { Reply(body = "<html>oops</html>".toByteArray(), headers = mapOf("content-type" to "application/x-bittorrent")) }
        cookieHeader = "bb_session=x"

        assertThrows<AppError.ParseError> { client().fetchTorrent("1") }
        Unit
    }

    private companion object {
        /** A tiny made-up torrent; real ones carry a passkey and are never committed. */
        val TORRENT = "d8:announce22:http://tracker.invalid4:infod6:lengthi1e4:name1:a12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaaee"
            .toByteArray(Charsets.US_ASCII)
    }
}
