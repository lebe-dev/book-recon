package ru.tinyops.android.bookrecon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionReport
import ru.tinyops.android.bookrecon.core.diagnostics.Probe
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeProblem
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeResult
import ru.tinyops.android.bookrecon.core.diagnostics.SourceAdvice
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.diagnostics.ConnectionCheckState
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme

/**
 * The connection test: a button, the probes as they come in and the best route per source,
 * which "Apply" writes into [settings]. Shown on the Server and Diagnostics pages.
 */
@Composable
internal fun ConnectionTestBlock(
    state: ConnectionCheckState,
    settings: Settings,
    serverApproved: Boolean,
    actions: ConnectionTestActions,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.settings_connection_test_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val running = state as? ConnectionCheckState.Running
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = actions.onStart, enabled = running == null) {
                val again = state is ConnectionCheckState.Done
                Text(stringResource(if (again) R.string.settings_connection_test_again else R.string.settings_connection_test_start))
            }
            if (running != null) {
                Spacer(modifier = Modifier.width(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_connection_test_progress, running.results.size, running.total),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!serverApproved) {
            Text(
                stringResource(R.string.settings_connection_test_no_server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val results = when (state) {
            ConnectionCheckState.Idle -> emptyList()
            is ConnectionCheckState.Running -> state.results
            is ConnectionCheckState.Done -> state.report.results
        }
        results.forEach { ResultLine(it) }
        if (state is ConnectionCheckState.Done) Advice(state.report, settings, actions.onApply)
    }
}

@Composable
private fun ResultLine(result: ProbeResult) {
    val probe = result.probe
    val outcome = when (val o = result.outcome) {
        is ProbeOutcome.Reachable -> stringResource(R.string.settings_connection_test_millis, o.millis)
        is ProbeOutcome.Failed -> stringResource(
            when (o.problem) {
                ProbeProblem.TIMEOUT -> R.string.settings_connection_test_timeout
                ProbeProblem.NO_CONNECTION -> R.string.settings_connection_test_no_connection
                ProbeProblem.BAD_ANSWER -> R.string.settings_connection_test_bad_answer
            },
        )
    }
    Text(
        text = stringResource(
            R.string.settings_connection_test_line,
            probe.source.title(),
            hostOf(probe.baseUrl),
            routeLabel(probe.route),
            outcome,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (result.outcome is ProbeOutcome.Reachable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Advice(report: ConnectionReport, settings: Settings, onApply: () -> Unit) = Column(
    modifier = Modifier.padding(top = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(
        stringResource(R.string.settings_connection_test_advice),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    AdviceLine(Source.RUTRACKER, report.rutracker)
    AdviceLine(Source.FLIBUSTA, report.flibusta)
    if (report.flibusta == null && report.rutracker == null) return@Column
    if (report.changes(settings)) {
        Button(onClick = onApply) { Text(stringResource(R.string.settings_connection_test_apply)) }
    } else {
        Text(stringResource(R.string.settings_connection_test_applied), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AdviceLine(source: Source, advice: SourceAdvice?) {
    val text = when {
        advice == null -> stringResource(R.string.settings_connection_test_unreachable, source.title())
        source == Source.RUTRACKER -> stringResource(R.string.settings_connection_test_advice_rutracker, routeLabel(advice.route))
        else -> stringResource(R.string.settings_connection_test_advice_flibusta, routeLabel(advice.route), hostOf(advice.baseUrl))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (advice == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun routeLabel(route: SourceRoute): String = stringResource(
    if (route == SourceRoute.SERVER) R.string.settings_connection_test_server else R.string.settings_connection_test_direct,
)

private fun Source.title(): String = when (this) {
    Source.FLIBUSTA -> "Flibusta"
    Source.RUTRACKER -> "RuTracker"
}

private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

@Preview(showBackground = true)
@Composable
private fun ConnectionTestDonePreview() {
    val results = listOf(
        ProbeResult(Probe(Source.FLIBUSTA, "https://flibusta.is", SourceRoute.DIRECT), ProbeOutcome.Reachable(240)),
        ProbeResult(Probe(Source.FLIBUSTA, "https://flibusta.is", SourceRoute.SERVER), ProbeOutcome.Failed(ProbeProblem.TIMEOUT)),
        ProbeResult(Probe(Source.RUTRACKER, "https://rutracker.org", SourceRoute.DIRECT), ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION)),
        ProbeResult(Probe(Source.RUTRACKER, "https://rutracker.org", SourceRoute.SERVER), ProbeOutcome.Reachable(610)),
    )
    val report = ConnectionReport(
        results,
        flibusta = SourceAdvice(SourceRoute.DIRECT, "https://flibusta.is"),
        rutracker = SourceAdvice(SourceRoute.SERVER, "https://rutracker.org"),
    )
    BookReconTheme {
        Surface {
            ConnectionTestBlock(ConnectionCheckState.Done(report), Settings(), serverApproved = true, ConnectionTestActions())
        }
    }
}
