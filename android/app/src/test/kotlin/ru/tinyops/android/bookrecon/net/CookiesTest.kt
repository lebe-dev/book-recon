package ru.tinyops.android.bookrecon.net

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CookiesTest {
    @Test
    fun `cookie value is read from a cookie header`() {
        val header = "bb_ssl=1; bb_session=0-123-abc; cf_clearance=x=y"

        assertEquals("0-123-abc", cookieValue(header, "bb_session"))
        assertEquals("x=y", cookieValue(header, "cf_clearance"))
        assertNull(cookieValue(header, "bb"))
        assertNull(cookieValue(null, "bb_session"))
        assertNull(cookieValue("", "bb_session"))
    }

    @Test
    fun `only a new non-empty cookie counts as fresh`() {
        assertTrue(isFreshCookie(before = null, now = "a"))
        assertTrue(isFreshCookie(before = "a", now = "b"))
        assertFalse(isFreshCookie(before = "a", now = "a"))
        assertFalse(isFreshCookie(before = "a", now = null))
        assertFalse(isFreshCookie(before = null, now = ""))
    }

    @Test
    fun `cookie jar writes to the shared store and reads back from it`() {
        val store = InMemoryCookieStore()
        val jar = SharedCookieJar(store)
        val url = "https://rutracker.org/forum/login.php".toHttpUrl()

        jar.saveFromResponse(url, listOf(Cookie.parse(url, "bb_session=abc; path=/forum/; HttpOnly")!!))
        // A cookie the WebView set is visible to OkHttp as well.
        store.put("https://rutracker.org/", "cf_clearance", "solved")

        val loaded = jar.loadForRequest("https://rutracker.org/forum/tracker.php".toHttpUrl())

        assertEquals(mapOf("bb_session" to "abc", "cf_clearance" to "solved"), loaded.associate { it.name to it.value })
        assertEquals(1, store.flushes)
    }

    @Test
    fun `cookie jar skips flush when the response sets no cookies`() {
        val store = InMemoryCookieStore()

        SharedCookieJar(store).saveFromResponse("https://flibusta.is/".toHttpUrl(), emptyList())

        assertEquals(0, store.flushes)
        assertTrue(SharedCookieJar(store).loadForRequest("https://flibusta.is/".toHttpUrl()).isEmpty())
    }
}
