package ru.tinyops.android.bookrecon.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseUrlTest {
    @Test
    fun addsHttpsWhenSchemeIsMissing() {
        assertEquals("https://flibusta.site", normalizeBaseUrl(" flibusta.site/ "))
        assertEquals("https://books.example.com:8081", normalizeBaseUrl("books.example.com:8081"))
    }

    @Test
    fun keepsAnExplicitScheme() {
        assertEquals("http://flibusta.lib", normalizeBaseUrl(" http://flibusta.lib/ "))
        assertEquals("ftp://flibusta.is", normalizeBaseUrl("ftp://flibusta.is"))
    }

    @Test
    fun blankStaysEmpty() {
        assertEquals("", normalizeBaseUrl("  "))
    }

    @Test
    fun lowercasesSchemeAndHostButKeepsThePath() {
        assertEquals("https://flibusta.is", normalizeBaseUrl("HTTPS://Flibusta.IS/"))
        assertEquals("https://flibusta.site", normalizeBaseUrl("Flibusta.Site"))
        assertEquals("https://books.example.com:8081/Lib", normalizeBaseUrl("Books.Example.com:8081/Lib/"))
    }

    @Test
    fun trimsTabsAndNewlinesAroundTheAddress() {
        assertEquals("https://flibusta.is", normalizeBaseUrl("\t https://flibusta.is \n"))
    }

    @Test
    fun typedHttpsOfAKnownMirrorStaysAKnownMirror() {
        val typed = Settings(flibustaBaseUrl = normalizeBaseUrl(" HTTPS://Flibusta.Is/ "))
        assertEquals(Settings.FLIBUSTA_MIRRORS, typed.flibustaFallbackUrls)
    }
}
