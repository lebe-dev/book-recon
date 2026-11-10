package ru.tinyops.android.bookrecon.core.domain

import java.time.Instant

/** What the user asked to download. */
sealed interface DownloadRequest {
    val kind: MediaKind
    val source: Source
    val sourceId: String
    val title: String

    /** One Flibusta book in one format. */
    data class FlibustaBook(
        val bookId: String,
        override val title: String,
        val author: String,
        val format: Format,
    ) : DownloadRequest {
        override val kind: MediaKind get() = MediaKind.BOOK
        override val source: Source get() = Source.FLIBUSTA
        override val sourceId: String get() = bookId
    }

    /**
     * Selected files of a RuTracker torrent.
     *
     * @property torrentBytes the `.torrent` file as fetched from the tracker.
     * @property selectedIndexes [TorrentFile.index] values to download; never empty.
     * @property targetName folder name for an audiobook (already edited by the user); for
     *   e-books it is only a display name, the files go flat into the books root.
     */
    class TorrentSelection(
        val topicId: String,
        override val kind: MediaKind,
        override val title: String,
        val torrentBytes: ByteArray,
        val selectedIndexes: Set<Int>,
        val targetName: String,
    ) : DownloadRequest {
        override val source: Source get() = Source.RUTRACKER
        override val sourceId: String get() = topicId

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TorrentSelection) return false
            return topicId == other.topicId &&
                kind == other.kind &&
                title == other.title &&
                torrentBytes.contentEquals(other.torrentBytes) &&
                selectedIndexes == other.selectedIndexes &&
                targetName == other.targetName
        }

        override fun hashCode(): Int {
            var result = topicId.hashCode()
            result = 31 * result + kind.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + torrentBytes.contentHashCode()
            result = 31 * result + selectedIndexes.hashCode()
            result = 31 * result + targetName.hashCode()
            return result
        }

        override fun toString(): String =
            "TorrentSelection(topicId=$topicId, kind=$kind, title=$title, " +
                "torrentBytes=${torrentBytes.size} bytes, selected=${selectedIndexes.size}, " +
                "targetName=$targetName)"
    }
}

/**
 * A download record: an entry of the queue while active and of the library once its files
 * are placed (completed or seeding; a seeding download is in both).
 *
 * @property id stable job ID; also names the private staging folder of the download.
 * @property sourceId Flibusta book ID or RuTracker topic ID.
 * @property format chosen format for Flibusta books, null for torrents.
 * @property targetName audiobook folder name or e-book display name chosen before download.
 * @property queuePosition 1-based position among QUEUED downloads, null otherwise.
 * @property files selected torrent files, empty for Flibusta.
 * @property paths paths, relative to the root of [kind], of what was placed into the roots:
 *   the audiobook folder, or every e-book file. Records of version 0.9.0 and older hold
 *   absolute paths instead. Empty until the download completes.
 * @property size bytes on disk once completed, or the expected size before that.
 * @property seedUntil when seeding stops; set only while [status] is SEEDING.
 * @property fileMissing computed when read: the download is in the library (COMPLETED or
 *   SEEDING, see [DownloadStatus.isInLibrary]) but none of [paths] exists on disk any more.
 *   Never stored.
 */
data class Download(
    val id: String,
    val kind: MediaKind,
    val source: Source,
    val sourceId: String,
    val title: String,
    val author: String,
    val format: Format? = null,
    val targetName: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: DownloadProgress = DownloadProgress(),
    val queuePosition: Int? = null,
    val files: List<TorrentFile> = emptyList(),
    val paths: List<String> = emptyList(),
    val size: Long = 0,
    val error: AppError? = null,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    val seedUntil: Instant? = null,
    val fileMissing: Boolean = false,
)
