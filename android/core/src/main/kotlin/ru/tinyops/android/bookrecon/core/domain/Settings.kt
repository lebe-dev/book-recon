package ru.tinyops.android.bookrecon.core.domain

/**
 * User settings. The RuTracker password is kept apart, encrypted, and is not part of this
 * class so it never ends up in logs or state dumps.
 *
 * @property audioRoot absolute path of the audiobooks root, null until chosen.
 * @property booksRoot absolute path of the e-books root, null until chosen.
 * @property rutrackerLogin RuTracker username, empty when not set.
 * @property flibustaBaseUrl Flibusta mirror without a trailing slash.
 * @property wifiOnly run downloads on unmetered networks only.
 * @property maxConcurrent downloads running at the same time, 1..[MAX_CONCURRENT_LIMIT].
 * @property seedHours keep seeding on Wi-Fi and charger for this many hours after a torrent
 *   completes; 0 turns seeding after download off.
 * @property noSeedersRetryMinutes while the app is open, a torrent that failed for lack of
 *   seeders (or stalled) is retried this many minutes later; 0 turns retries off.
 * @property sendErrorReports send crashes and unexpected errors to Sentry; off until the user
 *   turns it on.
 * @property logInfo record INFO, warning and error lines in the app log.
 * @property logDebug record DEBUG lines (requests, queue steps, torrent progress) in the app log.
 * @property colorTheme color theme of the app.
 * @property darkMode light or dark app, or following the system.
 */
data class Settings(
    val audioRoot: String? = null,
    val booksRoot: String? = null,
    val rutrackerLogin: String = "",
    val flibustaBaseUrl: String = DEFAULT_FLIBUSTA_BASE_URL,
    val preferredFormat: Format = Format.EPUB,
    val wifiOnly: Boolean = false,
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val seedHours: Int = 0,
    val noSeedersRetryMinutes: Int = DEFAULT_NO_SEEDERS_RETRY_MINUTES,
    val sendErrorReports: Boolean = false,
    val logInfo: Boolean = true,
    val logDebug: Boolean = false,
    val colorTheme: ColorTheme = ColorTheme.BLUEBERRY,
    val darkMode: DarkMode = DarkMode.SYSTEM,
) {
    /** Root folder for downloads of the given kind, null until chosen. */
    fun rootFor(kind: MediaKind): String? = when (kind) {
        MediaKind.BOOK -> booksRoot
        MediaKind.AUDIO -> audioRoot
    }

    companion object {
        const val DEFAULT_FLIBUSTA_BASE_URL = "https://flibusta.is"
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MAX_CONCURRENT_LIMIT = 4
        const val DEFAULT_NO_SEEDERS_RETRY_MINUTES = 15
    }
}
