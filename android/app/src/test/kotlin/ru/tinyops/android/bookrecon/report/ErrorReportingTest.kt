package ru.tinyops.android.bookrecon.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Settings
import java.io.IOException

class ErrorReportingTest {
    private class FakeSdk : ReportingSdk {
        var starts = 0
        var stops = 0
        var running = false
        val captured = mutableListOf<Throwable>()

        override fun start() {
            starts++
            running = true
        }

        override fun stop() {
            stops++
            running = false
        }

        override fun capture(error: Throwable) {
            check(running) { "capture while stopped" }
            captured += error
        }
    }

    private val sdk = FakeSdk()

    @Test
    fun offByDefault() {
        val reporting = ErrorReporting(dsn = "https://key@sentry.example/1", sdk = sdk)

        reporting.report(IllegalStateException("boom"))

        assertFalse(reporting.enabled)
        assertEquals(0, sdk.starts)
        assertTrue(sdk.captured.isEmpty())
    }

    @Test
    fun startsWhenTurnedOnAndStopsWhenTurnedOff() {
        val reporting = ErrorReporting(dsn = "https://key@sentry.example/1", sdk = sdk)
        val error = IllegalStateException("boom")

        reporting.apply(true)
        reporting.apply(true)
        reporting.report(error)
        reporting.apply(false)
        reporting.report(IllegalStateException("after"))

        assertEquals(1, sdk.starts)
        assertEquals(1, sdk.stops)
        assertEquals(listOf<Throwable>(error), sdk.captured)
    }

    @Test
    fun turningOffWhatIsOffDoesNothing() {
        ErrorReporting(dsn = "https://key@sentry.example/1", sdk = sdk).apply(false)

        assertEquals(0, sdk.stops)
    }

    @Test
    fun withoutDsnNothingStarts() {
        val reporting = ErrorReporting(dsn = " ", sdk = sdk)

        reporting.apply(true)
        reporting.report(IOException("boom"))

        assertFalse(reporting.enabled)
        assertEquals(0, sdk.starts)
    }

    @Test
    fun failingSdkLeavesReportingOff() {
        val failing = object : ReportingSdk {
            override fun start() = throw IllegalStateException("bad dsn")
            override fun stop() = Unit
            override fun capture(error: Throwable) = error("must not capture")
        }
        val logged = mutableListOf<String>()
        val reporting = ErrorReporting(dsn = "not a dsn", sdk = failing, log = { logged += it })

        reporting.apply(true)
        reporting.report(IOException("boom"))

        assertFalse(reporting.enabled)
        assertEquals(1, logged.size)
    }

    @Test
    fun followsTheSetting() = runTest {
        val settings = MutableStateFlow(Settings())
        val reporting = ErrorReporting(dsn = "https://key@sentry.example/1", sdk = sdk)
        val job = launch { reporting.follow(settings) }
        runCurrent()
        assertFalse(sdk.running)

        settings.value = settings.value.copy(sendErrorReports = true)
        runCurrent()
        assertTrue(sdk.running)

        settings.value = settings.value.copy(wifiOnly = true)
        runCurrent()
        assertEquals(1, sdk.starts)

        settings.value = settings.value.copy(sendErrorReports = false)
        runCurrent()
        assertFalse(sdk.running)
        job.cancel()
    }
}
