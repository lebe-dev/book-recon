package ru.tinyops.android.bookrecon.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
