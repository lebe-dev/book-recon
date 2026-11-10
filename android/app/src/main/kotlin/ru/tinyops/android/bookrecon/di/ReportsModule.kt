package ru.tinyops.android.bookrecon.di

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.tinyops.android.bookrecon.BuildConfig
import ru.tinyops.android.bookrecon.report.ErrorReporting
import ru.tinyops.android.bookrecon.report.SentryReportingSdk

class ReportsModule(private val app: AppContainer) {
    val errorReporting: ErrorReporting by lazy {
        ErrorReporting(
            dsn = BuildConfig.SENTRY_DSN,
            sdk = SentryReportingSdk(app.context, BuildConfig.SENTRY_DSN),
            log = { Log.i(TAG, it) },
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Called once from Application.onCreate. The stored choice is read before returning, so a
     * crash right after start is reported too; later changes on the settings screen apply as
     * they are saved.
     */
    fun start() {
        if (BuildConfig.SENTRY_DSN.isBlank()) return
        val settings = app.settings.settingsRepository.settings
        val enabled = runBlocking { settings.first().sendErrorReports }
        errorReporting.apply(enabled)
        scope.launch { errorReporting.follow(settings) }
    }

    private companion object {
        const val TAG = "ErrorReports"
    }
}
