package ru.tinyops.android.bookrecon.di

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.tinyops.android.bookrecon.log.AppLog
import ru.tinyops.android.bookrecon.log.LogEntry
import ru.tinyops.android.bookrecon.log.LogLevel

/** The app log shown in Settings, and the log switches kept in step with the settings. */
class LogModule(private val app: AppContainer) {
    val appLog: AppLog by lazy {
        AppLog(dir = File(app.context.filesDir, "logs"), mirror = ::toLogcat)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Called first from Application.onCreate. The stored switches are read before returning,
     * so the start of the app is logged at the chosen level; later changes apply as they are
     * saved. A crash is written to the log before the process dies.
     */
    fun start() {
        val settings = app.settings.settingsRepository.settings
        val stored = runBlocking { settings.first() }
        appLog.setLevels(stored.logInfo, stored.logDebug)
        scope.launch { settings.collect { appLog.setLevels(it.logInfo, it.logDebug) } }
        installCrashHook()
    }

    private fun installCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            appLog.error("Crash", "Uncaught exception in thread ${thread.name}", error)
            appLog.flush()
            previous?.uncaughtException(thread, error)
        }
    }

    private fun toLogcat(entry: LogEntry) {
        val priority = when (entry.level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, entry.tag, entry.message)
    }
}
