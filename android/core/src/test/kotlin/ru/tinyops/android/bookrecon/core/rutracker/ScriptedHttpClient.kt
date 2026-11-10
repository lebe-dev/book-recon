package ru.tinyops.android.bookrecon.core.rutracker

import kotlinx.coroutines.yield
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import java.nio.charset.Charset

/**
 * An [HttpClient] whose answers come from [route], so a test can play a sequence of pages and
 * redirects. Every call yields first: concurrent callers interleave as they would on a real
 * network.
 */
internal class ScriptedHttpClient(var route: (Call) -> Reply = { Reply.notFound(it.url) }) : HttpClient {

    data class Call(
        val method: String,
        val url: String,
        val form: List<Pair<String, String>> = emptyList(),
        val charset: Charset? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    class Reply(
        val code: Int = 200,
        val body: ByteArray = ByteArray(0),
        val finalUrl: String? = null,
        val headers: Map<String, String> = mapOf("content-type" to "text/html; charset=Windows-1251"),
        val charset: Charset? = RuTrackerParser.CHARSET,
    ) {
        companion object {
            fun page(fixture: String, finalUrl: String? = null) = Reply(body = Fixtures.bytes(fixture), finalUrl = finalUrl)

            fun notFound(url: String) = Reply(code = 404, finalUrl = url)
        }
    }

    private val recorded = mutableListOf<Call>()

    val calls: List<Call>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        answer(Call("GET", url, headers = headers))

    override suspend fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        headers: Map<String, String>,
        charset: Charset,
    ): HttpResponse = answer(Call("POST", url, form, charset, headers))

    private suspend fun answer(call: Call): HttpResponse {
        synchronized(recorded) { recorded += call }
        yield()
        val reply = route(call)
        return HttpResponse(
            code = reply.code,
            finalUrl = reply.finalUrl ?: call.url,
            headers = reply.headers.entries.associate { (name, value) -> name.lowercase() to listOf(value) },
            charset = reply.charset,
            body = reply.body.inputStream(),
        )
    }
}
