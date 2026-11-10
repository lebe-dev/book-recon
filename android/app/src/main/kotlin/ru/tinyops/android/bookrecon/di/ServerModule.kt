package ru.tinyops.android.bookrecon.di

import android.os.Build
import android.util.Log
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.server.DataStoreServerStore
import ru.tinyops.android.bookrecon.server.OkHttpServerApi
import ru.tinyops.android.bookrecon.server.ServerApi
import ru.tinyops.android.bookrecon.server.ServerConnection
import ru.tinyops.android.bookrecon.server.ServerStore
import ru.tinyops.android.bookrecon.server.TunnelProxy
import ru.tinyops.android.bookrecon.server.TunnelProxySelector
import ru.tinyops.android.bookrecon.server.WebViewTunnel
import ru.tinyops.android.bookrecon.storage.settings.AesGcmCipher
import ru.tinyops.android.bookrecon.storage.settings.KeystoreKeys

/** Connection to the Book Recon server and the tunnel through it. */
class ServerModule(private val app: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log: (String) -> Unit = { Log.i(TAG, it) }

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
            log = { Log.w(TAG, it) },
        )
    }

    val api: ServerApi by lazy { OkHttpServerApi(directClient) }

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
    }

    private companion object {
        const val TAG = "BookReconServer"
        const val TOKEN_KEY_ALIAS = "server_token"
    }
}
