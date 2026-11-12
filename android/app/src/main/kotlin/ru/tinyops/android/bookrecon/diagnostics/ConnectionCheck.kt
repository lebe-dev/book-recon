package ru.tinyops.android.bookrecon.diagnostics

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionPlan
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionReport
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionTest
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectivityProbe
import ru.tinyops.android.bookrecon.core.diagnostics.Probe
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeProblem
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeResult
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.port.SettingsRepository

/** Where the connection test is, as the settings screens show it. */
sealed interface ConnectionCheckState {
    data object Idle : ConnectionCheckState

    /** [results] of [total] probes are in. */
    data class Running(val results: List<ProbeResult>, val total: Int) : ConnectionCheckState

    data class Done(val report: ConnectionReport) : ConnectionCheckState
}

/**
 * Runs the [ConnectionTest] in [scope], which outlives the screens: the test goes on in the
 * background when the user leaves settings. One test at a time; [serverHosts] are the hosts
 * the approved server tunnels, null while it is not approved.
 */
class ConnectionCheck(
    private val test: ConnectionTest,
    private val settings: SettingsRepository,
    private val serverHosts: () -> Set<String>?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<ConnectionCheckState>(ConnectionCheckState.Idle)
    val state: StateFlow<ConnectionCheckState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Starts a test unless one is running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val current = settings.settings.first()
            val hosts = serverHosts()
            val total = ConnectionPlan.probes(current, hosts).size
            mutableState.value = ConnectionCheckState.Running(emptyList(), total)
            log("Connection test started, server ${if (hosts == null) "not connected" else "connected"}")
            val report = test.run(current, hosts) { done ->
                mutableState.value = ConnectionCheckState.Running(done, total)
            }
            report.results.forEach { log("Connection test: ${it.describe()}") }
            mutableState.value = ConnectionCheckState.Done(report)
        }
    }

    /** Applies the advice of the finished test to the settings. */
    suspend fun apply() {
        val done = mutableState.value as? ConnectionCheckState.Done ?: return
        settings.update { done.report.applyTo(it) }
        log("Connection test advice applied")
    }

    private fun ProbeResult.describe(): String {
        val via = if (probe.route == SourceRoute.SERVER) "through the server" else "directly"
        val outcome = when (val o = outcome) {
            is ProbeOutcome.Reachable -> "answered in ${o.millis} ms"
            is ProbeOutcome.Failed -> "failed: ${o.problem.name.lowercase().replace('_', ' ')}"
        }
        return "${probe.baseUrl} $via $outcome"
    }
}

/**
 * Opens a page of a site ([ConnectionPlan.probeUrl]) and times it: Flibusta must answer with its own page,
 * RuTracker with anything but a server error (a Cloudflare check counts, WebView passes it
 * later). [direct] must not use the tunnel; [throughServer] is a client that sends everything
 * through the local tunnel proxy, null when there is none.
 */
class OkHttpConnectivityProbe(
    private val direct: OkHttpClient,
    private val throughServer: () -> OkHttpClient?,
) : ConnectivityProbe {
    override suspend fun check(probe: Probe): ProbeOutcome = withContext(Dispatchers.IO) {
        val client = when (probe.route) {
            SourceRoute.DIRECT -> direct
            SourceRoute.SERVER -> throughServer() ?: return@withContext ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION)
        }
        timedProbe(client, probe.source, ConnectionPlan.probeUrl(probe, ConnectionPlan.newNonce()))
    }
}

/**
 * Opens [url] with [client] and times the answer, blocking: Flibusta must answer with its own
 * page, RuTracker with anything but a server error.
 */
internal fun timedProbe(client: OkHttpClient, source: Source, url: String): ProbeOutcome {
    val started = System.nanoTime()
    return try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body.source().let { source ->
                source.request(ProbeLimits.BODY_LIMIT)
                source.buffer.snapshot().utf8()
            }
            val millis = (System.nanoTime() - started) / ProbeLimits.NANOS_PER_MILLI
            val fine = when (source) {
                Source.FLIBUSTA -> response.isSuccessful && ProbeLimits.FLIBUSTA_MARKERS.any { body.contains(it, ignoreCase = true) }
                Source.RUTRACKER -> response.code < ProbeLimits.SERVER_ERROR
            }
            if (fine) ProbeOutcome.Reachable(millis) else ProbeOutcome.Failed(ProbeProblem.BAD_ANSWER)
        }
    } catch (_: InterruptedIOException) {
        ProbeOutcome.Failed(ProbeProblem.TIMEOUT)
    } catch (_: IOException) {
        ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION)
    }
}

private object ProbeLimits {
    const val BODY_LIMIT = 64L * 1024
    const val SERVER_ERROR = 500
    const val NANOS_PER_MILLI = 1_000_000L
    val FLIBUSTA_MARKERS = listOf("flibusta", "флибуста")
}
