package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.naming.bookTargetName
import ru.tinyops.android.bookrecon.core.naming.splitExtension
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.DownloadedFile
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.FlibustaSource
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.core.port.TorrentEngine
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Takes over a finished torrent for seeding instead of letting the queue drop it.
 */
fun interface SeedingHandoff {
    /**
     * Offered a torrent whose selected files are complete in [stagingDir], only when seeding
     * after download is enabled. Returns the download to store (SEEDING, with its paths and
     * seed deadline) when it places the files and keeps seeding itself, or null to let the
     * queue place the files and remove the torrent from the session.
     */
    suspend fun takeOver(download: Download, stagingDir: File): Download?

    companion object {
        /** Never seeds: every torrent is placed and removed right away. */
        val None: SeedingHandoff = SeedingHandoff { _, _ -> null }
    }
}

/** A download that just reached a final state, reported once for a notification. */
sealed interface DownloadEvent {
    val download: Download

    data class Completed(override val download: Download) : DownloadEvent

    data class Failed(override val download: Download, val error: AppError) : DownloadEvent
}

/**
 * The download queue. Downloads run only while the queue is started ([start]), which the
 * background job does once the system lets it run; [pause] puts the running ones back in
 * front of the queue when the job is stopped. At most `Settings.maxConcurrent` run at once.
 *
 * Flibusta books are fetched into the staging folder and renamed into the books root.
 * Torrents download into the staging folder and their files are moved into place once
 * complete. The `.torrent` bytes are kept in [torrentStore], so a torrent can start after a
 * process restart and be retried after a failure.
 *
 * The first call of any method recovers from a previous process: downloads left in
 * DOWNLOADING go back to the head of the queue, and torrents continue from resume data.
 *
 * @param onWork called after new work is queued, to schedule the background job.
 * @param log receives plain-English log lines.
 * @param report receives errors nobody expected (they fail a download as UNKNOWN).
 */
