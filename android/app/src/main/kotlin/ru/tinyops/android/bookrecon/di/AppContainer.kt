package ru.tinyops.android.bookrecon.di

import android.content.Context
import ru.tinyops.android.bookrecon.BookReconApp

/**
 * Composition root of the app. Every module receives the container and reaches its
 * dependencies lazily through it, so a module can switch from a fake to a real
 * implementation without touching this file.
 */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext

    val network: NetworkModule by lazy { NetworkModule(this) }
    val settings: SettingsModule by lazy { SettingsModule(this) }
    val database: DatabaseModule by lazy { DatabaseModule(this) }
    val flibusta: FlibustaModule by lazy { FlibustaModule(this) }
    val ruTracker: RuTrackerModule by lazy { RuTrackerModule(this) }
    val torrent: TorrentModule by lazy { TorrentModule(this) }
    val download: DownloadModule by lazy { DownloadModule(this) }
    val search: SearchModule by lazy { SearchModule(this) }
    val log: LogModule by lazy { LogModule(this) }
    val reports: ReportsModule by lazy { ReportsModule(this) }
    val server: ServerModule by lazy { ServerModule(this) }
    val diagnostics: DiagnosticsModule by lazy { DiagnosticsModule(this) }
    val updates: UpdateModule by lazy { UpdateModule(this) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as BookReconApp).container
