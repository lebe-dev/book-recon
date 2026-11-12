package ru.tinyops.android.bookrecon.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppVersionTest {
    @Test
    fun parsesTagsWithAndWithoutPrefix() {
        assertEquals(AppVersion(0, 9, 1), AppVersion.parse("v0.9.1"))
        assertEquals(AppVersion(0, 8, 0), AppVersion.parse("0.8.0"))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse(" V1.2 "))
    }

    @Test
    fun rejectsWhatIsNotAVersion() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("v1.x.0"))
        assertNull(AppVersion.parse("1.2.3.4"))
        assertNull(AppVersion.parse("1.2.3-beta"))
    }

    @Test
    fun comparesPartByPartNotAsText() {
        assertTrue(AppVersion(0, 10, 0) > AppVersion(0, 9, 9))
        assertTrue(AppVersion(1, 0, 0) > AppVersion(0, 99, 99))
        assertTrue(AppVersion(0, 9, 2) > AppVersion(0, 9, 1))
        assertEquals(0, AppVersion(0, 9, 1).compareTo(AppVersion(0, 9, 1)))
    }

    @Test
    fun printsWithoutPrefix() {
        assertEquals("0.9.1", AppVersion(0, 9, 1).toString())
    }
}
