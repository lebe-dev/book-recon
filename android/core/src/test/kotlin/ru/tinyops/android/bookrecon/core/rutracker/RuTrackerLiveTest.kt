package ru.tinyops.android.bookrecon.core.rutracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * Runs against the live site. Enable with `RUTRACKER_LIVE=1` and set `RUTRACKER_USERNAME`
 * and `RUTRACKER_PASSWORD`.
 *
 * `login.php` is behind a Cloudflare challenge that this plain client cannot pass: copy the
 * `cf_clearance` cookie and the User-Agent of a browser that passed it into
 * `RUTRACKER_CF_CLEARANCE` and `RUTRACKER_USER_AGENT` (the cookie is bound to both the
 * User-Agent and the IP address).
 */
@EnabledIfEnvironmentVariable(named = "RUTRACKER_LIVE", matches = "1|true")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class RuTrackerLiveTest {
    private val cookies = CookieManager().apply {
        System.getenv("RUTRACKER_CF_CLEARANCE")?.takeIf { it.isNotBlank() }?.let { value ->
            cookieStore.add(URI("https://rutracker.org/"), HttpCookie("cf_clearance", value).apply { domain = ".rutracker.org"; path = "/" })
        }
    }
    private val settings = FakeSettingsRepository(
        Settings(rutrackerLogin = System.getenv("RUTRACKER_USERNAME").orEmpty()),
        password = System.getenv("RUTRACKER_PASSWORD"),
    )
    private val client = RuTrackerClientImpl(
        http = JdkClient(cookies, System.getenv("RUTRACKER_USER_AGENT")),
        settings = settings,
        cookies = { url -> cookies.cookieStore.get(URI(url)).joinToString("; ") { "${it.name}=${it.value}" } },
    )

    @Test
    fun `login then search both kinds and fetch a torrent`() = runBlocking {
        client.login()
        assertTrue(client.isLoggedIn())

        val audio = client.search("Лукьяненко Дозор", MediaKind.AUDIO, 10)
        val books = client.search("Лукьяненко", MediaKind.BOOK, 10)
        assertTrue(audio.isNotEmpty())
        assertTrue(books.isNotEmpty())
        assertTrue((audio + books).all { it.seeders > 0 })

        val torrent = client.fetchTorrent(audio.first().topicId)
        assertEquals('d'.code.toByte(), torrent.first())
    }

    /** The transport the app will have, reduced to what the client needs. */
    private class JdkClient(cookies: CookieManager, private val userAgent: String?) : HttpClient {
        private val jdk = JdkHttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(JdkHttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
            send(HttpRequest.newBuilder(URI(url)).GET(), headers)

        override suspend fun postForm(
            url: String,
            form: List<Pair<String, String>>,
            headers: Map<String, String>,
            charset: Charset,
        ): HttpResponse {
            val body = form.joinToString("&") { (name, value) ->
                URLEncoder.encode(name, charset) + "=" + URLEncoder.encode(value, charset)
            }
            val request = HttpRequest.newBuilder(URI(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.US_ASCII))
            return send(request, headers)
        }

        private suspend fun send(builder: HttpRequest.Builder, headers: Map<String, String>): HttpResponse {
            userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
            headers.forEach { (name, value) -> builder.header(name, value) }
            val response = withContext(Dispatchers.IO) {
                jdk.send(builder.build(), JdkHttpResponse.BodyHandlers.ofInputStream())
            }
            val charset = response.headers().firstValue("content-type").orElse("")
                .substringAfter("charset=", "").trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            return HttpResponse(
                code = response.statusCode(),
                finalUrl = response.uri().toString(),
                headers = response.headers().map().mapKeys { it.key.lowercase() },
                charset = charset,
                body = response.body(),
            )
        }
    }
}
