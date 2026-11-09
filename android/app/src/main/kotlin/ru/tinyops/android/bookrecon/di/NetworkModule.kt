package ru.tinyops.android.bookrecon.di

import android.webkit.WebSettings
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.net.ChallengeSolver
import ru.tinyops.android.bookrecon.net.CookieStore
import ru.tinyops.android.bookrecon.net.OkHttpHttpClient
import ru.tinyops.android.bookrecon.net.SharedCookieJar
import ru.tinyops.android.bookrecon.net.WebViewChallengeSolver
import ru.tinyops.android.bookrecon.net.WebkitCookieStore
import ru.tinyops.android.bookrecon.net.buildOkHttpClient

class NetworkModule(private val app: AppContainer) {
    /** The WebView cookie store; OkHttp reads and writes it too. */
    val cookieStore: CookieStore by lazy { WebkitCookieStore() }

    /** User-Agent of WebView: `cf_clearance` passed there is valid only with the same one. */
    val userAgent: String by lazy { WebSettings.getDefaultUserAgent(app.context) }

    val challengeSolver: ChallengeSolver by lazy { WebViewChallengeSolver(app.context, cookieStore) }

    /** The single OkHttp client of the app. */
    val okHttpClient: OkHttpClient by lazy {
        buildOkHttpClient(SharedCookieJar(cookieStore), challengeSolver, userAgent = { userAgent })
    }

    val httpClient: HttpClient by lazy { OkHttpHttpClient(okHttpClient) }
}
