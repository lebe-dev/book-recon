package ru.tinyops.android.bookrecon.di

import kotlinx.coroutines.flow.first
import ru.tinyops.android.bookrecon.core.flibusta.FlibustaSourceImpl
import ru.tinyops.android.bookrecon.core.port.FlibustaSource

class FlibustaModule(private val app: AppContainer) {
    val flibustaSource: FlibustaSource by lazy {
        FlibustaSourceImpl(
            http = app.network.httpClient,
            baseUrl = { app.settings.settingsRepository.settings.first().flibustaEffectiveUrl },
            fallbackUrls = { app.settings.settingsRepository.settings.first().flibustaFallbackUrls },
            pageTimeout = { app.settings.settingsRepository.settings.first().flibustaPageDeadline },
            mirrorMemory = app.network.routeMemory,
            onTrouble = { app.routes.reportTrouble() },
        )
    }
}
