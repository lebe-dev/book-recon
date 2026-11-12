package ru.tinyops.android.bookrecon.di

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionTest
import ru.tinyops.android.bookrecon.diagnostics.ConnectionCheck
import ru.tinyops.android.bookrecon.diagnostics.OkHttpConnectivityProbe
import ru.tinyops.android.bookrecon.server.TunnelProxy

/** The connection test of the Server and Diagnostics settings. */
class DiagnosticsModule(private val app: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** No cookies, no Cloudflare solving, no tunnel: only whether the site answers. */
    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Everything through the local tunnel proxy, whatever the user chose for the source. */
    private fun throughServer(): OkHttpClient? {
        if (app.server.connection.route.value == null) return null
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(TunnelProxy.LOOPBACK, app.server.tunnelPort))
        return directClient.newBuilder().proxy(proxy).build()
    }

    val connectionCheck: ConnectionCheck by lazy {
        ConnectionCheck(
            test = ConnectionTest(OkHttpConnectivityProbe(directClient, ::throughServer)),
            settings = app.settings.settingsRepository,
            serverHosts = { app.server.connection.route.value?.hosts },
            scope = scope,
            log = { app.log.appLog.info(TAG, it) },
        )
    }

    private companion object {
        const val TAG = "ConnectionTest"
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 25L
    }
}
