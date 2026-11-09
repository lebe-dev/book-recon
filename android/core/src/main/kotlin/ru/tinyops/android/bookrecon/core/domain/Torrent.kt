package ru.tinyops.android.bookrecon.core.domain

/**
 * A file inside a torrent.
 *
 * @property index position in the torrent's file list, used for file priorities.
 * @property path slash-separated path that starts with the torrent root folder for
 *   multi-file torrents, for example `Author - Title/CD1/01.mp3`.
 */
data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
)

/**
 * Parsed content of a `.torrent` file. Padding files and unsafe paths are already removed.
 *
 * @property infoHash lowercase hex info-hash, unique per torrent.
 * @property name torrent name, the root folder of a multi-file torrent.
 */
data class TorrentInfo(
    val infoHash: String,
    val name: String,
    val files: List<TorrentFile>,
) {
    /** Sum of all file sizes in bytes. */
    val totalSize: Long
        get() = files.sumOf { it.size }
}

/**
 * Snapshot of a running download.
 *
 * @property completed bytes downloaded so far, counting only the selected files.
 * @property total bytes to download, counting only the selected files.
 * @property peers peers currently connected.
 */
data class DownloadProgress(
    val completed: Long = 0,
    val total: Long = 0,
    val peers: Int = 0,
    val speedBytesPerSec: Long = 0,
) {
    /** Progress in the range 0..1; 0 while [total] is unknown. */
    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}
