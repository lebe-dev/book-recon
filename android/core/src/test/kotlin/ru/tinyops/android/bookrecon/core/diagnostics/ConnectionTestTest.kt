package ru.tinyops.android.bookrecon.core.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.SourceRoute

class ConnectionTestTest {
    private val serverHosts = setOf("rutracker.org", "rutracker.net", "flibusta.is", "flibusta.site")

    @Test
    fun `without a server only direct probes are planned, for every known mirror`() {
        val probes = ConnectionPlan.probes(Settings(), serverHosts = null)

        assertTrue(probes.all { it.route == SourceRoute.DIRECT })
        assertEquals(
            listOf("https://flibusta.site", "https://flibusta.is"),
            probes.filter { it.source == Source.FLIBUSTA }.map { it.baseUrl },
        )
        assertEquals(
            listOf("https://rutracker.org", "https://rutracker.net"),
            probes.filter { it.source == Source.RUTRACKER }.map { it.baseUrl },
        )
    }

    @Test
    fun `a custom mirror is probed first, and through the server only when the server serves it`() {
        val probes = ConnectionPlan.probes(Settings(flibustaBaseUrl = "https://flibusta.example"), serverHosts)

        val flibusta = probes.filter { it.source == Source.FLIBUSTA }
        assertEquals("https://flibusta.example", flibusta.first().baseUrl)
        assertFalse(flibusta.any { it.baseUrl == "https://flibusta.example" && it.route == SourceRoute.SERVER })
        assertTrue(flibusta.any { it.baseUrl == "https://flibusta.site" && it.route == SourceRoute.SERVER })
        assertTrue(probes.any { it.source == Source.RUTRACKER && it.route == SourceRoute.SERVER })
    }

    @Test
    fun `direct wins when it is about as fast as the server`() {
        val advice = ConnectionPlan.advise(
            Source.RUTRACKER,
            current = Settings(),
            results = listOf(
                result(Source.RUTRACKER, RUTRACKER, SourceRoute.DIRECT, 400),
                result(Source.RUTRACKER, RUTRACKER, SourceRoute.SERVER, 300),
            ),
        )

        assertEquals(SourceAdvice(SourceRoute.DIRECT, RUTRACKER), advice)
    }

    @Test
    fun `the server wins when direct does not answer`() {
        val advice = ConnectionPlan.advise(
            Source.FLIBUSTA,
            current = Settings(),
            results = listOf(
                failed(Source.FLIBUSTA, IS, SourceRoute.DIRECT),
                failed(Source.FLIBUSTA, SITE, SourceRoute.DIRECT),
                failed(Source.FLIBUSTA, IS, SourceRoute.SERVER),
                result(Source.FLIBUSTA, SITE, SourceRoute.SERVER, 900),
            ),
        )

        assertEquals(SourceAdvice(SourceRoute.SERVER, SITE), advice)
    }

    @Test
    fun `the current mirror is kept unless another one is clearly faster`() {
        val current = Settings(flibustaBaseUrl = SITE)
        val close = ConnectionPlan.advise(
            Source.FLIBUSTA,
            current,
            listOf(result(Source.FLIBUSTA, IS, SourceRoute.DIRECT, 300), result(Source.FLIBUSTA, SITE, SourceRoute.DIRECT, 450)),
        )
        val far = ConnectionPlan.advise(
            Source.FLIBUSTA,
            current,
            listOf(result(Source.FLIBUSTA, IS, SourceRoute.DIRECT, 300), result(Source.FLIBUSTA, SITE, SourceRoute.DIRECT, 3000)),
        )

        assertEquals(SITE, close?.baseUrl)
        assertEquals(IS, far?.baseUrl)
    }

    @Test
    fun `no advice when a source answers nowhere`() {
        assertNull(ConnectionPlan.advise(Source.RUTRACKER, Settings(), listOf(failed(Source.RUTRACKER, RUTRACKER, SourceRoute.DIRECT))))
    }

    @Test
    fun `run probes everything, reports progress and tells whether the advice changes settings`() = runTest {
        val probe = ConnectivityProbe { p ->
            when {
                p.source == Source.RUTRACKER && p.route == SourceRoute.DIRECT -> ProbeOutcome.Failed(ProbeProblem.TIMEOUT)
                p.source == Source.FLIBUSTA && p.baseUrl == IS && p.route == SourceRoute.DIRECT -> ProbeOutcome.Reachable(200)
                p.route == SourceRoute.SERVER -> ProbeOutcome.Reachable(500)
                else -> ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION)
            }
        }
        val progress = mutableListOf<Int>()

        val report = ConnectionTest(probe).run(Settings(), serverHosts) { progress += it.size }

        assertEquals(ConnectionPlan.probes(Settings(), serverHosts).size, report.results.size)
        assertEquals(report.results.size, progress.last())
        assertEquals(SourceAdvice(SourceRoute.DIRECT, IS), report.flibusta)
        assertEquals(SourceAdvice(SourceRoute.SERVER, RUTRACKER), report.rutracker)
        assertTrue(report.changes(Settings()))
        assertFalse(report.changes(report.applyTo(Settings())))
        assertEquals(SourceRoute.SERVER, report.applyTo(Settings()).rutrackerRoute)
    }

    private fun result(source: Source, url: String, route: SourceRoute, millis: Long) =
        ProbeResult(Probe(source, url, route), ProbeOutcome.Reachable(millis))

    private fun failed(source: Source, url: String, route: SourceRoute) =
        ProbeResult(Probe(source, url, route), ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION))

    private companion object {
        const val IS = "https://flibusta.is"
        const val SITE = "https://flibusta.site"
        const val RUTRACKER = ConnectionPlan.RUTRACKER_URL
    }
}
