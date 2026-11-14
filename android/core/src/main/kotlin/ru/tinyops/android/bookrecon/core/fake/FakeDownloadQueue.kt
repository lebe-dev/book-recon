package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * Download queue that simulates transfers without touching the network or the disk. It
 * keeps the real rules: at most [maxConcurrent] downloads run, the rest wait with a queue
 * position, cancel and retry work in any state, and torrents seed for [seedFor] when it is
 * positive. Each download takes [steps] progress updates [stepDelay] apart.
 *
 * [behavior] applies to [enqueue]; [failNextDownload] makes the next running download fail
 * halfway through with that error.
 */
class FakeDownloadQueue(
    private val repository: DownloadRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val maxConcurrent: suspend () -> Int = { Settings.DEFAULT_MAX_CONCURRENT },
    private val seedFor: suspend () -> Duration = { Duration.ZERO },
    val behavior: FakeBehavior = FakeBehavior(),
    private val steps: Int = 10,
    private val stepDelay: Duration = 500.milliseconds,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DownloadQueue {

    private val mutex = Mutex()
    private val running = mutableMapOf<String, Job>()

    @Volatile
    var failNextDownload: AppError? = null

    override suspend fun enqueue(request: DownloadRequest): String {
        behavior.step()
        val files = selectedFiles(request)
        val size = if (request is DownloadRequest.FlibustaBook) FLIBUSTA_BOOK_SIZE else files.sumOf { it.size }
        val id = newId()
        mutex.withLock {
            val position = repository.listByStatus(setOf(DownloadStatus.QUEUED)).size + 1
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
                    queuePosition = position,
                    files = files,
                    size = size,
                    createdAt = clock.now(),
                ),
            )
            scheduleLocked()
        }
        return id
    }

    override suspend fun cancel(id: String) = mutex.withLock {
        val download = repository.get(id) ?: return@withLock
        if (download.status != DownloadStatus.QUEUED && download.status != DownloadStatus.DOWNLOADING) return@withLock
        running.remove(id)?.cancel()
        repository.update(download.copy(status = DownloadStatus.CANCELLED, queuePosition = null))
        scheduleLocked()
    }

    override suspend fun retry(id: String) = mutex.withLock {
        val download = repository.get(id) ?: return@withLock
        if (download.status != DownloadStatus.FAILED && download.status != DownloadStatus.CANCELLED) return@withLock
        val position = repository.listByStatus(setOf(DownloadStatus.QUEUED)).size + 1
        repository.update(
            download.copy(
                status = DownloadStatus.QUEUED,
                error = null,
                progress = DownloadProgress(0, download.size),
                queuePosition = position,
            ),
        )
        scheduleLocked()
    }

    override suspend fun stopSeeding(id: String) = mutex.withLock {
        val download = repository.get(id) ?: return@withLock
        if (download.status != DownloadStatus.SEEDING) return@withLock
        repository.update(download.copy(status = DownloadStatus.COMPLETED, seedUntil = null))
    }

    override suspend fun dismiss(id: String) = mutex.withLock {
        val download = repository.get(id) ?: return@withLock
        if (download.status !in DISMISSIBLE) return@withLock
        repository.delete(id)
    }

    override fun observe(): Flow<List<Download>> = repository.observeQueue()

    private fun selectedFiles(request: DownloadRequest): List<TorrentFile> {
        if (request !is DownloadRequest.TorrentSelection) return emptyList()
        if (request.selectedIndexes.isEmpty()) throw AppError.SelectionEmpty()
        val topicId = FakeData.topicIdOf(request.torrentBytes) ?: return emptyList()
        return FakeData.torrentInfo(topicId).files.filter { it.index in request.selectedIndexes }
    }

    /** Starts queued downloads while slots are free and renumbers the rest. Hold [mutex]. */
    private suspend fun scheduleLocked() {
        val queued = repository.listByStatus(setOf(DownloadStatus.QUEUED))
            .sortedWith(compareBy<Download> { it.queuePosition ?: Int.MAX_VALUE }.thenBy { it.createdAt })
        val free = (maxConcurrent() - running.size).coerceAtLeast(0)
        queued.take(free).forEach { download ->
            repository.update(download.copy(status = DownloadStatus.DOWNLOADING, queuePosition = null))
            running[download.id] = scope.launch { simulate(download.id) }
        }
        queued.drop(free).forEachIndexed { index, download ->
            if (download.queuePosition != index + 1) repository.update(download.copy(queuePosition = index + 1))
        }
    }

    private suspend fun simulate(id: String) {
        try {
            for (step in 1..steps) {
                delay(stepDelay)
                if (step == steps / 2 + 1) failNextDownload?.let { failNextDownload = null; throw it }
                mutex.withLock {
                    val download = repository.get(id)?.takeIf { it.status == DownloadStatus.DOWNLOADING } ?: return
                    repository.update(download.copy(progress = progressAt(download, step)))
                }
            }
            mutex.withLock { finishLocked(id) }
        } catch (e: AppError) {
            mutex.withLock {
                running.remove(id)
                val download = repository.get(id) ?: return
                repository.update(download.copy(status = DownloadStatus.FAILED, error = e))
                scheduleLocked()
            }
        }
    }

    private fun progressAt(download: Download, step: Int): DownloadProgress {
        val total = download.size
        val perStep = total / steps
        val speed = if (stepDelay > Duration.ZERO) perStep * 1000 / stepDelay.inWholeMilliseconds.coerceAtLeast(1) else perStep
        val peers = if (download.source == Source.RUTRACKER) 5 else 0
        return DownloadProgress(total * step / steps, total, peers, speed)
    }

    private suspend fun finishLocked(id: String) {
        running.remove(id)
        val download = repository.get(id)?.takeIf { it.status == DownloadStatus.DOWNLOADING } ?: return
        val now = clock.now()
        val seed = if (download.source == Source.RUTRACKER) seedFor() else Duration.ZERO
        val done = download.copy(
            progress = DownloadProgress(download.size, download.size),
            paths = listOf(targetPath(download)),
            completedAt = now,
        )
        val next = if (seed > Duration.ZERO) {
            done.copy(status = DownloadStatus.SEEDING, seedUntil = now.plus(seed.toJavaDuration()))
        } else {
            done.copy(status = DownloadStatus.COMPLETED)
        }
        repository.update(next)
        scheduleLocked()
    }

    private fun targetPath(download: Download): String = when (download.kind) {
        MediaKind.AUDIO -> "$FAKE_AUDIO_ROOT/${download.targetName ?: download.title}"
        MediaKind.BOOK -> "$FAKE_BOOKS_ROOT/${download.title}.${download.format?.extension ?: "epub"}"
    }

    private companion object {
        val DISMISSIBLE = setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.COMPLETED)
        const val FLIBUSTA_BOOK_SIZE = 1_500_000L
        const val FAKE_BOOKS_ROOT = "/storage/emulated/0/Books"
        const val FAKE_AUDIO_ROOT = "/storage/emulated/0/Audiobooks"
    }
}
