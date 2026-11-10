package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.naming.audioFolderName
import ru.tinyops.android.bookrecon.core.naming.bookTargetName
import ru.tinyops.android.bookrecon.core.naming.mapTorrentPath
import ru.tinyops.android.bookrecon.core.naming.splitExtension
import ru.tinyops.android.bookrecon.core.port.FileStorage
import java.io.File

/**
 * Where the selected files of a finished torrent go in the roots. Every path is relative to
 * the root of the download's kind.
 *
 * @property targets every selected file with its target path.
 * @property paths what the download records as its paths: the audiobook folder, or every
 *   e-book file.
 * @property folder the new audiobook folder, null for e-books; removed when placing fails.
 */
data class TorrentLayout(
    val targets: List<Pair<TorrentFile, String>>,
    val paths: List<String>,
    val folder: String?,
)

/**
 * Plans the library layout of a finished torrent without touching the disk: an audiobook
 * goes to `<folder>/` in the audio root without the torrent's root folder, e-books go flat
 * into the books root under names that do not collide with existing files or with each
 * other. The queue and seeding both place files by this plan, so a seeded torrent ends up
 * exactly where a plain one would.
 */
suspend fun planTorrentLayout(download: Download, storage: FileStorage): TorrentLayout {
    storage.requireRoot(download.kind)
    // Asking the storage whether a name is taken blocks on file I/O; the queue runs on IO.
    return when (download.kind) {
        MediaKind.AUDIO -> planAudio(download, storage)
        MediaKind.BOOK -> planBooks(download, storage)
    }
}

private fun planAudio(download: Download, storage: FileStorage): TorrentLayout {
    val name = download.targetName?.takeIf { it.isNotBlank() } ?: download.title
    val folder = audioFolderName(name) { storage.exists(MediaKind.AUDIO, it) }
    val targets = download.files.map { file -> file to "$folder/${mapTorrentPath(file.path, MediaKind.AUDIO)}" }
    return TorrentLayout(targets, listOf(folder), folder)
}

private fun planBooks(download: Download, storage: FileStorage): TorrentLayout {
    // Lower-cased: FAT and exFAT roots treat names that differ in case as the same file.
    val planned = mutableSetOf<String>()
    val targets = download.files.map { file ->
        val (stem, extension) = splitExtension(mapTorrentPath(file.path, MediaKind.BOOK))
        val name = bookTargetName(stem, download.author, extension) { it.lowercase() in planned || storage.exists(MediaKind.BOOK, it) }
        planned += name.lowercase()
        file to name
    }
    return TorrentLayout(targets, targets.map { it.second }, folder = null)
}

/**
 * Copies the files of [layout] from [staging] into the root of [kind]. On failure whatever
 * was placed is removed again and the error is thrown; the staged files stay untouched.
 */
suspend fun placeTorrentFiles(storage: FileStorage, kind: MediaKind, layout: TorrentLayout, staging: File) {
    val placed = mutableListOf<String>()
    try {
        layout.targets.forEach { (file, target) ->
            val staged = File(staging, file.path)
            if (!staged.exists()) throw AppError.StorageFailed("downloaded file is missing: ${file.path}")
            storage.copyInto(kind, staged, target)
            placed += target
        }
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            placed.forEach { storage.delete(kind, it) }
            layout.folder?.let { storage.delete(kind, it) }
        }
        throw e
    }
}
