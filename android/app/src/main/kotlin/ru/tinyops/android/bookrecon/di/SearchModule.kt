package ru.tinyops.android.bookrecon.di

import ru.tinyops.android.bookrecon.core.usecase.search.SearchUseCase

class SearchModule(private val app: AppContainer) {
    val searchUseCase: SearchUseCase by lazy {
        SearchUseCase(
            flibusta = app.flibusta.flibustaSource,
            ruTracker = app.ruTracker.ruTrackerClient,
            settings = app.settings.settingsRepository,
            downloads = app.database.downloadRepository,
        )
    }
}
