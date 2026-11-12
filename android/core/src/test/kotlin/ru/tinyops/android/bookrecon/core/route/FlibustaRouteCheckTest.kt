package ru.tinyops.android.bookrecon.core.route

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeProblem
import ru.tinyops.android.bookrecon.core.mirror.MirrorFallback
import ru.tinyops.android.bookrecon.core.mirror.MirrorSites

/** Answers from a table: host to addresses, and (mirror, address) to an outcome. */
class TableProber(
    private val dns: Map<String, List<String>>,
    private val outcomes: Map<Pair<String, String>, ProbeOutcome>,
) : AddressProber {
    val probed = mutableListOf<Pair<String, String>>()

    override suspend fun resolve(host: String): List<String> = dns[host].orEmpty()

    override suspend fun probe(baseUrl: String, address: String): ProbeOutcome {
        probed += baseUrl to address
        val outcome = outcomes[baseUrl to address] ?: ProbeOutcome.Failed(ProbeProblem.NO_CONNECTION)
        if (outcome is ProbeOutcome.Reachable) delay(outcome.millis)
        return outcome
    }
}

class FlibustaRouteCheckTest {
    private val site = "https://flibusta.site"
    private val isMirror = "https://flibusta.is"
    private val mirrors = listOf(site, isMirror)
    private val key = MirrorFallback.keyOf(MirrorSites.FLIBUSTA, mirrors)!!

    private val memory = RouteMemory()
    private val log = mutableListOf<String>()

    private val dns = mapOf(
        "flibusta.site" to listOf("5.61.56.204", "5.61.59.57", "5.45.87.206"),
        "flibusta.is" to listOf("179.43.150.83"),
    )

    private fun check(outcomes: Map<Pair<String, String>, ProbeOutcome>, resolver: Map<String, List<String>> = dns) =
        TableProber(resolver, outcomes).let { it to FlibustaRouteCheck(it, memory, log::add) }

    @Test
    fun `every address of every mirror is probed`() = runTest {
        val (prober, check) = check(emptyMap())

        check.run(mirrors)

        assertEquals(
            setOf(
                site to "5.61.56.204",
                site to "5.61.59.57",
                site to "5.45.87.206",
                isMirror to "179.43.150.83",
            ),
            prober.probed.toSet(),
        )
    }

    @Test
    fun `addresses are ordered fastest first, those that failed last`() = runTest {
        val (_, check) = check(
            mapOf(
                (site to "5.61.59.57") to ProbeOutcome.Reachable(900),
                (site to "5.45.87.206") to ProbeOutcome.Reachable(300),
            ),
        )

        check.run(mirrors)

        assertEquals(
            listOf("5.45.87.206", "5.61.59.57", "5.61.56.204"),
            memory.order("flibusta.site", dns.getValue("flibusta.site")),
        )
    }

    @Test
    fun `the mirror with the fastest address is remembered for the mirror list`() = runTest {
        val (_, check) = check(
            mapOf(
                (site to "5.45.87.206") to ProbeOutcome.Reachable(800),
                (isMirror to "179.43.150.83") to ProbeOutcome.Reachable(200),
            ),
        )

        val report = check.run(mirrors)

        assertEquals(isMirror, report.bestMirror)
        assertEquals(isMirror, memory.preferred(key))
    }

    @Test
    fun `nothing reachable keeps the remembered mirror and still notes the failures`() = runTest {
        memory.remember(key, site)
        val (_, check) = check(emptyMap())

        val report = check.run(mirrors)

        assertNull(report.bestMirror)
        assertEquals(site, memory.preferred(key))
        assertEquals(false, memory.snapshot().addresses.getValue("flibusta.is").getValue("179.43.150.83").works)
        assertTrue(log.any { "no Flibusta address answered" in it })
    }

    @Test
    fun `a host that does not resolve is not recorded`() = runTest {
        val (prober, check) = check(emptyMap(), resolver = mapOf("flibusta.site" to listOf("5.45.87.206")))

        check.run(mirrors)

        assertEquals(listOf(site to "5.45.87.206"), prober.probed)
        assertEquals(setOf("flibusta.site"), memory.snapshot().addresses.keys)
        assertTrue(log.any { "flibusta.is" in it && "could not be resolved" in it })
    }

    @Test
    fun `every result is logged in plain words`() = runTest {
        val (_, check) = check(mapOf((site to "5.45.87.206") to ProbeOutcome.Reachable(300)))

        check.run(mirrors)

        assertTrue(log.contains("Flibusta route check: https://flibusta.site at 5.45.87.206 answered in 300 ms"))
        assertTrue(log.contains("Flibusta route check: https://flibusta.is at 179.43.150.83 failed: no connection"))
        assertTrue(log.contains("Flibusta route check: https://flibusta.site is the best mirror"))
    }

    @Test
    fun `probes run in parallel`() = runTest {
        val (_, check) = check(
            mapOf(
                (site to "5.61.56.204") to ProbeOutcome.Reachable(1_000),
                (site to "5.61.59.57") to ProbeOutcome.Reachable(1_000),
                (site to "5.45.87.206") to ProbeOutcome.Reachable(1_000),
                (isMirror to "179.43.150.83") to ProbeOutcome.Reachable(1_000),
            ),
        )

        check.run(mirrors)

        assertEquals(1_000, testScheduler.currentTime)
    }
}
