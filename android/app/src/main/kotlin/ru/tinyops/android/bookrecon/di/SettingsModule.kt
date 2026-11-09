package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeFileStorage
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import java.io.File

class SettingsModule(private val app: AppContainer) {
    val settingsRepository: SettingsRepository by lazy {
        FakeSettingsRepository(
            Settings(
                audioRoot = "/storage/emulated/0/Audiobooks",
                booksRoot = "/storage/emulated/0/Books",
                rutrackerLogin = "reader",
            ),
        )
    }

    val fileStorage: FileStorage by lazy { FakeFileStorage(File(app.context.filesDir, "fake-storage")) }
}
