package ru.tinyops.android.bookrecon.download

import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.naming.audioTargetDir
import ru.tinyops.android.bookrecon.core.naming.bookTargetName
import ru.tinyops.android.bookrecon.core.naming.mapTorrentPath
import ru.tinyops.android.bookrecon.core.naming.splitExtension
import ru.tinyops.android.bookrecon.core.port.FileStorage
import java.io.File

/**
 * Where the selected files of a finished torrent go in the roots.
 *
 * @property targets every selected file with its absolute target path.
 * @property paths what the download records as its paths: the audiobook folder, or every
 *   e-book file.
 * @property folder the new audiobook folder, null for e-books; removed when placing fails.
 */
data class TorrentLayout(
    val targets: List<Pair<TorrentFile, File>>,
    val paths: List<String>,
    val folder: File?,
)

/**
 * Plans the library layout of a finished torrent without touching the disk: an audiobook
 * goes to `<audio root>/<folder>/` without the torrent's root folder, e-books go flat into
 * the books root under names that do not collide with existing files or with each other.
 * The queue and seeding both place files by this plan, so a seeded torrent ends up exactly
 * where a plain one would.
 */
suspend fun planTorrentLayout(download: Download, storage: FileStorage): TorrentLayout = when (download.kind) {
    MediaKind.AUDIO -> planAudio(download, storage)
    MediaKind.BOOK -> planBooks(download, storage)
}

private suspend fun planAudio(download: Download, storage: FileStorage): TorrentLayout {
    val root = storage.root(MediaKind.AUDIO)
    val name = download.targetName?.takeIf { it.isNotBlank() } ?: download.title
    val folder = File(audioTargetDir(root.path, name) { storage.exists(File(it)) })
    val targets = download.files.map { file -> file to File(folder, mapTorrentPath(file.path, MediaKind.AUDIO)) }
    return TorrentLayout(targets, listOf(folder.path), folder)
}

private suspend fun planBooks(download: Download, storage: FileStorage): TorrentLayout {
    val root = storage.root(MediaKind.BOOK)
    // Lower-cased: FAT and exFAT roots treat names that differ in case as the same file.
    val planned = mutableSetOf<String>()
    val targets = download.files.map { file ->
        val (stem, extension) = splitExtension(mapTorrentPath(file.path, MediaKind.BOOK))
        val name = bookTargetName(stem, download.author, extension) { it.lowercase() in planned || storage.exists(File(root, it)) }
        planned += name.lowercase()
        file to File(root, name)
    }
    return TorrentLayout(targets, targets.map { it.second.path }, folder = null)
}
