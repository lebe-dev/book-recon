package ru.tinyops.android.bookrecon.core.diagnostics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.SourceRoute

/** One way to reach a source: a site and the route to it. */
data class Probe(val source: Source, val baseUrl: String, val route: SourceRoute)

/** Why a probe failed. */
enum class ProbeProblem {
    /** No answer in time. */
    TIMEOUT,

    /** The connection could not be made: blocked, refused, no DNS name, no network. */
    NO_CONNECTION,

    /** Something answered, but not the site: a server error or a stub page. */
    BAD_ANSWER,
}

sealed interface ProbeOutcome {
    /** The site answered in [millis]. */
    data class Reachable(val millis: Long) : ProbeOutcome

    data class Failed(val problem: ProbeProblem) : ProbeOutcome
}

data class ProbeResult(val probe: Probe, val outcome: ProbeOutcome)

/** Opens one page of a probe's site the way the probe says and times the answer. */
fun interface ConnectivityProbe {
    suspend fun check(probe: Probe): ProbeOutcome
}

/** The best way found to reach a source. */
data class SourceAdvice(val route: SourceRoute, val baseUrl: String)

/**
 * Outcome of a [ConnectionTest]: every probe and the advice per source, null where the source
 * answered nowhere.
 */
data class ConnectionReport(
    val results: List<ProbeResult>,
    val flibusta: SourceAdvice?,
    val rutracker: SourceAdvice?,
) {
    /** Settings with the advice applied; a source without advice keeps its settings. */
    fun applyTo(settings: Settings): Settings {
        var result = settings
        flibusta?.let { result = result.copy(flibustaRoute = it.route, flibustaBaseUrl = it.baseUrl) }
        rutracker?.let { result = result.copy(rutrackerRoute = it.route) }
        return result
    }

    /** True when applying the advice would change [settings]. */
    fun changes(settings: Settings): Boolean = applyTo(settings) != settings
}

/** Which probes a connection test makes and how it picks the best one. */
object ConnectionPlan {
    const val RUTRACKER_URL = "https://rutracker.org"

    /** Known RuTracker addresses; the client tries them in this order, the connection test probes them all. */
    val RUTRACKER_MIRRORS = listOf(RUTRACKER_URL, "https://rutracker.net")

    /** Added to the time through the server: direct does not depend on the server. */
    private const val SERVER_PENALTY_MILLIS = 300L

    /** Added to the time of a mirror other than the current one: no change for a trifle. */
    private const val OTHER_MIRROR_PENALTY_MILLIS = 250L

    /**
     * Probes for [settings]: every Flibusta mirror (the one in settings first) and RuTracker,
     * directly, and through the server for hosts in [serverHosts], the hosts the approved
     * server tunnels; null when the server is not approved.
     */
    fun probes(settings: Settings, serverHosts: Set<String>?): List<Probe> {
        val mirrors = (listOf(settings.flibustaEffectiveUrl) + Settings.FLIBUSTA_MIRRORS).distinct()
        val sites = mirrors.map { Source.FLIBUSTA to it } + RUTRACKER_MIRRORS.map { Source.RUTRACKER to it }
        return sites.flatMap { (source, url) ->
            buildList {
                add(Probe(source, url, SourceRoute.DIRECT))
                if (serverHosts != null && serves(serverHosts, hostOf(url))) add(Probe(source, url, SourceRoute.SERVER))
            }
        }
    }

    /**
     * The best reachable probe of [source]: the shortest time, where the server and a mirror
     * other than the one in [current] count a little slower. Null when nothing answered.
     */
    fun advise(source: Source, current: Settings, results: List<ProbeResult>): SourceAdvice? =
        results
            .filter { it.probe.source == source }
            .mapNotNull { result ->
                val millis = (result.outcome as? ProbeOutcome.Reachable)?.millis ?: return@mapNotNull null
                val probe = result.probe
                var score = millis
                if (probe.route == SourceRoute.SERVER) score += SERVER_PENALTY_MILLIS
                if (source == Source.FLIBUSTA && probe.baseUrl != current.flibustaEffectiveUrl) score += OTHER_MIRROR_PENALTY_MILLIS
                probe to score
            }
            .minByOrNull { it.second }
            ?.first
            ?.let { SourceAdvice(it.route, it.baseUrl) }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    private fun serves(hosts: Set<String>, host: String): Boolean = hosts.any { host == it || host.endsWith(".$it") }
}

/** Tries every way to reach the sources at once and advises the best one per source. */
class ConnectionTest(private val probe: ConnectivityProbe) {
    /** Runs the probes of [ConnectionPlan.probes]; [onProgress] gets the results so far after each one. */
    suspend fun run(
        settings: Settings,
        serverHosts: Set<String>?,
        onProgress: (List<ProbeResult>) -> Unit = {},
    ): ConnectionReport {
        val probes = ConnectionPlan.probes(settings, serverHosts)
        val done = mutableListOf<ProbeResult>()
        val lock = Mutex()
        val results = coroutineScope {
            probes.map { p ->
                async {
                    val result = ProbeResult(p, probe.check(p))
                    lock.withLock {
                        done += result
                        onProgress(done.toList())
                    }
                    result
                }
            }.awaitAll()
        }
        return ConnectionReport(
            results = results,
            flibusta = ConnectionPlan.advise(Source.FLIBUSTA, settings, results),
            rutracker = ConnectionPlan.advise(Source.RUTRACKER, settings, results),
        )
    }
}
