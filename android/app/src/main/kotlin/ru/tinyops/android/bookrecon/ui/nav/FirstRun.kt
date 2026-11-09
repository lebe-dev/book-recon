package ru.tinyops.android.bookrecon.ui.nav

import ru.tinyops.android.bookrecon.core.domain.Settings

/**
 * Whether the app should open on the settings tab: without all-files access, or with no root
 * folder chosen at all, every download fails until the user goes there. One chosen root is
 * enough: a download of the other kind then fails with a button that leads to settings.
 */
fun needsStorageSetup(hasAllFilesAccess: Boolean, settings: Settings): Boolean {
    if (!hasAllFilesAccess) return true
    return settings.audioRoot == null && settings.booksRoot == null
}
