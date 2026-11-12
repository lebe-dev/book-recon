package ru.tinyops.android.bookrecon.core.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteMemoryTest {
    private var time = 1_000L
    private val saved = mutableListOf<RouteSnapshot>()
    private val memory = RouteMemory(now = { time }, onChange = { saved += it })

    private val dns = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")

    private fun tick() {
        time += 1_000
    }

    @Test
    fun `addresses of an unknown host keep the order the resolver gave`() {
        assertEquals(dns, memory.order("flibusta.site", dns))
    }

    @Test
    fun `the address that answered last goes first`() {
        memory.addressWorked("flibusta.site", "10.0.0.3")
        tick()
        memory.addressWorked("flibusta.site", "10.0.0.2")

        assertEquals(listOf("10.0.0.2", "10.0.0.3", "10.0.0.1"), memory.order("flibusta.site", dns))
    }

    @Test
    fun `failed addresses go last, the one that failed longest ago first among them`() {
        memory.addressFailed("flibusta.site", "10.0.0.1")
        tick()
        memory.addressFailed("flibusta.site", "10.0.0.2")

        assertEquals(listOf("10.0.0.3", "10.0.0.1", "10.0.0.2"), memory.order("flibusta.site", dns))
    }

    @Test
    fun `an address that works again after a failure is preferred again`() {
        memory.addressFailed("flibusta.site", "10.0.0.1")
        tick()
        memory.addressWorked("flibusta.site", "10.0.0.1")

        assertEquals(dns, memory.order("flibusta.site", dns))
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), memory.order("flibusta.site", listOf("10.0.0.2", "10.0.0.1")))
    }

    @Test
    fun `hosts do not share what they learnt`() {
        memory.addressWorked("flibusta.site", "10.0.0.3")

        assertEquals(dns, memory.order("rutracker.org", dns))
    }

    @Test
    fun `only changes of the preferred route are saved`() {
        memory.addressWorked("flibusta.site", "10.0.0.1")
        tick()
        memory.addressWorked("flibusta.site", "10.0.0.1")
        tick()
        memory.addressFailed("flibusta.site", "10.0.0.1")
        tick()
        memory.addressFailed("flibusta.site", "10.0.0.1")
        memory.remember("Flibusta https://flibusta.site", "https://flibusta.is")
        memory.remember("Flibusta https://flibusta.site", "https://flibusta.is")

        assertEquals(3, saved.size)
        assertEquals("https://flibusta.is", saved.last().mirrors["Flibusta https://flibusta.site"])
    }

    @Test
    fun `mirrors are remembered per key`() {
        memory.remember("RuTracker https://rutracker.org", "https://rutracker.net")

        assertEquals("https://rutracker.net", memory.preferred("RuTracker https://rutracker.org"))
        assertNull(memory.preferred("Flibusta https://flibusta.site"))
    }

    @Test
    fun `a restored snapshot fills in what was not learnt since the start`() {
        memory.addressWorked("flibusta.site", "10.0.0.2")
        memory.restore(
            RouteSnapshot(
                mirrors = mapOf("Flibusta https://flibusta.site" to "https://flibusta.is"),
                addresses = mapOf(
                    "flibusta.site" to mapOf("10.0.0.2" to AddressRecord(failedAt = 900)),
                    "rutracker.org" to mapOf("10.0.0.3" to AddressRecord(okAt = 900)),
                ),
            ),
        )

        assertEquals(listOf("10.0.0.2", "10.0.0.1", "10.0.0.3"), memory.order("flibusta.site", dns))
        assertEquals(listOf("10.0.0.3", "10.0.0.1", "10.0.0.2"), memory.order("rutracker.org", dns))
        assertEquals("https://flibusta.is", memory.preferred("Flibusta https://flibusta.site"))
    }

    @Test
    fun `only the most recently used hosts are kept`() {
        repeat(RouteMemory.MAX_HOSTS + 5) { i ->
            tick()
            memory.addressWorked("host$i.test", "10.0.0.1")
        }

        val hosts = memory.snapshot().addresses.keys
        assertEquals(RouteMemory.MAX_HOSTS, hosts.size)
        assertEquals(false, "host0.test" in hosts)
        assertEquals(true, "host${RouteMemory.MAX_HOSTS + 4}.test" in hosts)
    }

    @Test
    fun `checked addresses are ordered as measured and saved once`() {
        memory.addressWorked("flibusta.site", "10.0.0.1")
        saved.clear()

        memory.addressesChecked("flibusta.site", fastestFirst = listOf("10.0.0.3", "10.0.0.2"), failed = listOf("10.0.0.1"))

        assertEquals(listOf("10.0.0.3", "10.0.0.2", "10.0.0.1"), memory.order("flibusta.site", dns))
        assertEquals(1, saved.size)
    }

    @Test
    fun `a working address of any of the hosts counts as known`() {
        memory.addressFailed("flibusta.site", "10.0.0.1")
        assertEquals(false, memory.knowsWorkingAddress(listOf("flibusta.site", "flibusta.is")))

        memory.addressWorked("flibusta.is", "10.0.0.2")
        assertEquals(true, memory.knowsWorkingAddress(listOf("flibusta.site", "flibusta.is")))
        assertEquals(false, memory.knowsWorkingAddress(listOf("rutracker.org")))
    }
}
