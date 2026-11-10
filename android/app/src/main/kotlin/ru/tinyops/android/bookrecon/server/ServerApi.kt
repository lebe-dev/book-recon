package ru.tinyops.android.bookrecon.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Access of this installation as the server sees it. */
enum class ClientStatus {
    @SerialName("unlinked")
    UNLINKED,

    @SerialName("pending")
    PENDING,

    @SerialName("approved")
    APPROVED,

    @SerialName("denied")
    DENIED,
}

@Serializable
data class StatusResponse(
    val status: ClientStatus,
    val telegramUsername: String? = null,
    val tunnelHosts: List<String> = emptyList(),
)

@Serializable
data class LinkResponse(val url: String, val expiresAt: String = "")

/** Why a call to the server failed. */
sealed class ServerError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The token is unknown: the server forgot this installation. */
    class Unauthorized : ServerError("server does not know this installation")

    /** The URL answers, but not as a Book Recon server with the app API enabled. */
    class NotBookRecon(detail: String) : ServerError("not a book recon server: $detail")

    class Http(val code: Int) : ServerError("server answered with HTTP $code")

    class Network(cause: IOException) : ServerError("server is unreachable: ${cause.message}", cause)
}

/** The Book Recon server API used by the app. Every call throws [ServerError]. */
interface ServerApi {
    suspend fun ping(baseUrl: String)

    /** Registers this installation and returns its token. */
    suspend fun register(baseUrl: String, deviceName: String): String

    suspend fun status(baseUrl: String, token: String): StatusResponse

    /** Issues a one-time Telegram link for this installation. */
    suspend fun link(baseUrl: String, token: String): LinkResponse

    suspend fun delete(baseUrl: String, token: String)
}

/** [ServerApi] over [client]; the client must not route through the tunnel. */
class OkHttpServerApi(private val client: OkHttpClient) : ServerApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun ping(baseUrl: String) {
        val body = call(get(url(baseUrl, "ping")))
        if (!body.contains("\"book-recon\"")) throw ServerError.NotBookRecon("ping answer has no service name")
    }

    override suspend fun register(baseUrl: String, deviceName: String): String {
        val payload = json.encodeToString(RegisterRequest.serializer(), RegisterRequest(deviceName))
        val request = Request.Builder().url(url(baseUrl, "clients")).post(payload.toRequestBody(JSON)).build()
        return decode<RegisterResponse>(call(request)).token
    }

    override suspend fun status(baseUrl: String, token: String): StatusResponse =
        decode(call(get(url(baseUrl, "clients/me"), token)))

    override suspend fun link(baseUrl: String, token: String): LinkResponse {
        val request = Request.Builder()
            .url(url(baseUrl, "clients/me/link"))
            .header(AUTHORIZATION, "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return decode(call(request))
    }

    override suspend fun delete(baseUrl: String, token: String) {
        call(Request.Builder().url(url(baseUrl, "clients/me")).header(AUTHORIZATION, "Bearer $token").delete().build())
    }

    private fun get(url: HttpUrl, token: String? = null): Request {
        val builder = Request.Builder().url(url).get()
        if (token != null) builder.header(AUTHORIZATION, "Bearer $token")
        return builder.build()
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.isSuccessful -> body
                    response.code == 401 -> throw ServerError.Unauthorized()
                    // No such route: an older server, the app API is off, or another site.
                    response.code == 404 || response.code == 405 -> throw ServerError.NotBookRecon("HTTP ${response.code}")
                    else -> throw ServerError.Http(response.code)
                }
            }
        } catch (e: IOException) {
            throw ServerError.Network(e)
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw ServerError.NotBookRecon("unexpected answer: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ServerError.NotBookRecon("unexpected answer: ${e.message}")
    }

    @Serializable
    private data class RegisterRequest(val deviceName: String)

    @Serializable
    private data class RegisterResponse(val token: String)

    companion object {
        private const val AUTHORIZATION = "Authorization"
        private val JSON = "application/json".toMediaType()

        /** `<base>/api/v1/<path>`; throws [ServerError.NotBookRecon] for a malformed base. */
        fun url(baseUrl: String, path: String): HttpUrl {
            val base = baseUrl.toHttpUrlOrNull() ?: throw ServerError.NotBookRecon("malformed URL")
            return base.newBuilder().addPathSegments("api/v1/$path").build()
        }
    }
}
