package ru.tinyops.android.bookrecon.di

import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.core.route.FlibustaRouteCheck
import ru.tinyops.android.bookrecon.core.route.FlibustaRouteWatch
import ru.tinyops.android.bookrecon.diagnostics.OkHttpAddressProber

/** The background check of the routes to Flibusta: at start when none is known, and on trouble. */
class RoutesModule(private val app: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** No cookies, no Cloudflare solving, no tunnel: only whether an address answers. */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val watch: FlibustaRouteWatch by lazy {
        val log: (String) -> Unit = { app.log.appLog.info(TAG, it) }
        FlibustaRouteWatch(
            check = FlibustaRouteCheck(OkHttpAddressProber(probeClient), app.network.routeMemory, log),
            memory = app.network.routeMemory,
            settings = app.settings.settingsRepository,
            scope = scope,
            ready = { app.network.awaitRoutes() },
            log = log,
        )
    }

    /** Checks the routes in the background unless they are known. */
    fun start() {
        watch.start()
    }

    /** Flibusta could not be reached: checks the routes again, unless a check ran lately. */
    fun reportTrouble() {
        watch.reportTrouble()
    }

    private companion object {
        const val TAG = "RouteCheck"
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 25L
    }
}
