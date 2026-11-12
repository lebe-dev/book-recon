package ru.tinyops.android.bookrecon.server

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.SourceRoute

/**
 * The part of the approved route the user sends through the server ([Settings.flibustaRoute],
 * [Settings.rutrackerRoute]). Flibusta hosts are those named "flibusta" and the mirror from
 * settings; every other host belongs to RuTracker (its trackers, the Cloudflare check). Null
 * when nothing is left.
 */
fun TunnelRoute.forSources(settings: Settings): TunnelRoute? {
    val mirror = settings.flibustaEffectiveUrl.toHttpUrlOrNull()?.host?.lowercase()
    val kept = hosts.filterTo(mutableSetOf()) { host ->
        val flibusta = "flibusta" in host || (mirror != null && hostMatches(mirror, setOf(host)))
        val route = if (flibusta) settings.flibustaRoute else settings.rutrackerRoute
        route == SourceRoute.SERVER
    }
    if (kept.isEmpty()) return null
    return copy(hosts = kept)
}

/** True when the route carries RuTracker: its search and `.torrent` then come from the server too. */
val TunnelRoute.carriesRuTracker: Boolean
    get() = RUTRACKER_HOSTS.any { serves(it) }

private val RUTRACKER_HOSTS = listOf("rutracker.org", "rutracker.net")

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
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(TunnelProxy.LOOPBACK, port())))
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
        val config = ProxyConfig.Builder().addProxyRule("${TunnelProxy.LOOPBACK.hostAddress}:${port()}", ProxyConfig.MATCH_HTTPS)
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
