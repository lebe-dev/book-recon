package ru.tinyops.android.bookrecon.report

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.tinyops.android.bookrecon.core.domain.Settings

/** The error reporting SDK, replaced by a fake in tests. */
interface ReportingSdk {
    /** Starts the SDK: from now on crashes and ANRs are reported. */
    fun start()

    /** Stops the SDK; nothing is sent until the next [start]. */
    fun stop()

    /** Sends an error the app caught but did not expect. */
    fun capture(error: Throwable)
}

/**
 * Sends crashes and unexpected errors to Sentry, only while the user allows it
 * ([Settings.sendErrorReports], off by default). The SDK is not started at all while
 * reports are off, so nothing leaves the device. A build without a DSN never reports.
 */
class ErrorReporting(
    dsn: String,
    private val sdk: ReportingSdk,
    private val log: (String) -> Unit = {},
) {
    private val configured = dsn.isNotBlank()

    @Volatile
    var enabled: Boolean = false
        private set

    /** Turns reporting on or off; repeated calls with the same value do nothing. */
    @Synchronized
    fun apply(enable: Boolean) {
        if (!configured || enable == enabled) return
        if (!enable) {
            enabled = false
            sdk.stop()
            log("Error reports are turned off")
            return
        }
        try {
            sdk.start()
        } catch (e: RuntimeException) {
            log("Error reports cannot start: ${e.message}")
            return
        }
        enabled = true
        log("Error reports are turned on")
    }

    /** Follows the user's choice until cancelled. */
    suspend fun follow(settings: Flow<Settings>) {
        settings.map { it.sendErrorReports }.distinctUntilChanged().collect(::apply)
    }

    /** Reports an unexpected error the app caught, if reports are on. */
    fun report(error: Throwable) {
        if (!enabled) return
        sdk.capture(error)
    }
}
