package ru.tinyops.android.bookrecon.core.update

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import java.nio.charset.Charset

class UpdateCheckerTest {
    private val http = JsonHttpClient()

    private fun checker(current: String = "0.9.1") = UpdateChecker(http, AppVersion.parse(current)!!, REPOSITORY)

    @Test
    fun asksGitHubForTheLatestRelease() = runTest {
        http.reply = release("v0.9.1")
        checker().check()
        assertEquals("https://api.github.com/repos/lebe-dev/book-recon/releases/latest", http.calls.single().first)
        assertEquals("application/vnd.github+json", http.calls.single().second["Accept"])
    }

    @Test
    fun aNewerReleaseIsAnUpdateWithItsApk() = runTest {
        http.reply = release("v0.10.0")
        val check = checker().check()
        val available = assertInstanceOf(UpdateCheck.Available::class.java, check)
        val release = available.release
        assertEquals(AppVersion(0, 10, 0), release.version)
        assertEquals("https://github.com/lebe-dev/book-recon/releases/tag/v0.10.0", release.pageUrl)
        assertEquals("book-recon-0.10.0.apk", release.apk.name)
        assertEquals("https://github.com/lebe-dev/book-recon/releases/download/v0.10.0/book-recon-0.10.0.apk", release.apk.url)
        assertEquals(37_087_202L, release.apk.size)
        assertEquals("ce12d2ab", release.apk.sha256?.take(8))
    }

    @Test
    fun theSameOrAnOlderReleaseIsUpToDate() = runTest {
        http.reply = release("v0.9.1")
        assertEquals(UpdateCheck.UpToDate(AppVersion(0, 9, 1)), checker().check())
        http.reply = release("v0.9.0")
        assertEquals(UpdateCheck.UpToDate(AppVersion(0, 9, 0)), checker().check())
    }

    @Test
    fun anApkNamedAfterTheAppWinsOverOtherApks() = runTest {
        http.reply = release(
            "v1.0.0",
            assets = asset("other-1.0.0.apk") + "," + asset("book-recon-1.0.0.apk") + "," + asset("notes.txt"),
        )
        val release = (checker().check() as UpdateCheck.Available).release
        assertEquals("book-recon-1.0.0.apk", release.apk.name)
    }

    @Test
    fun aDigestOtherThanSha256IsIgnored() = runTest {
        http.reply = release("v1.0.0", assets = asset("book-recon-1.0.0.apk", digest = "md5:abc"))
        assertNull((checker().check() as UpdateCheck.Available).release.apk.sha256)
    }

    @Test
    fun aNewerReleaseWithoutApkIsNotOffered() = runTest {
        http.reply = release("v1.0.0", assets = asset("book-recon-1.0.0.tar.gz"))
        assertEquals(UpdateCheck.UpToDate(AppVersion(1, 0, 0)), checker().check())
    }

    @Test
    fun noReleasesAtAllIsNotFound() = runTest {
        http.code = 404
        http.reply = """{"message":"Not Found"}"""
        assertThrows<AppError.NotFound> { checker().check() }
    }

    @Test
    fun rateLimitAndServerErrorsAreServiceDown() = runTest {
        http.code = 403
        http.reply = """{"message":"API rate limit exceeded"}"""
        assertThrows<AppError.ServiceDown> { checker().check() }
        http.code = 502
        assertThrows<AppError.ServiceDown> { checker().check() }
    }

    @Test
    fun unexpectedJsonIsAParseError() = runTest {
        http.reply = "<html>not json</html>"
        assertThrows<AppError.ParseError> { checker().check() }
        http.reply = """{"tag_name":"nightly","assets":[]}"""
        assertThrows<AppError.ParseError> { checker().check() }
    }

    @Test
    fun notesComeWithoutMarkdownAndChangelogLink() = runTest {
        http.reply = release(
            "v1.0.0",
            body = "- **Breaking:** pick `folders` again\\n  in settings\\n- Faster search\\n\\n" +
                "**Full Changelog**: https://github.com/lebe-dev/book-recon/compare/v0.9.1...v1.0.0",
        )
        val release = (checker().check() as UpdateCheck.Available).release
        assertEquals("• Breaking: pick folders again in settings\n• Faster search", release.notes)
    }

    private class JsonHttpClient : HttpClient {
        var code = 200
        var reply = ""
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls += url to headers
            return HttpResponse(code, url, emptyMap(), Charsets.UTF_8, reply.toByteArray().inputStream())
        }

        override suspend fun postForm(
            url: String,
            form: List<Pair<String, String>>,
            headers: Map<String, String>,
            charset: Charset,
        ): HttpResponse = error("not used")
    }

    private companion object {
        const val REPOSITORY = "lebe-dev/book-recon"
        const val DIGEST = "sha256:ce12d2abd4a93ceefdfeaa832292be6de965ebb4a54cc67494bc88b312b20187"

        fun asset(name: String, digest: String = DIGEST) = """
            {"name":"$name","size":37087202,"digest":"$digest",
             "browser_download_url":"https://github.com/lebe-dev/book-recon/releases/download/TAG/$name"}
        """.trimIndent()

        fun release(tag: String, assets: String? = null, body: String = "- Fixes") = """
            {"tag_name":"$tag","name":"$tag","draft":false,"prerelease":false,
             "html_url":"https://github.com/lebe-dev/book-recon/releases/tag/$tag",
             "body":"$body",
             "assets":[${(assets ?: asset("book-recon-${tag.removePrefix("v")}.apk")).replace("TAG", tag)}]}
        """.trimIndent()
    }
}
