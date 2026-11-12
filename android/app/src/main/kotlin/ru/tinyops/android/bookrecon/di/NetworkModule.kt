package ru.tinyops.android.bookrecon.di

import android.webkit.WebSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.route.RouteMemory
import ru.tinyops.android.bookrecon.log.LoggingInterceptor
import ru.tinyops.android.bookrecon.net.ChallengeSolver
import ru.tinyops.android.bookrecon.net.CookieStore
import ru.tinyops.android.bookrecon.net.OkHttpHttpClient
import ru.tinyops.android.bookrecon.net.SharedCookieJar
import ru.tinyops.android.bookrecon.net.WebViewChallengeSolver
import ru.tinyops.android.bookrecon.net.WebkitCookieStore
import ru.tinyops.android.bookrecon.net.buildOkHttpClient
import ru.tinyops.android.bookrecon.storage.settings.DataStoreRouteStore
import ru.tinyops.android.bookrecon.storage.settings.StoredRoutes

class NetworkModule(private val app: AppContainer) {
    /** The WebView cookie store; OkHttp reads and writes it too. */
    val cookieStore: CookieStore by lazy { WebkitCookieStore() }

    /** User-Agent of WebView: `cf_clearance` passed there is valid only with the same one. */
    val userAgent: String by lazy { WebSettings.getDefaultUserAgent(app.context) }

    val challengeSolver: ChallengeSolver by lazy { WebViewChallengeSolver(app.context, cookieStore, log = app.log.appLog) }

    /** The single OkHttp client of the app. */
    val okHttpClient: OkHttpClient by lazy {
        buildOkHttpClient(
            SharedCookieJar(cookieStore),
            challengeSolver,
            userAgent = { userAgent },
            proxySelector = app.server.proxySelector,
            logging = LoggingInterceptor(app.log.appLog),
            readTimeoutFor = ::userReadTimeout,
            routes = routeMemory,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val storedRoutes by lazy { StoredRoutes(DataStoreRouteStore(app.settings.dataStore), scope) }

    /** The mirrors and addresses that worked, kept across restarts. */
    val routeMemory: RouteMemory get() = storedRoutes.memory

    /** Returns once the stored routes are read. */
    suspend fun awaitRoutes() = storedRoutes.awaitRestored()

    /**
     * The read timeout the user chose for [host]: every Flibusta mirror a search may reach.
     * Runs on an OkHttp worker thread, where waiting for the (cached) settings is fine.
     */
    private fun userReadTimeout(host: String): Duration? {
        val settings = runBlocking { app.settings.settingsRepository.settings.first() }
        if (!settings.isFlibustaHost(host)) return null
        return settings.flibustaTimeoutSeconds.seconds
    }

    val httpClient: HttpClient by lazy { OkHttpHttpClient(okHttpClient) }
}
