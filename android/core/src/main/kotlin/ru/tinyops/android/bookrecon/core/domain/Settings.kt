package ru.tinyops.android.bookrecon.core.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * User settings. The RuTracker password is kept apart, encrypted, and is not part of this
 * class so it never ends up in logs or state dumps.
 *
 * @property audioRoot document tree URI of the audiobooks root picked by the user, null until chosen.
 * @property booksRoot document tree URI of the e-books root picked by the user, null until chosen.
 * @property rutrackerLogin RuTracker username, empty when not set.
 * @property flibustaBaseUrl Flibusta mirror without a trailing slash; empty means automatic:
 *   the known mirrors ([FLIBUSTA_MIRRORS]) are used under the hood, see [flibustaEffectiveUrl].
 * @property flibustaTimeoutSeconds how long to wait for a Flibusta response (headers, and then
 *   each chunk of a body) before a request fails, [MIN_FLIBUSTA_TIMEOUT_SECONDS]..[MAX_FLIBUSTA_TIMEOUT_SECONDS].
 * @property flibustaEnabled search Flibusta.
 * @property rutrackerEnabled search RuTracker.
 * @property wifiOnly run downloads on unmetered networks only.
 * @property maxConcurrent downloads running at the same time, 1..[MAX_CONCURRENT_LIMIT].
 * @property seedHours keep seeding on Wi-Fi and charger for this many hours after a torrent
 *   completes; 0 turns seeding after download off.
 * @property noSeedersRetryMinutes while the app is open, a torrent that failed for lack of
 *   seeders (or stalled) is retried this many minutes later; 0 turns retries off.
 * @property searchCacheMinutes how long the results of a search are reused when the same query
 *   is repeated; 0 turns the cache off.
 * @property sendErrorReports send crashes and unexpected errors to Sentry; off until the user
 *   turns it on.
 * @property logInfo show INFO, warning and error lines on the app log screen.
 * @property logDebug show DEBUG lines (requests, queue steps, torrent progress) on the app log screen.
 * @property colorTheme color theme of the app.
 * @property darkMode light or dark app, or following the system.
 * @property cyrillicAppName show "Бук Ракун" under the launcher icon instead of "Book Recon".
 * @property flibustaRoute reach Flibusta directly or through the Book Recon server.
 * @property rutrackerRoute reach RuTracker (search, `.torrent` and tracker announces) directly
 *   or through the Book Recon server.
 * @property autoCheckUpdates look for a new release in the background at every app start;
 *   off until the user turns it on.
 * @property skippedUpdateVersion a release (`0.10.0`) the user chose to skip: the start check
 *   does not offer it again, a check by hand still does. Empty when none is skipped.
 */
data class Settings(
    val audioRoot: String? = null,
    val booksRoot: String? = null,
    val rutrackerLogin: String = "",
    val flibustaBaseUrl: String = DEFAULT_FLIBUSTA_BASE_URL,
    val flibustaTimeoutSeconds: Int = DEFAULT_FLIBUSTA_TIMEOUT_SECONDS,
    val flibustaEnabled: Boolean = true,
    val rutrackerEnabled: Boolean = true,
    val preferredFormat: Format = Format.EPUB,
    val wifiOnly: Boolean = true,
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val seedHours: Int = 0,
    val noSeedersRetryMinutes: Int = DEFAULT_NO_SEEDERS_RETRY_MINUTES,
    val searchCacheMinutes: Int = DEFAULT_SEARCH_CACHE_MINUTES,
    val sendErrorReports: Boolean = false,
    val logInfo: Boolean = true,
    val logDebug: Boolean = false,
    val colorTheme: ColorTheme = ColorTheme.BLUEBERRY,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val torrentPeersThroughServer: Boolean = false,
    val cyrillicAppName: Boolean = false,
    val flibustaRoute: SourceRoute = SourceRoute.DIRECT,
    val rutrackerRoute: SourceRoute = SourceRoute.DIRECT,
    val autoCheckUpdates: Boolean = false,
    val skippedUpdateVersion: String = "",
) {
    /** The Flibusta mirror requests go to: the one set by the user, or the first known one when automatic. */
    val flibustaEffectiveUrl: String
        get() = flibustaBaseUrl.ifEmpty { FLIBUSTA_MIRRORS.first() }

    /**
     * Mirrors to try after [flibustaEffectiveUrl] fails. A known mirror counts as automatic too:
     * older versions saved `flibusta.is` as the default, and the connection test saves the best one.
     * Only an address that is not a known mirror is used alone.
     */
    val flibustaFallbackUrls: List<String>
        get() = if (flibustaBaseUrl.isEmpty() || flibustaBaseUrl in FLIBUSTA_MIRRORS) FLIBUSTA_MIRRORS else emptyList()

    /** Every Flibusta mirror a request may go to, in the order they are tried. */
    val flibustaUrls: List<String>
        get() = (listOf(flibustaEffectiveUrl) + flibustaFallbackUrls).distinct()

    /**
     * True when [host] is one of [flibustaUrls] or a subdomain of one: such requests wait
     * [flibustaTimeoutSeconds] for an answer.
     */
    fun isFlibustaHost(host: String): Boolean = flibustaUrls.any { url ->
        val mirror = hostOf(url)
        mirror.isNotEmpty() && (host == mirror || host.endsWith(".$mirror"))
    }

    /**
     * Deadline of one Flibusta page on one mirror, the book pages fetched for a search
     * included: twice the timeout, at least [MIN_FLIBUSTA_PAGE_DEADLINE].
     */
    val flibustaPageDeadline: Duration
        get() = maxOf(MIN_FLIBUSTA_PAGE_DEADLINE, (flibustaTimeoutSeconds * 2).seconds)

    /** Deadline of a whole Flibusta search: a [flibustaPageDeadline] for every mirror tried. */
    val flibustaSearchDeadline: Duration
        get() = flibustaPageDeadline * flibustaUrls.size

    /** Route chosen for [source]. */
    fun routeFor(source: Source): SourceRoute = when (source) {
        Source.FLIBUSTA -> flibustaRoute
        Source.RUTRACKER -> rutrackerRoute
    }

    /** True when the root folder for [kind] is chosen. */
    fun hasRoot(kind: MediaKind): Boolean = rootFor(kind) != null

    /** Kinds of books whose root folder is chosen: only these can be searched and downloaded. */
    val chosenKinds: Set<MediaKind>
        get() = MediaKind.entries.filterTo(mutableSetOf()) { hasRoot(it) }

    /** Root folder for downloads of the given kind, null until chosen. */
    fun rootFor(kind: MediaKind): String? = when (kind) {
        MediaKind.BOOK -> booksRoot
        MediaKind.AUDIO -> audioRoot
    }

    companion object {
        /** No mirror chosen: the known ones are used. */
        const val DEFAULT_FLIBUSTA_BASE_URL = ""

        /** Known Flibusta mirrors, the first one is used when none is set; the connection test tries them all. */
        val FLIBUSTA_MIRRORS = listOf("https://flibusta.site", "https://flibusta.is")
        const val DEFAULT_FLIBUSTA_TIMEOUT_SECONDS = 20
        const val MIN_FLIBUSTA_TIMEOUT_SECONDS = 5
        const val MAX_FLIBUSTA_TIMEOUT_SECONDS = 120
        val MIN_FLIBUSTA_PAGE_DEADLINE: Duration = 30.seconds
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MAX_CONCURRENT_LIMIT = 4
        const val DEFAULT_NO_SEEDERS_RETRY_MINUTES = 15
        const val DEFAULT_SEARCH_CACHE_MINUTES = 30
    }
}
