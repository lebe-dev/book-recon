package ru.tinyops.android.bookrecon.di

import android.os.Build
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.server.DataStoreServerStore
import ru.tinyops.android.bookrecon.server.OkHttpServerApi
import ru.tinyops.android.bookrecon.server.ServerApi
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.server.ServerConnection
import ru.tinyops.android.bookrecon.server.ServerRuTrackerClient
import ru.tinyops.android.bookrecon.server.ServerStore
import ru.tinyops.android.bookrecon.server.TunnelProxy
import ru.tinyops.android.bookrecon.server.TunnelProxySelector
import ru.tinyops.android.bookrecon.server.WebViewTunnel
import ru.tinyops.android.bookrecon.storage.settings.AesGcmCipher
import ru.tinyops.android.bookrecon.storage.settings.KeystoreKeys

/** Connection to the Book Recon server and the tunnel through it. */
class ServerModule(private val app: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log: (String) -> Unit = { app.log.appLog.info(TAG, it) }

    /** Talks to the server directly: it must never route through its own tunnel. */
    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Carries tunnels: no read timeout (a page may idle), pings keep NAT mappings alive. */
    private val tunnelClient: OkHttpClient by lazy {
        directClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val store: ServerStore by lazy {
        DataStoreServerStore(
            store = app.settings.dataStore,
            cipher = AesGcmCipher { KeystoreKeys.aesKey(TOKEN_KEY_ALIAS) },
            log = { app.log.appLog.warn(TAG, it) },
        )
    }

    val api: ServerApi by lazy { OkHttpServerApi(directClient) }

    /** The server searches RuTracker with its own account; a Cloudflare check there takes up to a minute. */
    val ruTrackerClient: RuTrackerClient by lazy {
        val client = directClient.newBuilder().readTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        ServerRuTrackerClient(client) { connection.route.value }
    }

    val connection: ServerConnection by lazy {
        ServerConnection(api, store, deviceName = "${Build.MANUFACTURER} ${Build.MODEL}", scope = scope, log = log)
    }

    private val proxy: TunnelProxy by lazy {
        TunnelProxy(tunnelClient, { connection.route.value }, log).also { it.start() }
    }

    val proxySelector: ProxySelector by lazy { TunnelProxySelector({ connection.route.value }, { proxy.port }) }

    /** Called once from Application.onCreate: loads the server and keeps WebView in step. */
    fun start() {
        scope.launch { connection.start() }
        scope.launch(Dispatchers.Main) {
            val webView = WebViewTunnel(log)
            connection.route.collect { route -> webView.apply(route) { proxy.port } }
        }
        scope.launch {
            // Torrents (trackers and peers) go through the tunnel too; nothing to undo before
            // the first route, so the torrent engine is not created for nothing at start.
            var proxied = false
            connection.route.map { it != null }.distinctUntilChanged().collect { on ->
                if (!on && !proxied) return@collect
                proxied = on
                app.torrent.libTorrentEngine.useProxy(if (on) proxy.port else null)
            }
        }
    }

    private companion object {
        const val TAG = "BookReconServer"
        const val TOKEN_KEY_ALIAS = "server_token"
        const val SEARCH_TIMEOUT_SECONDS = 100L
    }
}
