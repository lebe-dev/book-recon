package ru.tinyops.android.bookrecon.di

import kotlinx.coroutines.flow.first
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.flibusta.FlibustaSourceImpl
import ru.tinyops.android.bookrecon.core.port.FlibustaSource

class FlibustaModule(private val app: AppContainer) {
    val flibustaSource: FlibustaSource by lazy {
        FlibustaSourceImpl(
            http = app.network.httpClient,
            baseUrl = { app.settings.settingsRepository.settings.first().flibustaEffectiveUrl },
            fallbackUrls = {
                // Only an automatic choice may fall back; a mirror typed by the user is the only one used.
                val settings = app.settings.settingsRepository.settings.first()
                if (settings.flibustaBaseUrl.isEmpty()) Settings.FLIBUSTA_MIRRORS else emptyList()
            },
        )
    }
}
