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
}
