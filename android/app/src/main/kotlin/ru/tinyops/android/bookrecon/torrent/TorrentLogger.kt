package ru.tinyops.android.bookrecon.torrent

import android.util.Log
import ru.tinyops.android.bookrecon.log.AppLog

/** Log sink of the torrent engine; messages never contain announce URLs or passkeys. */
interface TorrentLogger {
    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    /** Details nobody needs unless something goes wrong; the message is built only when asked for. */
    fun debug(message: () -> String) = Unit
}

/** Writes to logcat under the `TorrentEngine` tag. */
object AndroidTorrentLogger : TorrentLogger {
    private const val TAG = "TorrentEngine"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }
}

/** Writes to the app log under the `TorrentEngine` tag. */
class AppLogTorrentLogger(private val log: AppLog) : TorrentLogger {
    override fun info(message: String) = log.info(TAG, message)

    override fun warn(message: String, error: Throwable?) = log.warn(TAG, message, error)

    override fun debug(message: () -> String) = log.debug(TAG, message)

    private companion object {
        const val TAG = "TorrentEngine"
    }
}
