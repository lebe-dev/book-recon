package ru.tinyops.android.bookrecon.core.route

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.domain.hostOf
import ru.tinyops.android.bookrecon.core.mirror.MirrorFallback
import ru.tinyops.android.bookrecon.core.mirror.MirrorSites

/** Resolves hosts and opens a mirror at one of its addresses, straight, with no proxy. */
interface AddressProber {
    /** Addresses of [host] as text, empty when it cannot be resolved. */
    suspend fun resolve(host: String): List<String>

    /** Opens a page of [baseUrl] connecting to [address] and times the answer. */
    suspend fun probe(baseUrl: String, address: String): ProbeOutcome
}

/** How one address of one mirror answered in a [FlibustaRouteCheck]. */
data class AddressProbeResult(val baseUrl: String, val address: String, val outcome: ProbeOutcome)

/** Every probe of a check, and the mirror whose fastest address won; null when none answered. */
data class RouteCheckReport(val results: List<AddressProbeResult>, val bestMirror: String?)

/**
 * Finds the best way to Flibusta: probes every address of every mirror at once and tells
 * [memory] what it found. Addresses of a host are ordered fastest first, the silent ones last;
 * the mirror with the fastest address becomes the remembered one for the mirror list, so the
 * search goes there first. Nothing is changed in the settings.
 */
class FlibustaRouteCheck(
    private val prober: AddressProber,
    private val memory: RouteMemory,
    private val log: (String) -> Unit = {},
) {
    /** Checks [mirrors], in the order the search tries them. */
    suspend fun run(mirrors: List<String>): RouteCheckReport {
        val targets = mirrors.map { base ->
            val host = hostOf(base)
            val addresses = prober.resolve(host)
            if (addresses.isEmpty()) log("Flibusta route check: $host could not be resolved")
            Triple(base, host, addresses)
        }
        val results = coroutineScope {
            targets.flatMap { (base, _, addresses) ->
                addresses.map { address -> async { AddressProbeResult(base, address, prober.probe(base, address)) } }
            }.awaitAll()
        }
        results.forEach { log("Flibusta route check: ${it.baseUrl} at ${it.address} ${describe(it.outcome)}") }

        targets.groupBy({ it.second }, { it.first }).forEach { (host, bases) -> record(host, results.filter { it.baseUrl in bases }) }

        val best = results
            .mapNotNull { result -> millisOf(result)?.let { result to it } }
            .minWithOrNull(compareBy<Pair<AddressProbeResult, Long>> { it.second }.thenBy { mirrors.indexOf(it.first.baseUrl) })
            ?.first
            ?.baseUrl
        if (best == null) {
            log("Flibusta route check: no Flibusta address answered")
        } else {
            MirrorFallback.keyOf(MirrorSites.FLIBUSTA, mirrors)?.let { memory.remember(it, best) }
            log("Flibusta route check: $best is the best mirror")
        }
        return RouteCheckReport(results, best)
    }

    private fun record(host: String, results: List<AddressProbeResult>) {
        val fastest = results
            .mapNotNull { result -> millisOf(result)?.let { result.address to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.min() }
            .entries.sortedBy { it.value }
            .map { it.key }
        val failed = results.map { it.address }.distinct().filterNot { it in fastest }
        memory.addressesChecked(host, fastest, failed)
    }

    private fun millisOf(result: AddressProbeResult): Long? = (result.outcome as? ProbeOutcome.Reachable)?.millis

    private fun describe(outcome: ProbeOutcome): String = when (outcome) {
        is ProbeOutcome.Reachable -> "answered in ${outcome.millis} ms"
        is ProbeOutcome.Failed -> "failed: ${outcome.problem.name.lowercase().replace('_', ' ')}"
    }
}
