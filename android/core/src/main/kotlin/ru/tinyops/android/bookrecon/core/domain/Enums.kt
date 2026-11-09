package ru.tinyops.android.bookrecon.core.domain

/**
 * E-book file format.
 *
 * @property extension file extension without the leading dot, also used as the Flibusta
 *   download path segment (`/b/<id>/<extension>`).
 */
enum class Format(val extension: String) {
    EPUB("epub"),
    FB2("fb2"),
    MOBI("mobi"),
    PDF("pdf"),
    DJVU("djvu");

    companion object {
        /** Finds a format by extension, ignoring case and a leading dot; null if unknown. */
        fun fromExtension(extension: String): Format? {
            val normalized = extension.trim().removePrefix(".").lowercase()
            return entries.firstOrNull { it.extension == normalized }
        }
    }
}

/** Where a book or a torrent comes from. */
enum class Source {
    FLIBUSTA,
    RUTRACKER,
}

/** What a download contains: e-book files or an audiobook. */
enum class MediaKind {
    BOOK,
    AUDIO,
}

/**
 * Lifecycle of a [Download].
 *
 * QUEUED → DOWNLOADING → COMPLETED, or DOWNLOADING → SEEDING → COMPLETED when seeding after
 * download is enabled. FAILED and CANCELLED are terminal; a failed download can be retried,
 * which puts it back to QUEUED.
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    SEEDING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** True while the download occupies the queue screen. */
    val isActive: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == SEEDING
}
