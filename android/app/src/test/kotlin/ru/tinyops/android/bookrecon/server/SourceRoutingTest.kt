package ru.tinyops.android.bookrecon.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.SourceRoute

class SourceRoutingTest {
    private val approved = TunnelRoute(
        "https://b",
        "t",
        setOf("rutracker.org", "t-ru.org", "challenges.cloudflare.com", "flibusta.is", "flibusta.site", "books.example"),
    )

    @Test
    fun `both sources through the server keep the whole route`() {
        val settings = Settings(flibustaRoute = SourceRoute.SERVER, rutrackerRoute = SourceRoute.SERVER)

        assertEquals(approved, approved.forSources(settings))
    }

    @Test
    fun `direct sources leave no route`() {
        assertNull(approved.forSources(Settings()))
    }

    @Test
    fun `only flibusta keeps the flibusta hosts and the mirror from settings`() {
        val settings = Settings(flibustaRoute = SourceRoute.SERVER, flibustaBaseUrl = "https://books.example")

        val route = approved.forSources(settings)!!
        assertEquals(setOf("flibusta.is", "flibusta.site", "books.example"), route.hosts)
        assertTrue(route.serves("static.flibusta.site"))
        assertFalse(route.serves("rutracker.org"))
        assertFalse(route.carriesRuTracker)
    }

    @Test
    fun `only rutracker keeps everything but flibusta`() {
        val route = approved.forSources(Settings(rutrackerRoute = SourceRoute.SERVER))!!

        assertEquals(setOf("rutracker.org", "t-ru.org", "challenges.cloudflare.com", "books.example"), route.hosts)
        assertTrue(route.carriesRuTracker)
    }
}
