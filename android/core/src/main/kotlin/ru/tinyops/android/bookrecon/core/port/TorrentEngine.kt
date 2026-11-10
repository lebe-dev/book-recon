package ru.tinyops.android.bookrecon.core.port

import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import java.io.File
import kotlin.time.Duration

/**
 * BitTorrent engine. One session per process; torrents are addressed by job ID, the same as
 * [ru.tinyops.android.bookrecon.core.domain.Download.id]. All failures are
 * [ru.tinyops.android.bookrecon.core.domain.AppError].
 */
interface TorrentEngine {
    /** Parses a `.torrent` file. Throws ParseError for invalid data. */
    fun parse(bytes: ByteArray): TorrentInfo

    /**
     * Downloads the files with [indexes] into [stagingDir]; other files are skipped and any
     * partial pieces of them are deleted at the end. Files land at
     * `<stagingDir>/<TorrentFile.path>`.
     *
     * The flow starts the download when collected, emits progress every few seconds,
     * completes normally when all selected files are done, and throws NoSeeders, Stalled or
     * AlreadyDownloading (the same info-hash is already running) on failure. Cancelling the
     * collector pauses the torrent and keeps resume data; call [cancel] to drop it.
     */
    fun download(jobId: String, bytes: ByteArray, indexes: Set<Int>, stagingDir: File): Flow<DownloadProgress>

    /**
     * Continues a download started by [download] before the process restarted, from saved
     * resume data. Same flow contract as [download]. Throws NotFound when there is no resume
     * data for [jobId].
     */
    fun resume(jobId: String): Flow<DownloadProgress>

    /** Stops the torrent and removes it with its resume data. Files are left to the caller. */
    suspend fun cancel(jobId: String)

    /**
     * Keeps a completed torrent in the session and seeds it for at most [duration], after
     * which it is removed from the session. Files stay where they are.
     */
    suspend fun seed(jobId: String, duration: Duration)

    /** Pauses a seeding torrent, for example when Wi-Fi or charging is lost. */
    suspend fun pauseSeeding(jobId: String)

    /** Resumes a seeding torrent paused by [pauseSeeding]. */
    suspend fun resumeSeeding(jobId: String)

    /**
     * Moves the torrent's data to [dir] so it keeps seeding from the new place. Suspends
     * until the move is done; throws StorageFailed if it fails.
     */
    suspend fun moveStorage(jobId: String, dir: File)
}
