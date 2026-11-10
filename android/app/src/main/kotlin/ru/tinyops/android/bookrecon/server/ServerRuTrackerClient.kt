package ru.tinyops.android.bookrecon.server

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient

/**
 * RuTracker through the Book Recon server: the server searches with its own account (in
 * Jackett) and hands out `.torrent` files, so no credentials from settings are used. A topic
 * can be fetched only after the server found it in a search.
 *
 * @param client talks to the server directly, with timeouts that outlast a Cloudflare check
 *   on the server side.
 */
class ServerRuTrackerClient(
    private val client: OkHttpClient,
    private val route: () -> TunnelRoute?,
) : RuTrackerClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** Nothing to log in to: the server's account is used. */
    override suspend fun login() = Unit

    override suspend fun isLoggedIn(): Boolean = true

    override suspend fun needsCredentials(): Boolean = false

    override suspend fun search(query: String, kind: MediaKind, limit: Int): List<TorrentTopic> {
        val current = currentRoute()
        val url = OkHttpServerApi.url(current.baseUrl, "rutracker/search").newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("kind", kind.wireName())
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = call(current, Request.Builder().url(url).build()) { it.body.string() }
        val answer = try {
            json.decodeFromString<SearchAnswer>(body)
        } catch (e: SerializationException) {
            throw AppError.ParseError("server search answer is malformed", e)
        } catch (e: IllegalArgumentException) {
            throw AppError.ParseError("server search answer is malformed", e)
        }
        return answer.topics.map { TorrentTopic(it.topicId, it.title, it.size, it.seeders, it.peers, kind) }
    }

    override suspend fun fetchTorrent(topicId: String): ByteArray {
        val current = currentRoute()
        val url = OkHttpServerApi.url(current.baseUrl, "rutracker/topics").newBuilder()
            .addPathSegment(topicId)
            .addPathSegment("torrent")
            .build()
        val bytes = call(current, Request.Builder().url(url).build()) { it.body.bytes() }
        if (bytes.size > MAX_TORRENT_BYTES) throw AppError.TorrentTooLarge()
        return bytes
    }

    private fun currentRoute(): TunnelRoute =
        route() ?: throw AppError.ServiceDown("the server is not connected")

    private suspend fun <T> call(route: TunnelRoute, request: Request, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val authorized = request.newBuilder().header("Authorization", "Bearer ${route.token}").build()
            try {
                client.newCall(authorized).execute().use { response ->
                    if (response.isSuccessful) return@use read(response)
                    throw errorFor(response.code, reasonOf(response))
                }
            } catch (e: IOException) {
                throw AppError.Network("the server is unreachable: ${e.message}", e)
            }
        }

    private fun reasonOf(response: Response): String = try {
        json.decodeFromString<ErrorAnswer>(response.body.string()).error
    } catch (_: Exception) {
        ""
    }

    private fun errorFor(code: Int, reason: String): AppError = when {
        reason == "topic_unknown" -> AppError.NotFound("the server has not seen this topic in a recent search")
        reason == "rutracker_disabled" -> AppError.ServiceDown("the server has no RuTracker search")
        code == 401 || code == 403 -> AppError.ServiceDown("the server refused access: HTTP $code $reason")
        else -> AppError.ServiceDown("server RuTracker request failed: HTTP $code $reason")
    }

    private fun MediaKind.wireName(): String = when (this) {
        MediaKind.BOOK -> "book"
        MediaKind.AUDIO -> "audio"
    }

    @Serializable
    private data class SearchAnswer(val topics: List<TopicAnswer> = emptyList())

    @Serializable
    private data class TopicAnswer(
        val topicId: String,
        val title: String,
        val size: Long = 0,
        val seeders: Int = 0,
        val peers: Int = 0,
    )

    @Serializable
    private data class ErrorAnswer(val error: String = "")

    private companion object {
        const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
    }
}

/**
 * Uses [server] while the tunnel to the Book Recon server is on and [local] (credentials from
 * settings) otherwise. Read on every call, so connecting or disconnecting applies at once.
 */
class RoutingRuTrackerClient(
    private val local: RuTrackerClient,
    private val server: RuTrackerClient,
    private val route: () -> TunnelRoute?,
) : RuTrackerClient {
    private val current: RuTrackerClient get() = if (route() != null) server else local

    override suspend fun login() = current.login()

    override suspend fun isLoggedIn(): Boolean = current.isLoggedIn()

    override suspend fun needsCredentials(): Boolean = current.needsCredentials()

    override suspend fun search(query: String, kind: MediaKind, limit: Int): List<TorrentTopic> =
        current.search(query, kind, limit)

    override suspend fun fetchTorrent(topicId: String): ByteArray = current.fetchTorrent(topicId)
}
