package ru.tinyops.android.bookrecon.core.port

import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import java.io.File

/**
 * A file downloaded from a source into a temporary location.
 *
 * @property tempFile the downloaded content; the caller moves or deletes it.
 * @property suggestedName file name with extension, from `Content-Disposition` or built from
 *   author and title. Not yet sanitized for the file system.
 */
data class DownloadedFile(
    val tempFile: File,
    val suggestedName: String,
)

/**
 * Flibusta HTML scraper. The base URL comes from settings on every call, so a changed
 * mirror applies without restart. All failures are
 * [ru.tinyops.android.bookrecon.core.domain.AppError].
 */
interface FlibustaSource {
    /**
     * Searches by title or author and returns up to [limit] books that can be downloaded,
     * each with its formats. When no book matches by title, books of up to three matching
     * authors are returned instead. [BookResult.alreadyDownloaded] is always false here.
     */
    suspend fun search(query: String, limit: Int): List<BookResult>

    /** Loads the book page. Throws NotFound for a missing book. */
    suspend fun details(bookId: String): FlibustaBookDetails

    /**
     * Downloads the book in [format] into a new temporary file inside [dir], streaming it to
     * disk. A zip with a single fb2 is unpacked. [dir] should be on the same file system as
     * the final root, so the caller can move the file by renaming it.
     */
    suspend fun download(bookId: String, format: Format, dir: File): DownloadedFile
}

/**
 * RuTracker client: login, search and `.torrent` download. The session cookie lives in the
 * shared cookie store, not in the client. All failures are
 * [ru.tinyops.android.bookrecon.core.domain.AppError].
 */
interface RuTrackerClient {
    /**
     * Logs in with the credentials from settings. Throws RuTrackerAuthRequired when they are
     * not set, RuTrackerLoginFailed when rejected, RuTrackerCaptcha when a captcha is shown.
     */
    suspend fun login()

    /** True when a session cookie is present; it may still turn out to be expired. */
    suspend fun isLoggedIn(): Boolean

    /**
     * True when the client logs in with the credentials from settings; false when it goes
     * through a server that uses its own RuTracker account.
     */
    suspend fun needsCredentials(): Boolean = true

    /**
     * Searches the forums of [kind] and returns up to [limit] topics that have seeders. An
     * expired session triggers one automatic [login] and a retry.
     */
    suspend fun search(query: String, kind: MediaKind, limit: Int): List<TorrentTopic>

    /** Downloads the `.torrent` of a topic; at most 10 MB, otherwise TorrentTooLarge. */
    suspend fun fetchTorrent(topicId: String): ByteArray
}
