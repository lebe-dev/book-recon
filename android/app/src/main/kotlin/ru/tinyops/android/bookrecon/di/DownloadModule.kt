package ru.tinyops.android.bookrecon.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadQueue
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import kotlin.time.Duration.Companion.hours

class DownloadModule(private val app: AppContainer) {
    /** Lives as long as the process; downloads must outlive any screen. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val downloadQueue: DownloadQueue by lazy {
        FakeDownloadQueue(
            repository = app.database.downloadRepository,
            scope = scope,
            maxConcurrent = { app.settings.settingsRepository.settings.first().maxConcurrent },
            seedFor = { app.settings.settingsRepository.settings.first().seedHours.hours },
        )
    }
}
