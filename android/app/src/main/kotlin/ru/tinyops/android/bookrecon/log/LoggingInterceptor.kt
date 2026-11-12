package ru.tinyops.android.bookrecon.log

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Logs every request of the client: INFO for answers below 400 (shown by default, so a shared
 * log tells which requests worked), a warning for HTTP errors and failures.
 * Only the method, host and path are written; the query string can carry tokens and is never
 * logged, nor are headers and cookies.
 */
class LoggingInterceptor(private val log: AppLog) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = "${request.method} ${request.url.host}${request.url.encodedPath}"
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log.warn(TAG, "$target failed: ${e.javaClass.simpleName}: ${e.message} (${elapsedMs(started)} ms)")
            throw e
        }
        val line = "$target -> ${response.code} (${elapsedMs(started)} ms)"
        if (response.code >= HTTP_ERROR) log.warn(TAG, line) else log.info(TAG, line)
        return response
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MILLI

    private companion object {
        const val TAG = "Http"
        const val HTTP_ERROR = 400
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
