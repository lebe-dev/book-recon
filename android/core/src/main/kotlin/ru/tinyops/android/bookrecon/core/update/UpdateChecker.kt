package ru.tinyops.android.bookrecon.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.HttpClient

/**
 * The APK file of a release.
 *
 * @property size length in bytes, as GitHub reports it.
 * @property sha256 lowercase hex SHA-256 of the file, null when GitHub gave no digest.
 */
data class ApkAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/**
 * A published release with an APK.
 *
 * @property notes release notes as plain text: Markdown markers removed, bullets as `•`.
 * @property pageUrl the release page on GitHub.
 */
data class Release(val version: AppVersion, val notes: String, val pageUrl: String, val apk: ApkAsset)

/** Result of asking for the latest release. */
sealed interface UpdateCheck {
    /** Nothing newer to install; [latest] is the newest published version. */
    data class UpToDate(val latest: AppVersion) : UpdateCheck

    data class Available(val release: Release) : UpdateCheck
}

/**
 * Asks GitHub Releases of [repository] (`owner/name`) for the latest release and compares it
 * with [current]. Drafts and pre-releases are never "latest" on GitHub, so they are not offered.
 * A newer release without an APK is treated as up to date: there is nothing to install yet.
 *
 * Transport failures come from [http] as [AppError.Network] or [AppError.Timeout]; no
 * release at all is [AppError.NotFound], a rate limit or a server error [AppError.ServiceDown],
 * an answer that is not a release [AppError.ParseError].
 */
class UpdateChecker(
    private val http: HttpClient,
    private val current: AppVersion,
    private val repository: String,
) {
    suspend fun check(): UpdateCheck {
        val release = parse(fetchLatest())
        val version = AppVersion.parse(release.tagName)
            ?: throw AppError.ParseError("release tag ${release.tagName} is not a version")
        val apk = pickApk(release.assets)
        if (version <= current || apk == null) return UpdateCheck.UpToDate(version)
        return UpdateCheck.Available(Release(version, plainNotes(release.body.orEmpty()), release.htmlUrl, apk))
    }

    private suspend fun fetchLatest(): String {
        val url = "https://api.github.com/repos/$repository/releases/latest"
        return http.get(url, mapOf("Accept" to "application/vnd.github+json")).use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> throw AppError.NotFound("repository $repository has no releases")
                !response.isSuccessful -> throw AppError.ServiceDown("github answered ${response.code}")
                else -> response.text()
            }
        }
    }

    private fun parse(text: String): GitHubRelease = try {
        json.decodeFromString<GitHubRelease>(text)
    } catch (e: SerializationException) {
        throw AppError.ParseError("github answer is not a release", e)
    } catch (e: IllegalArgumentException) {
        throw AppError.ParseError("github answer is not a release", e)
    }

    private fun pickApk(assets: List<GitHubAsset>): ApkAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val asset = apks.firstOrNull { it.name.startsWith(APK_PREFIX) } ?: apks.firstOrNull() ?: return null
        val sha256 = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase()
        return ApkAsset(asset.name, asset.url, asset.size, sha256)
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val size: Long = 0,
        val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val APK_PREFIX = "book-recon-"
        const val SHA256_PREFIX = "sha256:"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Release notes for a plain text view: the `**Full Changelog**` link is dropped, bold and code
 * markers are removed, `-` bullets become `•` and wrapped continuation lines join their bullet.
 */
internal fun plainNotes(markdown: String): String {
    val lines = mutableListOf<String>()
    for (raw in markdown.lines()) {
        if (raw.trim().startsWith("**Full Changelog**")) continue
        val line = raw.replace("**", "").replace("`", "").trimEnd()
        val bullet = BULLET.matchEntire(line)
        when {
            line.isBlank() -> if (lines.lastOrNull()?.isNotEmpty() == true) lines += ""
            bullet != null -> lines += "• " + bullet.groupValues[1]
            line.first().isWhitespace() && lines.lastOrNull()?.isNotEmpty() == true ->
                lines[lines.lastIndex] = lines.last() + " " + line.trim()
            else -> lines += line.trim()
        }
    }
    return lines.joinToString("\n").trim()
}

private val BULLET = Regex("""\s*[-*]\s+(.*)""")
