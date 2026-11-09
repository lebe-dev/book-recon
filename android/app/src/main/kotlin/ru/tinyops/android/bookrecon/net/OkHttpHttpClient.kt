package ru.tinyops.android.bookrecon.net

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.Charset
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse

/**
 * [HttpClient] over OkHttp. The body is handed out as a stream straight from the socket and
 * never buffered whole: books reach tens of megabytes.
 */
class OkHttpHttpClient(private val client: OkHttpClient) : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute(Request.Builder().url(parse(url)).headers(headers.toHeaders()).build())

    override suspend fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        headers: Map<String, String>,
        charset: Charset,
    ): HttpResponse {
        val body = FormBody.Builder(charset)
            .apply { form.forEach { (name, value) -> add(name, value) } }
            .build()
        return execute(Request.Builder().url(parse(url)).headers(headers.toHeaders()).post(body).build())
    }

    private fun parse(url: String): HttpUrl =
        url.toHttpUrlOrNull() ?: throw AppError.Validation("invalid url: $url")

    private suspend fun execute(request: Request): HttpResponse {
        val call = client.newCall(request)
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw e.toAppError(call)
        }
        return response.toHttpResponse()
    }

    private fun IOException.toAppError(call: Call): AppError = when {
        this is CloudflareChallengeException -> AppError.CloudflareChallenge(url)
        this is InterruptedIOException -> AppError.Timeout("request to ${call.request().url.host} timed out", this)
        // The caller's coroutine is still running, so only the TLS handshake timer cancelled it.
        call.isCanceled() -> AppError.Timeout("tls handshake with ${call.request().url.host} timed out", this)
        else -> AppError.Network("request to ${call.request().url.host} failed: $message", this)
    }

    private fun Response.toHttpResponse(): HttpResponse = HttpResponse(
        code = code,
        finalUrl = request.url.toString(),
        headers = headers.toMultimap(),
        charset = body.contentType()?.charset(),
        body = body.byteStream(),
    )
}

/** Runs the call on the OkHttp dispatcher; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
