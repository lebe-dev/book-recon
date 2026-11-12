package ru.tinyops.android.bookrecon.diagnostics

import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectionTest
import ru.tinyops.android.bookrecon.core.diagnostics.ConnectivityProbe
import ru.tinyops.android.bookrecon.core.diagnostics.Probe
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeProblem
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository

class ConnectionCheckTest {
    @Test
    fun `runs in its own scope, ignores a second start and applies the advice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val probe = ConnectivityProbe { p ->
            gate.await()
            if (p.route == SourceRoute.SERVER) ProbeOutcome.Reachable(100) else ProbeOutcome.Failed(ProbeProblem.TIMEOUT)
        }
        val settings = FakeSettingsRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var probesStarted = 0
        val check = ConnectionCheck(
            test = ConnectionTest { p -> probesStarted++; probe.check(p) },
            settings = settings,
            serverHosts = { setOf("rutracker.org", "flibusta.is", "flibusta.site") },
            scope = scope,
        )

        check.start()
        scope.advanceUntilIdle()
        val running = check.state.value as ConnectionCheckState.Running
        assertEquals(0, running.results.size)
        assertEquals(6, running.total)
        check.start()
        gate.complete(Unit)
        scope.advanceUntilIdle()

        val done = check.state.value as ConnectionCheckState.Done
        assertEquals(done.report.results.size, probesStarted)
        assertTrue(done.report.changes(Settings()))
        check.apply()
        val applied = settings.settings.first()
        assertEquals(SourceRoute.SERVER, applied.rutrackerRoute)
        assertEquals(SourceRoute.SERVER, applied.flibustaRoute)
        assertFalse((check.state.value as ConnectionCheckState.Done).report.changes(applied))
    }

    @Test
    fun `http probe tells a live site, a stub, an error and a closed port apart`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<title>Флибуста</title>").build())
            server.enqueue(MockResponse.Builder().body("parked domain").build())
            server.enqueue(MockResponse.Builder().code(502).build())
            server.enqueue(MockResponse.Builder().code(403).body("Just a moment").build())
            val base = server.url("/").toString().trimEnd('/')
            val probe = OkHttpConnectivityProbe(direct = OkHttpClient(), throughServer = { null })

            assertTrue(probe.check(Probe(Source.FLIBUSTA, base, SourceRoute.DIRECT)) is ProbeOutcome.Reachable)
            assertEquals(ProbeOutcome.Failed(ProbeProblem.BAD_ANSWER), probe.check(Probe(Source.FLIBUSTA, base, SourceRoute.DIRECT)))
            assertEquals(ProbeOutcome.Failed(ProbeProblem.BAD_ANSWER), probe.check(Probe(Source.FLIBUSTA, base, SourceRoute.DIRECT)))
            // A Cloudflare check means RuTracker is reachable: WebView passes it later.
            assertTrue(probe.check(Probe(Source.RUTRACKER, base, SourceRoute.DIRECT)) is ProbeOutcome.Reachable)
            val paths = List(4) { server.takeRequest().url.encodedPath }
            assertEquals(listOf("/", "/", "/", "/forum/index.php"), paths)
        }
        val closed = ServerSocket(0).use { it.localPort }
        val probe = OkHttpConnectivityProbe(direct = OkHttpClient(), throughServer = { null })
        assertEquals(
            ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION),
            probe.check(Probe(Source.FLIBUSTA, "http://127.0.0.1:$closed", SourceRoute.DIRECT)),
        )
        assertEquals(
            ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION),
            probe.check(Probe(Source.FLIBUSTA, "http://127.0.0.1:$closed", SourceRoute.SERVER)),
        )
    }
}
