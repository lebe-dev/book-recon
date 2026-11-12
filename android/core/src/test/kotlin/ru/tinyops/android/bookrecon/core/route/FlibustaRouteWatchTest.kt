package ru.tinyops.android.bookrecon.core.route

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.diagnostics.ProbeOutcome
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.mirror.MirrorFallback
import ru.tinyops.android.bookrecon.core.mirror.MirrorSites
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FlibustaRouteWatchTest {
    private val memory = RouteMemory()
    private val settings = FakeSettingsRepository(Settings())
    private val log = mutableListOf<String>()
    private val dns = mapOf(
        "flibusta.site" to listOf("5.45.87.206"),
        "flibusta.is" to listOf("179.43.150.83"),
        "example.org" to listOf("93.184.216.34"),
    )
    private val prober = TableProber(dns, mapOf(("https://flibusta.site" to "5.45.87.206") to ProbeOutcome.Reachable(500)))

    /** Checks that ran to the probes. */
    private val probes: Int get() = log.count { it.startsWith("Flibusta route check started") } - log.count { it.startsWith("Flibusta route check failed") }

    /** Runs the watch, which lives in the background scope, through its pending work (a check takes 0.5 s). */
    private fun TestScope.settle() {
        advanceTimeBy(5.seconds)
        runCurrent()
    }

    private fun TestScope.watch(ready: suspend () -> Unit = {}) = FlibustaRouteWatch(
        check = FlibustaRouteCheck(prober, memory),
        memory = memory,
        settings = settings,
        scope = backgroundScope,
        ready = ready,
        cooldown = 2.minutes,
        now = { testScheduler.currentTime },
        log = log::add,
    )

    @Test
    fun `the first start without any known route runs a check`() = runTest {
        watch().start()
        settle()

        assertEquals(1, probes)
        assertEquals("https://flibusta.site", memory.preferred(MirrorFallback.keyOf(MirrorSites.FLIBUSTA, Settings().flibustaUrls)!!))
        assertTrue(log.any { it.startsWith("Flibusta route check started") && "no route is known yet" in it })
    }

    @Test
    fun `a start with a known working address does not check`() = runTest {
        memory.addressWorked("flibusta.site", "5.45.87.206")

        watch().start()
        settle()

        assertEquals(0, probes)
    }

    @Test
    fun `a start with a remembered mirror does not check`() = runTest {
        memory.remember(MirrorFallback.keyOf(MirrorSites.FLIBUSTA, Settings().flibustaUrls)!!, "https://flibusta.is")

        watch().start()
        settle()

        assertEquals(0, probes)
    }

    @Test
    fun `only failures known for the mirrors still lead to a check at start`() = runTest {
        memory.addressFailed("flibusta.site", "5.45.87.206")

        watch().start()
        settle()

        assertEquals(1, probes)
    }

    @Test
    fun `routes learnt for another mirror list do not count`() = runTest {
        memory.addressWorked("rutracker.org", "1.2.3.4")
        memory.remember(MirrorFallback.keyOf(MirrorSites.FLIBUSTA, listOf("https://example.org"))!!, "https://example.org")

        watch().start()
        settle()

        assertEquals(1, probes)
    }

    @Test
    fun `the start waits for the stored routes before it decides`() = runTest {
        val restored = CompletableDeferred<Unit>()
        watch(ready = { restored.await() }).start()
        runCurrent()
        assertEquals(0, probes)

        memory.addressWorked("flibusta.site", "5.45.87.206")
        restored.complete(Unit)
        settle()

        assertEquals(0, probes)
    }

    @Test
    fun `nothing is checked while Flibusta is off or goes through the server`() = runTest {
        settings.update { it.copy(flibustaEnabled = false) }
        watch().start()
        settle()
        watch().reportTrouble().join()

        settings.update { it.copy(flibustaEnabled = true, flibustaRoute = SourceRoute.SERVER) }
        watch().start()
        settle()
        watch().reportTrouble().join()

        assertEquals(0, probes)
    }

    @Test
    fun `a newly chosen mirror without known routes is checked, a known one again is not`() = runTest {
        memory.addressWorked("flibusta.site", "5.45.87.206")
        watch().start()
        settle()
        assertEquals(0, probes)

        settings.update { it.copy(flibustaBaseUrl = "https://example.org") }
        settle()
        assertEquals(1, probes)
        assertEquals(listOf("https://example.org"), prober.probed.map { it.first }.distinct())

        settings.update { it.copy(flibustaBaseUrl = "") }
        settle()
        assertEquals(1, probes)
    }

    @Test
    fun `turning direct Flibusta on again checks when nothing is known`() = runTest {
        settings.update { it.copy(flibustaRoute = SourceRoute.SERVER) }
        watch().start()
        settle()
        assertEquals(0, probes)

        settings.update { it.copy(flibustaRoute = SourceRoute.DIRECT) }
        settle()

        assertEquals(1, probes)
    }

    @Test
    fun `other settings do not lead to a check`() = runTest {
        settings.update { it.copy(flibustaEnabled = false) }
        watch().start()
        settle()

        settings.update { it.copy(wifiOnly = false, searchCacheMinutes = 10) }
        settle()

        assertEquals(0, probes)
    }

    @Test
    fun `trouble runs a check even when routes are known`() = runTest {
        memory.addressWorked("flibusta.site", "5.45.87.206")

        watch().reportTrouble().join()

        assertEquals(1, probes)
        assertTrue(log.any { it.startsWith("Flibusta route check started") && "trouble" in it })
    }

    @Test
    fun `trouble soon after a check is ignored, after the cooldown it checks again`() = runTest {
        val watch = watch()
        watch.reportTrouble().join()
        advanceTimeBy(1.minutes)
        watch.reportTrouble().join()
        assertEquals(1, probes)

        advanceTimeBy(1.minutes + 1.seconds)
        watch.reportTrouble().join()
        assertEquals(2, probes)
    }

    @Test
    fun `trouble during a running check does not start a second one`() = runTest {
        val watch = watch()
        watch.start()
        watch.reportTrouble()
        settle()

        assertEquals(1, probes)
    }

    @Test
    fun `a check uses the mirrors of the current settings`() = runTest {
        settings.update { it.copy(flibustaBaseUrl = "https://example.org") }

        watch().reportTrouble().join()

        assertEquals(listOf("https://example.org"), prober.probed.map { it.first }.distinct())
    }

    @Test
    fun `a failing check is logged and the watch keeps working`() = runTest {
        val broken = object : AddressProber {
            override suspend fun resolve(host: String): List<String> = error("resolver broke")
            override suspend fun probe(baseUrl: String, address: String): ProbeOutcome = error("unused")
        }
        val watch = FlibustaRouteWatch(
            check = FlibustaRouteCheck(broken, memory),
            memory = memory,
            settings = settings,
            scope = backgroundScope,
            cooldown = 0.seconds,
            now = { testScheduler.currentTime },
            log = log::add,
        )

        watch.start()
        settle()
        watch.reportTrouble().join()

        assertEquals(2, log.count { it.startsWith("Flibusta route check failed") })
    }
}
