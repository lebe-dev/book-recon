package ru.tinyops.android.bookrecon.torrent

import android.util.Log

/** Log sink of the torrent engine; messages never contain announce URLs or passkeys. */
interface TorrentLogger {
    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)
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
