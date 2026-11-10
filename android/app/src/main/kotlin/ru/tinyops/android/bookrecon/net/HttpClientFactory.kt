package ru.tinyops.android.bookrecon.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response

/**
 * Timeouts of one phase of a request. The overall deadline is the caller's: a large book must
 * not be cut off by the client.
 *
 * @property readTimeout wait for the response headers and between body bytes.
 * @property slowReadTimeout the same for [slowHosts]: RuTracker answers slowly while Cloudflare
 *   decides whether to challenge.
 */
data class NetworkConfig(
    val connectTimeout: Duration = 5.seconds,
    val tlsTimeout: Duration = 5.seconds,
    val readTimeout: Duration = 12.seconds,
    val slowReadTimeout: Duration = 60.seconds,
    val slowHosts: Set<String> = setOf("rutracker.org", "rutracker.net", "rutracker.nl"),
) {
    /** True for a host in [slowHosts] or a subdomain of one. */
    fun isSlowHost(host: String): Boolean = slowHosts.any { host == it || host.endsWith(".$it") }
}

/**
 * Builds the single OkHttp client of the app. [userAgent] is read on every request and must be
 * the one WebView uses: Cloudflare binds `cf_clearance` to it. [proxySelector] routes blocked
 * hosts through the Book Recon server; null means the system default. [logging] sees every
 * request first, before the User-Agent is set and challenges are solved.
 */
fun buildOkHttpClient(
    cookieJar: CookieJar,
    solver: ChallengeSolver,
    userAgent: () -> String,
    config: NetworkConfig = NetworkConfig(),
    proxySelector: ProxySelector? = null,
    logging: Interceptor? = null,
): OkHttpClient = OkHttpClient.Builder()
    .apply { if (proxySelector != null) proxySelector(proxySelector) }
    .apply { if (logging != null) addInterceptor(logging) }
    .cookieJar(cookieJar)
    .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .writeTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .addInterceptor(UserAgentInterceptor(userAgent))
    .addInterceptor(HostTimeoutInterceptor(config))
    .addInterceptor(CloudflareInterceptor(solver))
    .eventListenerFactory { HandshakeTimeout(config.tlsTimeout) }
    .build()

/** Sets the WebView User-Agent unless the caller passed its own. */
private class UserAgentInterceptor(private val userAgent: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent()).build())
    }
}

/** Gives slow hosts a longer read timeout. */
private class HostTimeoutInterceptor(private val config: NetworkConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!config.isSlowHost(chain.request().url.host)) return chain.proceed(chain.request())
        val millis = config.slowReadTimeout.inWholeMilliseconds.toInt()
        return chain
            .withReadTimeout(millis, TimeUnit.MILLISECONDS)
            .withWriteTimeout(millis, TimeUnit.MILLISECONDS)
            .proceed(chain.request())
    }
}

/**
 * Cancels the call when a TLS handshake takes longer than [timeout]. OkHttp itself bounds the
 * handshake only by the read timeout, which is far longer for slow hosts. The client reports
 * a call cancelled this way as a timeout.
 */
private class HandshakeTimeout(private val timeout: Duration) : EventListener() {
    @Volatile
    private var pending: ScheduledFuture<*>? = null

    override fun secureConnectStart(call: Call) {
        disarm()
        pending = scheduler.schedule(
            { call.cancel() },
            timeout.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = disarm()

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = disarm()

    override fun callEnd(call: Call) = disarm()

    override fun callFailed(call: Call, ioe: IOException) = disarm()

    override fun canceled(call: Call) = disarm()

    private fun disarm() {
        pending?.cancel(false)
        pending = null
    }

    companion object {
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "tls-handshake-timeout").apply { isDaemon = true }
        }
    }
}
