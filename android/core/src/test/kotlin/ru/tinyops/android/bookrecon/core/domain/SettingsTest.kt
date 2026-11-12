package ru.tinyops.android.bookrecon.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SettingsTest {
    @Test
    fun `a kind is usable once its root is chosen`() {
        val settings = Settings(audioRoot = "content://tree/audio")

        assertTrue(settings.hasRoot(MediaKind.AUDIO))
        assertFalse(settings.hasRoot(MediaKind.BOOK))
        assertEquals(setOf(MediaKind.AUDIO), settings.chosenKinds)
    }

    @Test
    fun `nothing is usable without roots`() {
        assertEquals(emptySet<MediaKind>(), Settings().chosenKinds)
        assertEquals(
            MediaKind.entries.toSet(),
            Settings(audioRoot = "content://tree/audio", booksRoot = "content://tree/books").chosenKinds,
        )
    }

    @Test
    fun `an empty address falls back to all known mirrors`() {
        assertEquals(Settings.FLIBUSTA_MIRRORS, Settings().flibustaFallbackUrls)
    }

    @Test
    fun `a known mirror saved as the address still falls back to the others`() {
        val settings = Settings(flibustaBaseUrl = "https://flibusta.is")

        assertEquals(Settings.FLIBUSTA_MIRRORS, settings.flibustaFallbackUrls)
        assertEquals("https://flibusta.is", settings.flibustaEffectiveUrl)
    }

    @Test
    fun `an address of the user's own is the only one used`() {
        assertEquals(emptyList<String>(), Settings(flibustaBaseUrl = "https://example.org").flibustaFallbackUrls)
    }

    @Test
    fun `every mirror a search may reach counts as Flibusta, subdomains included`() {
        val settings = Settings(flibustaBaseUrl = "https://flibusta.is")

        assertTrue(settings.isFlibustaHost("flibusta.is"))
        assertTrue(settings.isFlibustaHost("flibusta.site"))
        assertTrue(settings.isFlibustaHost("static.flibusta.site"))
        assertFalse(settings.isFlibustaHost("rutracker.org"))
        assertFalse(settings.isFlibustaHost("notflibusta.site"))
    }

    @Test
    fun `an address of the user's own is the only Flibusta host`() {
        val settings = Settings(flibustaBaseUrl = "https://example.org:8443/lib")

        assertTrue(settings.isFlibustaHost("example.org"))
        assertFalse(settings.isFlibustaHost("flibusta.site"))
    }

    @Test
    fun `the page deadline follows the timeout and never drops below half a minute`() {
        assertEquals(40.seconds, Settings(flibustaTimeoutSeconds = 20).flibustaPageDeadline)
        assertEquals(120.seconds, Settings(flibustaTimeoutSeconds = 60).flibustaPageDeadline)
        assertEquals(30.seconds, Settings(flibustaTimeoutSeconds = 5).flibustaPageDeadline)
    }

    @Test
    fun `the search deadline covers every mirror tried`() {
        assertEquals(120.seconds, Settings(flibustaTimeoutSeconds = 30).flibustaSearchDeadline)
        assertEquals(60.seconds, Settings(flibustaBaseUrl = "https://example.org", flibustaTimeoutSeconds = 30).flibustaSearchDeadline)
    }
}
