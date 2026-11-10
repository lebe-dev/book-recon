package ru.tinyops.android.bookrecon.ui.nav

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Settings

class FirstRunTest {
    @Test
    fun opensSettingsWhenNoRootIsChosen() {
        assertTrue(needsStorageSetup(Settings()))
    }

    @Test
    fun oneChosenRootIsEnough() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3A"
        assertFalse(needsStorageSetup(Settings(booksRoot = "${tree}Books")))
        assertFalse(needsStorageSetup(Settings(audioRoot = "${tree}Audiobooks")))
    }
}
