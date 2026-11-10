package ru.tinyops.android.bookrecon.core.port

import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import java.io.File
import java.time.Instant

/** Persistent user settings. */
interface SettingsRepository {
    /** Current settings; emits again after every [update]. */
    val settings: Flow<Settings>

    /** Atomically replaces the settings with the result of [transform]. */
    suspend fun update(transform: (Settings) -> Settings)

    /** The decrypted RuTracker password, null when not set. Never log it. */
    suspend fun rutrackerPassword(): String?

    /** Encrypts and stores the RuTracker password; null or empty removes it. */
    suspend fun setRutrackerPassword(password: String?)
}

/**
 * Download history and queue. Reads fill [Download.fileMissing] of library downloads
 * (COMPLETED and SEEDING) by checking the paths on disk; it is never stored.
 */
interface DownloadRepository {
    /**
     * Downloads shown on the downloads screen: DOWNLOADING, then SEEDING, then QUEUED by
     * queue position, then FAILED, then CANCELLED; ties are ordered by creation time, oldest
     * first.
     */
    fun observeQueue(): Flow<List<Download>>

    /**
     * Library downloads, newest first by completion time: COMPLETED and SEEDING, whose files
     * are already in the roots ([DownloadStatus.isInLibrary]). A download that stops seeding
     * keeps its completion time, so it stays in place. [query] matches title or author
     * case-insensitively, empty matches all; [kind] null means both kinds.
     */
    fun observeLibrary(query: String = "", kind: MediaKind? = null): Flow<List<Download>>

    suspend fun get(id: String): Download?

    /** All downloads with one of [statuses], oldest first; used to recover after restart. */
    suspend fun listByStatus(statuses: Set<DownloadStatus>): List<Download>

    /**
     * The newest library download (COMPLETED or SEEDING) of this source item, or null; the
     * "already downloaded" mark is this record without [Download.fileMissing].
     */
    suspend fun findInLibrary(source: Source, sourceId: String): Download?

    /** Inserts a new download; throws if the ID exists. */
    suspend fun insert(download: Download)

    /** Replaces an existing download by ID. */
    suspend fun update(download: Download)

    /** Removes the record only; files are deleted by the caller. */
    suspend fun delete(id: String)
}

/**
 * The download queue: runs at most `Settings.maxConcurrent` downloads, the rest wait.
 * Flibusta and RuTracker downloads share it.
 */
interface DownloadQueue {
    /**
     * Records the request as QUEUED and starts it when a slot is free. Returns the new
     * download ID. Throws StorageNotGranted or RootNotConfigured right away, before anything
     * is recorded, and SelectionEmpty for a torrent with nothing selected.
     */
    suspend fun enqueue(request: DownloadRequest): String

    /** Cancels a queued or running download and deletes its staging files. */
    suspend fun cancel(id: String)

    /** Puts a FAILED or CANCELLED download back to the queue. */
    suspend fun retry(id: String)

    /** Stops seeding: the torrent leaves the session and the download becomes COMPLETED. */
    suspend fun stopSeeding(id: String)

    /** Removes a FAILED or CANCELLED download from the queue screen. */
    suspend fun dismiss(id: String)

    /** Same as [DownloadRepository.observeQueue], with live progress. */
    fun observe(): Flow<List<Download>>
}

/**
 * File system access for the two roots. Paths are plain absolute paths on shared storage,
 * reachable thanks to all-files access.
 */
interface FileStorage {
    /** True when the app holds all-files access. */
    fun hasAllFilesAccess(): Boolean

    /**
     * The root folder for [kind]. Throws StorageNotGranted without all-files access and
     * RootNotConfigured when the root is not chosen or no longer exists.
     */
    suspend fun root(kind: MediaKind): File

    /**
     * Creates and returns `<root>/.book-recon/<jobId>/`; `.book-recon` holds a `.nomedia`
     * file so players and readers skip it. Same errors as [root].
     */
    suspend fun stagingDir(kind: MediaKind, jobId: String): File

    fun exists(file: File): Boolean

    /**
     * Moves a file or folder; renames within one file system, otherwise copies and deletes.
     * Creates missing parent folders. Throws StorageFailed when [to] exists or the move fails.
     */
    suspend fun move(from: File, to: File)

    /** Deletes a file or a folder with its content; a missing file is not an error. */
    suspend fun delete(file: File)
}

/** Source of the current time, replaced by a fake in tests. */
fun interface Clock {
    fun now(): Instant

    companion object {
        /** The system clock. */
        val System: Clock = Clock { Instant.now() }
    }
}
