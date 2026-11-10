package ru.tinyops.android.bookrecon.ui.nav

import ru.tinyops.android.bookrecon.core.domain.Settings

/**
 * Whether the app should open on the settings tab: with no root folder chosen at all, every
 * download fails until the user goes there. One chosen root is enough: a download of the
 * other kind then fails with a button that leads to settings.
 */
fun needsStorageSetup(settings: Settings): Boolean = settings.audioRoot == null && settings.booksRoot == null
