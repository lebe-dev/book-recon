package ru.tinyops.android.bookrecon.core.naming

import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind

private const val MAX_ATTEMPTS = 999
private const val MAX_AUTHOR_LENGTH = 40

/** [bookTargetName] for a Flibusta format. */
fun bookTargetName(title: String, author: String, format: Format, exists: (String) -> Boolean): String =
    bookTargetName(title, author, format.extension, exists)

/**
 * File name for an e-book in the flat books root: "Title.ext"; if taken, "Title (Author).ext";
 * then "Title (Author) (2).ext", "(3)" and so on. Without an author the numbers follow the
 * title directly. Parts are sanitized and the stem is shortened so the suffix and the
 * extension always fit [MAX_FILE_NAME_LENGTH].
 *
 * @param extension extension without the dot, may be compound such as `fb2.zip`.
 * @param exists tells whether a file name is already taken in the root.
 * @throws AppError.StorageFailed when no free name is found.
 */
fun bookTargetName(title: String, author: String, extension: String, exists: (String) -> Boolean): String {
    val ext = extension.trim().removePrefix(".").lowercase()
    val dotExt = if (ext.isEmpty()) "" else ".$ext"
    val cleanAuthor = author.takeIf { it.isNotBlank() }?.let { sanitizeFileName(it, MAX_AUTHOR_LENGTH) }

    val suffixes = sequence {
        yield("")
        if (cleanAuthor != null) yield(" ($cleanAuthor)")
        for (n in 2..MAX_ATTEMPTS) yield(if (cleanAuthor != null) " ($cleanAuthor) ($n)" else " ($n)")
    }
    return suffixes.map { fit(title, it + dotExt) }.firstOrNull { !exists(it) }
        ?: throw AppError.StorageFailed("no free file name for the book")
}

/**
 * Folder name for an audiobook in the audiobooks root: [folderName] sanitized, or with
 * " (2)", " (3)"… when taken.
 *
 * @param exists tells whether a name is already taken in the root.
 * @throws AppError.StorageFailed when no free name is found.
 */
fun audioFolderName(folderName: String, exists: (String) -> Boolean): String {
    val suffixes = sequence {
        yield("")
        for (n in 2..MAX_ATTEMPTS) yield(" ($n)")
    }
    return suffixes.map { fit(folderName, it) }.firstOrNull { !exists(it) }
        ?: throw AppError.StorageFailed("no free folder name for the audiobook")
}

/**
 * Where a torrent file goes relative to its target. AUDIO keeps the nested folders but drops
 * the torrent root folder ("Root/CD1/01.mp3" → "CD1/01.mp3"); BOOK keeps only the file
 * name, since e-books lie flat in the books root. Every segment is sanitized; empty, "." and
 * ".." segments are dropped.
 */
fun mapTorrentPath(path: String, kind: MediaKind): String {
    val segments = path.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
    if (segments.isEmpty()) return UNTITLED
    val fileName = sanitizeFileNameKeepingExtension(segments.last())
    if (kind == MediaKind.BOOK) return fileName
    val folders = segments.dropLast(1).drop(if (segments.size > 1) 1 else 0).map { sanitizeFileName(it) }
    return (folders + fileName).joinToString("/")
}

private fun fit(base: String, tail: String): String =
    sanitizeFileName(base, (MAX_FILE_NAME_LENGTH - tail.length).coerceAtLeast(1)) + tail
