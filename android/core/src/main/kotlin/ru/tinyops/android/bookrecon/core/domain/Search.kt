package ru.tinyops.android.bookrecon.core.domain

/**
 * One item of the search screen: a Flibusta book or a RuTracker topic.
 *
 * Flibusta results fill [formats]; RuTracker results fill [size], [seeders] and [peers] and
 * leave [formats] empty.
 *
 * @property sourceId Flibusta book ID or RuTracker topic ID, as digits.
 * @property kind what the result contains; Flibusta results are always [MediaKind.BOOK].
 * @property size total torrent size in bytes, null for Flibusta.
 * @property alreadyDownloaded a completed download with the same [source] and [sourceId]
 *   exists and its files are still on disk.
 */
data class BookResult(
    val source: Source,
    val sourceId: String,
    val title: String,
    val author: String,
    val kind: MediaKind = MediaKind.BOOK,
    val formats: List<Format> = emptyList(),
    val size: Long? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val alreadyDownloaded: Boolean = false,
)

/**
 * A Flibusta book page.
 *
 * @property formats formats offered on the page, in page order; never empty for a book that
 *   can be downloaded.
 */
data class FlibustaBookDetails(
    val id: String,
    val title: String,
    val author: String,
    val formats: List<Format>,
)

/**
 * A RuTracker topic (a distribution) from the search results.
 *
 * @property topicId numeric topic ID, used in `viewtopic.php?t=` and `dl.php?t=`.
 * @property size total size in bytes as shown by the tracker.
 */
data class TorrentTopic(
    val topicId: String,
    val title: String,
    val size: Long,
    val seeders: Int,
    val peers: Int,
    val kind: MediaKind,
)
