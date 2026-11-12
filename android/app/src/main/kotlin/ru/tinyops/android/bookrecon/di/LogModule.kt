package ru.tinyops.android.bookrecon.di

import android.util.Log
import java.io.File
import ru.tinyops.android.bookrecon.log.AppLog
import ru.tinyops.android.bookrecon.log.LogEntry
import ru.tinyops.android.bookrecon.log.LogLevel

/** The app log shown in Settings. */
class LogModule(private val app: AppContainer) {
    val appLog: AppLog by lazy {
        AppLog(dir = File(app.context.filesDir, "logs"), mirror = ::toLogcat)
    }

    /** Called first from Application.onCreate. A crash is written to the log before the process dies. */
    fun start() {
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
