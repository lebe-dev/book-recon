package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.port.TorrentEngine
import ru.tinyops.android.bookrecon.torrent.LibTorrentEngine
import java.io.File

class TorrentModule(private val app: AppContainer) {
    // Job state and resume data live in private storage that is excluded from backups.
    val torrentEngine: TorrentEngine by lazy { LibTorrentEngine(File(app.context.noBackupFilesDir, "torrents")) }
}
