package ru.tinyops.android.bookrecon.ui.nav

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Settings

class FirstRunTest {
    @Test
    fun freshInstallNeedsSetup() {
        assertTrue(needsStorageSetup(hasAllFilesAccess = false, settings = Settings()))
    }

    @Test
    fun accessWithoutRootsNeedsSetup() {
        assertTrue(needsStorageSetup(hasAllFilesAccess = true, settings = Settings()))
    }

    @Test
    fun rootsWithoutAccessNeedSetup() {
        val settings = Settings(audioRoot = "/storage/emulated/0/Audiobooks", booksRoot = "/storage/emulated/0/Books")
        assertTrue(needsStorageSetup(hasAllFilesAccess = false, settings = settings))
    }

    @Test
    fun accessAndOneRootIsEnough() {
        assertFalse(needsStorageSetup(hasAllFilesAccess = true, settings = Settings(booksRoot = "/storage/emulated/0/Books")))
        assertFalse(needsStorageSetup(hasAllFilesAccess = true, settings = Settings(audioRoot = "/storage/emulated/0/Audiobooks")))
    }
}
