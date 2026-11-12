package ru.tinyops.android.bookrecon.diagnostics

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionPlan
import ru.tinyops.android.bookrecon.core.diagnostics.Probe
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.route.AddressProber

/**
 * [AddressProber] over OkHttp: [dns] resolves, and each probe connects [client] to the address
 * it is given while the request keeps the mirror's host (TLS name and `Host` header). [client]
 * must be direct: no proxy, no cookies, no Cloudflare solving.
 */
class OkHttpAddressProber(
    private val client: OkHttpClient,
    private val dns: Dns = Dns.SYSTEM,
) : AddressProber {
    override suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            dns.lookup(host).mapNotNull { it.hostAddress }
        } catch (_: UnknownHostException) {
            emptyList()
        }
    }

    override suspend fun probe(baseUrl: String, address: String): ProbeOutcome = withContext(Dispatchers.IO) {
        val pinned = client.newBuilder().dns { listOf(InetAddress.getByName(address)) }.build()
        val url = ConnectionPlan.probeUrl(Probe(Source.FLIBUSTA, baseUrl, SourceRoute.DIRECT), ConnectionPlan.newNonce())
        timedProbe(pinned, Source.FLIBUSTA, url)
    }
}
