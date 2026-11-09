package ru.tinyops.android.bookrecon.di

import android.webkit.CookieManager
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.rutracker.RuTrackerClientImpl

class RuTrackerModule(private val app: AppContainer) {
    val ruTrackerClient: RuTrackerClient by lazy {
        RuTrackerClientImpl(
            http = app.network.httpClient,
            settings = app.settings.settingsRepository,
            // The WebView cookie store is the one the HTTP client shares, so the session set
            // by a login, in code or by hand in a WebView, is visible here.
            cookies = { url -> CookieManager.getInstance().getCookie(url) },
        )
    }
}
