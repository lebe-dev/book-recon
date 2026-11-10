package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.port.SearchHistory
import ru.tinyops.android.bookrecon.core.usecase.search.SearchUseCase
import ru.tinyops.android.bookrecon.storage.settings.DataStoreSearchHistory

class SearchModule(private val app: AppContainer) {
    val searchUseCase: SearchUseCase by lazy {
        SearchUseCase(
            flibusta = app.flibusta.flibustaSource,
            ruTracker = app.ruTracker.ruTrackerClient,
            settings = app.settings.settingsRepository,
            downloads = app.database.downloadRepository,
        )
    }

    val searchHistory: SearchHistory by lazy { DataStoreSearchHistory(app.settings.dataStore) }
}
