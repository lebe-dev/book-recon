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
     * download ID. Throws RootNotConfigured or StorageNotGranted right away, before anything
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
 * File access of the app. The app holds no all-files permission: it reaches only the two
 * library roots (books and audiobooks) that the user picked with the system folder picker,
 * through persisted grants on those folders. Everything else on shared storage is out of
 * reach for the operating system itself.
 *
 * Downloads are staged in a private folder of the app ([stagingDir]) and copied into the
 * root of their kind once complete. Items in the roots are addressed by slash-separated
 * paths relative to the root of their [MediaKind]: `Book.epub`, `Author - Title/CD1/01.mp3`.
 */
interface FileStorage {
    /**
     * Checks that the root of [kind] is chosen, still exists and may be written. Throws
     * RootNotConfigured when it is not chosen or no longer exists, and StorageNotGranted when
     * the grant on the folder is gone.
     */
    suspend fun requireRoot(kind: MediaKind)

    /**
     * Creates and returns the private folder of the job where its files are downloaded.
     * Needs no permission and never lies inside a root.
     */
    suspend fun stagingDir(jobId: String): File

    /** Deletes the private folder of the job with everything in it; a missing one is not an error. */
    suspend fun deleteStaging(jobId: String)

    /** True when [path] exists in the root of [kind]; false also when the root is not usable. */
    fun exists(kind: MediaKind, path: String): Boolean

    /** True when [path] exists in the root of [kind] and is a folder. */
    fun isFolder(kind: MediaKind, path: String): Boolean

    /**
     * Paths, relative to the root of [kind], of every file under the folder [path], looking
     * into subfolders. Empty when [path] is missing or is a file.
     */
    fun listFiles(kind: MediaKind, path: String): List<String>

    /**
     * Copies a local file or folder into the root of [kind] as [to], creating missing parent
     * folders. Throws StorageFailed when [to] exists or the copy fails; a failed copy leaves
     * nothing of it behind, and [from] stays untouched.
     */
    suspend fun copyInto(kind: MediaKind, from: File, to: String)

    /** Deletes a file or a folder with its content in the root of [kind]; a missing path is not an error. */
    suspend fun delete(kind: MediaKind, path: String)

    /** A content URI that other apps can be handed to read [path], null when the root is not usable. */
    fun uriOf(kind: MediaKind, path: String): String?
}

/** Source of the current time, replaced by a fake in tests. */
fun interface Clock {
    fun now(): Instant

    companion object {
        /** The system clock. */
        val System: Clock = Clock { Instant.now() }
    }
}