class DownloadQueueImpl(
    private val repository: DownloadRepository,
    private val settings: SettingsRepository,
    private val storage: FileStorage,
    private val flibusta: FlibustaSource,
    private val torrents: TorrentEngine,
    private val torrentStore: File,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val seeding: SeedingHandoff = SeedingHandoff.None,
    private val onWork: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val report: (Throwable) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DownloadQueue {

    private val mutex = Mutex()

    // Guarded by mutex.
    private val running = mutableMapOf<String, Job>()
    private var active = false
    private var recovered = false

    /** Torrents that were started before and should continue from resume data. */
    private val resumable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val liveProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    private val eventFlow = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Completions and failures, as they happen; nothing is replayed. */
    val events: SharedFlow<DownloadEvent> = eventFlow.asSharedFlow()

    /** True while something is queued or downloading; the background job lives this long. */
    val hasWork: Flow<Boolean> = repository.observeQueue()
        .map { list -> list.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING } }
        .distinctUntilChanged()

    override suspend fun enqueue(request: DownloadRequest): String {
        storage.root(request.kind)
        val files = selectedFiles(request)
        val id = newId()
        mutex.withLock {
            recoverLocked()
            ensureNotActiveLocked(request)
            if (request is DownloadRequest.TorrentSelection) saveTorrent(id, request.torrentBytes)
            val size = files.sumOf { it.size }
            repository.insert(
                Download(
                    id = id,
                    kind = request.kind,
                    source = request.source,
                    sourceId = request.sourceId,
                    title = request.title,
                    author = (request as? DownloadRequest.FlibustaBook)?.author.orEmpty(),
                    format = (request as? DownloadRequest.FlibustaBook)?.format,
                    targetName = (request as? DownloadRequest.TorrentSelection)?.targetName,
                    status = DownloadStatus.QUEUED,
                    progress = DownloadProgress(0, size),
                    queuePosition = Int.MAX_VALUE,
                    files = files,
                    size = size,
                    createdAt = clock.now(),
                ),
            )
            log("Queued download $id from ${request.source}")
            scheduleLocked()
        }
        onWork()
        return id
    }

    override suspend fun cancel(id: String) {
        // A running download is cancelled first; once its worker is gone, the next pass
        // records the cancellation. A worker that finished meanwhile leaves nothing to cancel.
        var lastProgress: DownloadProgress? = null
        while (true) {
            var cancelled: Download? = null
            val worker = mutex.withLock {
                recoverLocked()
                val download = repository.get(id) ?: return
                if (download.status != DownloadStatus.QUEUED && download.status != DownloadStatus.DOWNLOADING) return
                lastProgress = liveProgress.value[id] ?: lastProgress
                running[id]?.also { it.cancel() } ?: run {
                    markCancelledLocked(download, lastProgress)
                    scheduleLocked()
                    cancelled = download
                    null
                }
            }
            cancelled?.let {
                deleteFiles(it)
                return
            }
            worker?.join()
        }
    }

    override suspend fun retry(id: String) {
        mutex.withLock {
            recoverLocked()
            val download = repository.get(id) ?: return
            if (download.status != DownloadStatus.FAILED && download.status != DownloadStatus.CANCELLED) return
            repository.update(
                download.copy(
                    status = DownloadStatus.QUEUED,
                    error = null,
                    progress = DownloadProgress(0, download.files.sumOf { it.size }),
                    queuePosition = Int.MAX_VALUE,
                    completedAt = null,
                ),
            )
            log("Retrying download $id")
            scheduleLocked()
        }
        onWork()
    }

    override suspend fun stopSeeding(id: String) {
        val download = mutex.withLock {
            val download = repository.get(id) ?: return
            if (download.status != DownloadStatus.SEEDING) return
            repository.update(download.copy(status = DownloadStatus.COMPLETED, seedUntil = null))
            download
        }
        runCatching { torrents.cancel(download.id) }
        deleteTorrent(download.id)
    }

    override suspend fun dismiss(id: String) {
        mutex.withLock {
            val download = repository.get(id) ?: return
            if (download.status != DownloadStatus.FAILED && download.status != DownloadStatus.CANCELLED) return
            repository.delete(id)
        }
        deleteTorrent(id)
    }

    override fun observe(): Flow<List<Download>> =
        combine(repository.observeQueue(), liveProgress) { list, progress ->
            list.map { download -> progress[download.id]?.let { download.copy(progress = it) } ?: download }
        }

    /**
     * Brings back downloads interrupted by a process death, without running them. Returns
     * true when there is work for the background job.
     */
    suspend fun recover(): Boolean = mutex.withLock {
        recoverLocked()
        scheduleLocked()
        hasWorkNow()
    }

    /** True when something is queued or downloading right now. */
    suspend fun hasWorkNow(): Boolean =
        repository.listByStatus(setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)).isNotEmpty()

    /** Lets queued downloads run. */
    suspend fun start() {
        mutex.withLock {
            recoverLocked()
            if (!active) log("Download queue started")
            active = true
            scheduleLocked()
        }
    }

    /**
     * Starts queued downloads that fit a raised `Settings.maxConcurrent`. A lowered limit lets
     * running downloads finish and starts nothing new until they drop below it.
     */
    suspend fun reschedule() {
        mutex.withLock { scheduleLocked() }
    }

    /**
     * Stops running downloads and puts them back at the head of the queue. Torrents keep
     * their partial data and continue from it; books start over.
     */
    suspend fun pause() {
        val paused = mutex.withLock {
            active = false
            running.toMap()
        }
        if (paused.isEmpty()) return
        val progress = liveProgress.value
        paused.values.forEach { it.cancel() }
        paused.values.joinAll()
        mutex.withLock {
            paused.keys.forEach { id ->
                val download = repository.get(id) ?: return@forEach
                if (download.status != DownloadStatus.DOWNLOADING) return@forEach
                if (download.source == Source.RUTRACKER) resumable += id
                repository.update(
                    download.copy(
                        status = DownloadStatus.QUEUED,
                        progress = progress[id] ?: download.progress,
                        queuePosition = 0,
                    ),
                )
            }
            scheduleLocked()
        }
        paused.keys.forEach { id ->
            val download = repository.get(id) ?: return@forEach
            if (download.source == Source.FLIBUSTA) deleteStaging(download)
        }
        log("Download queue paused, ${paused.size} downloads put back in the queue")
    }

    private fun selectedFiles(request: DownloadRequest): List<TorrentFile> {
        if (request !is DownloadRequest.TorrentSelection) return emptyList()
        if (request.selectedIndexes.isEmpty()) throw AppError.SelectionEmpty()
        val info = torrents.parse(request.torrentBytes)
        val files = info.files.filter { it.index in request.selectedIndexes }
        if (files.isEmpty()) throw AppError.SelectionEmpty()
        return files
    }

    /** The same book in the same format, or the same torrent, may be in the queue only once. */
    private suspend fun ensureNotActiveLocked(request: DownloadRequest) {
        val format = (request as? DownloadRequest.FlibustaBook)?.format
        val busy = repository.listByStatus(setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.SEEDING))
            .any { it.source == request.source && it.sourceId == request.sourceId && it.format == format }
        if (busy) throw AppError.AlreadyDownloading()
    }

    private suspend fun recoverLocked() {
        if (recovered) return
        recovered = true
        val stale = repository.listByStatus(setOf(DownloadStatus.DOWNLOADING))
        stale.forEach { download ->
            if (download.source == Source.RUTRACKER) resumable += download.id
            repository.update(download.copy(status = DownloadStatus.QUEUED, queuePosition = 0))
        }
        if (stale.isNotEmpty()) log("Recovered ${stale.size} interrupted downloads")
    }

    /** Starts queued downloads while slots are free and renumbers the rest. Hold [mutex]. */
    private suspend fun scheduleLocked() {
        val queued = repository.listByStatus(setOf(DownloadStatus.QUEUED))
            .sortedWith(compareBy<Download> { it.queuePosition ?: Int.MAX_VALUE }.thenBy { it.createdAt })
        val limit = settings.settings.first().maxConcurrent.coerceIn(1, Settings.MAX_CONCURRENT_LIMIT)
        val free = if (active && scope.isActive) (limit - running.size).coerceAtLeast(0) else 0
        queued.take(free).forEach { launchLocked(it) }
        queued.drop(free).forEachIndexed { index, download ->
            if (download.queuePosition != index + 1) repository.update(download.copy(queuePosition = index + 1))
        }
    }

    private suspend fun launchLocked(download: Download) {
        val started = download.copy(status = DownloadStatus.DOWNLOADING, queuePosition = null, error = null)
        repository.update(started)
        liveProgress.update { it + (download.id to download.progress) }
        log("Started download ${download.id}")
        running[download.id] = scope.launch { work(started) }
    }

    private suspend fun work(download: Download) {
        val self = currentCoroutineContext()[Job]
        try {
            val result = when (download.source) {
                Source.FLIBUSTA -> downloadBook(download)
                Source.RUTRACKER -> downloadTorrent(download)
            }
            withContext(NonCancellable) { commit(result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.toAppError()
            if (error is AppError.Unknown) report(e)
            withContext(NonCancellable) { fail(download, error) }
        } finally {
            withContext(NonCancellable) {
                liveProgress.update { it - download.id }
                mutex.withLock {
                    if (running[download.id] === self) running.remove(download.id)
                    runCatching { scheduleLocked() }.onFailure { log("Cannot schedule downloads: ${it.message}") }
                }
            }
        }
    }

    private suspend fun downloadBook(download: Download): Download {
        val format = download.format ?: throw AppError.Validation("book download without a format")
        val staging = storage.stagingDir(MediaKind.BOOK, download.id)
        staging.listFiles()?.forEach { storage.delete(it) }
        val file = flibusta.download(download.sourceId, format, staging)
        return withContext(NonCancellable) { placeBook(download, file, staging) }
    }

    private suspend fun placeBook(download: Download, file: DownloadedFile, staging: File): Download {
        val root = storage.root(MediaKind.BOOK)
        val extension = splitExtension(file.suggestedName).second.ifEmpty { download.format?.extension.orEmpty() }
        val name = bookTargetName(download.title, download.author, extension) { storage.exists(File(root, it)) }
        val target = File(root, name)
        val size = file.tempFile.length()
        storage.move(file.tempFile, target)
        storage.delete(staging)
        return completed(download, listOf(target.path), size)
    }

    private suspend fun downloadTorrent(download: Download): Download {
        val staging = storage.stagingDir(download.kind, download.id)
        torrentFlow(download, staging).collect { progress ->
            liveProgress.update { it + (download.id to progress) }
        }
        return withContext(NonCancellable) { finishTorrent(download, staging) }
    }

    private fun torrentFlow(download: Download, staging: File): Flow<DownloadProgress> {
        val indexes = download.files.map { it.index }.toSet()
        val fresh = { torrents.download(download.id, readTorrent(download.id), indexes, staging) }
        if (!resumable.remove(download.id)) return fresh()
        return flow {
            try {
                emitAll(torrents.resume(download.id))
            } catch (_: AppError.NotFound) {
                log("No resume data for download ${download.id}, starting over")
                emitAll(fresh())
            }
        }
    }

    private suspend fun finishTorrent(download: Download, staging: File): Download {
        if (settings.settings.first().seedHours > 0) {
            seeding.takeOver(download, staging)?.let { return it }
        }
        val layout = planTorrentLayout(download, storage)
        placeTorrent(layout, staging)
        runCatching { torrents.cancel(download.id) }
        storage.delete(staging)
        deleteTorrent(download.id)
        return completed(download, layout.paths, download.files.sumOf { it.size })
    }

    /** Moves the files by [layout]; on failure whatever was placed is removed again. */
    private suspend fun placeTorrent(layout: TorrentLayout, staging: File) {
        val placed = mutableListOf<File>()
        try {
            layout.targets.forEach { (file, target) ->
                storage.move(stagedFile(staging, file), target)
                placed += target
            }
        } catch (e: Throwable) {
            placed.forEach { storage.delete(it) }
            layout.folder?.let { storage.delete(it) }
            throw e
        }
    }

    private fun stagedFile(staging: File, file: TorrentFile): File {
        val staged = File(staging, file.path)
        if (!storage.exists(staged)) throw AppError.StorageFailed("downloaded file is missing: ${file.path}")
        return staged
    }

    private fun completed(download: Download, paths: List<String>, size: Long): Download = download.copy(
        status = DownloadStatus.COMPLETED,
        progress = DownloadProgress(size, size),
        queuePosition = null,
        paths = paths,
        size = size,
        error = null,
        completedAt = clock.now(),
    )

    private suspend fun commit(result: Download) {
        mutex.withLock {
            val current = repository.get(result.id) ?: return
            if (current.status != DownloadStatus.DOWNLOADING) return
            repository.update(result)
        }
        log("Download ${result.id} finished with status ${result.status}")
        eventFlow.tryEmit(DownloadEvent.Completed(result))
    }

    private suspend fun fail(download: Download, error: AppError) {
        log("Download ${download.id} failed: ${error.code}")
        deleteFiles(download)
        val failed = mutex.withLock {
            val current = repository.get(download.id) ?: return
            if (current.status != DownloadStatus.DOWNLOADING) return
            val failed = current.copy(
                status = DownloadStatus.FAILED,
                error = error,
                progress = liveProgress.value[download.id] ?: current.progress,
            )
            repository.update(failed)
            failed
        }
        eventFlow.tryEmit(DownloadEvent.Failed(failed, error))
    }

    private suspend fun markCancelledLocked(download: Download, progress: DownloadProgress?) {
        resumable.remove(download.id)
        repository.update(
            download.copy(
                status = DownloadStatus.CANCELLED,
                queuePosition = null,
                progress = progress ?: download.progress,
            ),
        )
        log("Cancelled download ${download.id}")
    }

    /** Drops the torrent from the session and deletes the staging folder. */
    private suspend fun deleteFiles(download: Download) {
        if (download.source == Source.RUTRACKER) runCatching { torrents.cancel(download.id) }
        deleteStaging(download)
    }

    private suspend fun deleteStaging(download: Download) {
        runCatching { storage.delete(storage.stagingDir(download.kind, download.id)) }
            .onFailure { log("Cannot delete staging files of download ${download.id}: ${it.message}") }
    }

    private fun torrentFile(id: String): File = File(torrentStore, "$id.torrent")

    private fun saveTorrent(id: String, bytes: ByteArray) {
        try {
            torrentStore.mkdirs()
            torrentFile(id).writeBytes(bytes)
        } catch (e: IOException) {
            throw AppError.StorageFailed("cannot save the torrent file", e)
        }
    }

    private fun readTorrent(id: String): ByteArray {
        val file = torrentFile(id)
        if (!file.isFile) throw AppError.Interrupted()
        return file.readBytes()
    }

    private fun deleteTorrent(id: String) {
        torrentFile(id).delete()
    }

    private fun Throwable.toAppError(): AppError = when (this) {
        is AppError -> this
        is IOException -> AppError.StorageFailed(message ?: "i/o error", this)
        else -> AppError.Unknown(message ?: javaClass.simpleName, this)
    }

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
