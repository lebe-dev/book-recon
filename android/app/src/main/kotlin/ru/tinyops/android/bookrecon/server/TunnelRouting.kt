package ru.tinyops.android.bookrecon.server

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor

/**
 * Sends HTTPS requests to hosts of the current [route] through the local [TunnelProxy] on
 * [port]; everything else goes direct. Read on every request, so approval and disconnection
 * apply without rebuilding the OkHttp client.
 */
class TunnelProxySelector(
    private val route: () -> TunnelRoute?,
    private val port: () -> Int,
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val current = route()
        val host = uri.host
        if (current == null || host == null || uri.scheme != "https" || !current.serves(host)) return DIRECT
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), port())))
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit

    private companion object {
        val DIRECT = listOf(Proxy.NO_PROXY)
    }
}

/**
 * Points WebView at the tunnel for the same hosts as OkHttp: `cf_clearance` is bound to the
 * IP address, so the Cloudflare check in WebView must come from the server too. With reverse
 * bypass the bypass list names the hosts that use the proxy; WebView without that feature
 * sends all HTTPS through the proxy, which refuses other hosts.
 */
class WebViewTunnel(private val log: (String) -> Unit = {}) {
    private val sameThread = Executor { it.run() }
    private var overridden = false

    /** Must run on the main thread. [port] is read only when a proxy is set. */
    fun apply(route: TunnelRoute?, port: () -> Int) {
        // Nothing to clear yet: avoid loading WebView at app start for no reason.
        if (route == null && !overridden) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (route != null) log("WebView cannot use a proxy; Cloudflare checks will not go through the server")
            return
        }
        val controller = ProxyController.getInstance()
        if (route == null) {
            controller.clearProxyOverride(sameThread) {}
            overridden = false
            log("WebView proxy cleared")
            return
        }
        val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:${port()}", ProxyConfig.MATCH_HTTPS)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
            route.hosts.forEach { host ->
                config.addBypassRule(host)
                config.addBypassRule("*.$host")
            }
            config.setReverseBypassEnabled(true)
        }
        controller.setProxyOverride(config.build(), sameThread) {}
        overridden = true
        log("WebView proxy set for ${route.hosts.size} hosts")
    }
}
