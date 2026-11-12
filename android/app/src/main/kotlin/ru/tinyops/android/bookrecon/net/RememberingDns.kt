package ru.tinyops.android.bookrecon.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import ru.tinyops.android.bookrecon.core.route.RouteMemory

/**
 * Resolves through [delegate] and orders the addresses by what [routes] learnt: the address
 * that answered last first, the ones that failed last. OkHttp tries them in this order, so a
 * host with a dead address costs a connect timeout once, not on every new connection.
 */
class RememberingDns(private val routes: RouteMemory, private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = delegate.lookup(hostname)
        if (resolved.size < 2) return resolved
        val byText = resolved.associateBy { it.hostAddress.orEmpty() }
        return routes.order(hostname, resolved.map { it.hostAddress.orEmpty() }).mapNotNull(byText::get)
    }
}

/**
 * Tells [routes] how each address of a call did: a failed connect or a timeout after
 * connecting marks it failed, so does a server error (another front end of the same site may
 * be fine), any other answer marks it working. Calls through a proxy say nothing about the
 * address of the site and are left out.
 */
private class RouteTracker(private val routes: RouteMemory) : EventListener() {
    @Volatile
    private var current: Pair<String, String>? = null

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        if (proxy.type() != Proxy.Type.DIRECT) return
        val address = inetSocketAddress.address?.hostAddress ?: return
        routes.addressFailed(call.request().url.host, address)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val route = connection.route()
        current = if (route.proxy.type() == Proxy.Type.DIRECT) {
            route.socketAddress.address?.hostAddress?.let { route.address.url.host to it }
        } else {
            null
        }
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val (host, address) = current ?: return
        if (response.code >= SERVER_ERROR) routes.addressFailed(host, address) else routes.addressWorked(host, address)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        if (ioe !is InterruptedIOException) return
        val (host, address) = current ?: return
        routes.addressFailed(host, address)
    }

    private companion object {
        const val SERVER_ERROR = 500
    }
}

/** The events of one call go to [first] and then to [second]. */
private class BothListeners(private val first: EventListener, private val second: EventListener) : EventListener() {
    override fun secureConnectStart(call: Call) {
        first.secureConnectStart(call)
        second.secureConnectStart(call)
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        first.secureConnectEnd(call, handshake)
        second.secureConnectEnd(call, handshake)
    }

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        first.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        second.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        first.connectionAcquired(call, connection)
        second.connectionAcquired(call, connection)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        first.responseHeadersEnd(call, response)
        second.responseHeadersEnd(call, response)
    }

    override fun callEnd(call: Call) {
        first.callEnd(call)
        second.callEnd(call)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        first.callFailed(call, ioe)
        second.callFailed(call, ioe)
    }

    override fun canceled(call: Call) {
        first.canceled(call)
        second.canceled(call)
    }
}

/** [listener] alone, or together with a [RouteTracker] of [routes] when there are any. */
internal fun withRouteTracking(listener: EventListener, routes: RouteMemory?): EventListener =
    if (routes == null) listener else BothListeners(listener, RouteTracker(routes))
