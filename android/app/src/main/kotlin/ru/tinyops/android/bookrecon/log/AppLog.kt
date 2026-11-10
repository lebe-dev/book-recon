package ru.tinyops.android.bookrecon.log

import java.io.File
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.tinyops.android.bookrecon.core.port.Clock

/**
 * The app log the user can read in Settings. Lines go to `app.log` in [dir] (rotated into
 * `app.log.1` at [maxFileBytes], so the log never grows beyond about twice that) and the
 * newest [maxEntries] stay in memory in [entries].
 *
 * Two switches decide what is recorded, see [setLevels]: INFO (info, warnings, errors) and
 * DEBUG. Messages of a switched-off level are never built. Files are written on [executor],
 * one write at a time and in order, so callers on any thread, the main one included, never
 * wait for the disk.
 *
 * Messages must not contain secrets: no passwords, tokens, cookies, announce URLs or query
 * strings.
 *
 * @param mirror receives every recorded entry, used to copy lines to logcat.
 */
class AppLog(
    private val dir: File,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val mirror: (LogEntry) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "app-log").apply { isDaemon = true }
    },
) {
    private val current = File(dir, FILE_NAME)
    private val previous = File(dir, "$FILE_NAME.1")
    private val lock = Any()
    private val state = MutableStateFlow(loadEntries())

    @Volatile
    private var infoOn = true

    @Volatile
    private var debugOn = false

    /** The newest entries, oldest first. */
    val entries: StateFlow<List<LogEntry>> get() = state

    fun setLevels(info: Boolean, debug: Boolean) {
        infoOn = info
        debugOn = debug
    }

    fun isEnabled(level: LogLevel): Boolean = if (level == LogLevel.DEBUG) debugOn else infoOn

    fun debug(tag: String, message: () -> String) {
        if (debugOn) record(LogLevel.DEBUG, tag, message())
    }

    fun info(tag: String, message: String) {
        if (infoOn) record(LogLevel.INFO, tag, message)
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        if (infoOn) record(LogLevel.WARN, tag, message.withError(error))
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        if (infoOn) record(LogLevel.ERROR, tag, message.withError(error))
    }

    /** The whole stored log, oldest line first, one entry per line. Waits for pending writes. */
    fun text(): String = executor.submit<String> {
        buildString {
            for (file in listOf(previous, current)) if (file.exists()) append(file.readText())
        }
    }.get()

    /** Empties [entries] at once and deletes the stored log in the background. */
    fun clear() {
        synchronized(lock) {
            state.value = emptyList()
            executor.execute {
                previous.delete()
                current.delete()
            }
        }
    }

    /** Returns once every line recorded so far is on disk. */
    fun flush() {
        executor.submit {}.get()
    }

    private fun record(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(clock.now(), level, tag, message)
        synchronized(lock) {
            state.value = (state.value + entry).takeLast(maxEntries)
            val line = entry.format(zone) + "\n"
            executor.execute { append(line) }
        }
        mirror(entry)
    }

    private fun append(line: String) {
        try {
            dir.mkdirs()
            if (current.length() >= maxFileBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(line)
        } catch (_: java.io.IOException) {
            // A full disk must not break the app; the line stays in memory only.
        }
    }

    private fun loadEntries(): List<LogEntry> {
        val lines = try {
            listOf(previous, current).filter { it.exists() }.flatMap { it.readLines() }
        } catch (_: java.io.IOException) {
            emptyList()
        }
        return lines.mapNotNull { parseLogEntry(it, zone) }.takeLast(maxEntries)
    }

    private fun String.withError(error: Throwable?): String {
        if (error == null) return this
        val detail = error.message?.let { ": $it" }.orEmpty()
        return "$this: ${error.javaClass.simpleName}$detail"
    }

    companion object {
        const val FILE_NAME = "app.log"
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024
        const val DEFAULT_MAX_ENTRIES = 1000
    }
}
