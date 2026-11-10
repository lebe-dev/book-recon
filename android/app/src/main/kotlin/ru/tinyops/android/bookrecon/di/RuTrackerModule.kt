package ru.tinyops.android.bookrecon.di

import android.webkit.CookieManager
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.rutracker.RuTrackerClientImpl
import ru.tinyops.android.bookrecon.server.RoutingRuTrackerClient

class RuTrackerModule(private val app: AppContainer) {
    /** RuTracker with the credentials from settings, straight to the site (or the tunnel). */
    private val directClient: RuTrackerClient by lazy {
        RuTrackerClientImpl(
            http = app.network.httpClient,
            settings = app.settings.settingsRepository,
            // The WebView cookie store is the one the HTTP client shares, so the session set
            // by a login, in code or by hand in a WebView, is visible here.
            cookies = { url -> CookieManager.getInstance().getCookie(url) },
        )
    }

    /** The server's RuTracker account while the server is connected, settings otherwise. */
    val ruTrackerClient: RuTrackerClient by lazy {
        RoutingRuTrackerClient(directClient, app.server.ruTrackerClient) { app.server.connection.route.value }
    }
}
