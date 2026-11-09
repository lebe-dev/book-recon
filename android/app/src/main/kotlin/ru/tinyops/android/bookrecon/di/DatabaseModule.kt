package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.core.port.DownloadRepository

@Suppress("unused")
class DatabaseModule(private val app: AppContainer) {
    val downloadRepository: DownloadRepository by lazy {
        FakeDownloadRepository(FakeData.library(Clock.System.now()), FakeData::sampleFileExists)
    }
}
