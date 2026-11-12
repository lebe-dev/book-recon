package ru.tinyops.android.bookrecon.log

import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.port.Clock

class LoggingInterceptorTest {
    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()
    private val executor = Executors.newSingleThreadExecutor()

    @AfterEach
    fun tearDown() {
        server.close()
        executor.shutdownNow()
    }

    private fun logAndClient(): Pair<AppLog, OkHttpClient> {
        val log = AppLog(dir, Clock.System, ZoneOffset.UTC, executor = executor)
        return log to OkHttpClient.Builder().addInterceptor(LoggingInterceptor(log)).build()
    }

    @Test
    fun successfulRequestIsInfoWithoutQueryString() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        server.start()
        val (log, client) = logAndClient()

        client.newCall(Request.Builder().url(server.url("/viewtopic.php?t=1&passkey=SECRET")).build()).execute().close()

        val entry = log.entries.value.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("Http", entry.tag)
        assertTrue(entry.message.startsWith("GET ${server.hostName}/viewtopic.php -> 200 "), entry.message)
        assertFalse(log.text().contains("SECRET"))
    }

    @Test
    fun serverErrorIsAWarning() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.start()
        val (log, client) = logAndClient()

        client.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()

        assertEquals(LogLevel.WARN, log.entries.value.single().level)
    }

    @Test
    fun failedRequestIsLoggedAndRethrown() {
        server.start()
        val url = server.url("/a?token=SECRET")
        server.close()
        val (log, client) = logAndClient()

        val failed = runCatching { client.newCall(Request.Builder().url(url).build()).execute() }

        assertTrue(failed.isFailure)
        val entry = log.entries.value.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertTrue(entry.message.startsWith("GET ${server.hostName}/a failed: "), entry.message)
        assertFalse(log.text().contains("SECRET"))
    }
}
