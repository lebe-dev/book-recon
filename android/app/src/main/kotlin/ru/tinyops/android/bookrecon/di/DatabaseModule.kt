package ru.tinyops.android.bookrecon.di

import kotlinx.coroutines.Dispatchers
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.storage.db.DownloadDatabase
import ru.tinyops.android.bookrecon.storage.db.RoomDownloadRepository

class DatabaseModule(private val app: AppContainer) {
    val database: DownloadDatabase by lazy { DownloadDatabase.open(app.context) }

    val downloadRepository: DownloadRepository by lazy {
        RoomDownloadRepository(
            dao = database.downloadDao(),
            storage = app.settings.fileStorage,
            clock = Clock.System,
            io = Dispatchers.IO,
        )
    }
}
