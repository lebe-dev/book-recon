package ru.tinyops.android.bookrecon.download.seeding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.core.port.TorrentEngine
import ru.tinyops.android.bookrecon.download.SeedingHandoff
import ru.tinyops.android.bookrecon.download.placeTorrentFiles
import ru.tinyops.android.bookrecon.download.planTorrentLayout
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Seeding after download (`Settings.seedHours > 0`).
 *
 * A finished torrent is taken over from the queue: its files are copied into the library by
 * the same layout the queue uses, so the book is available right away, while the torrent
 * keeps seeding from its private staging folder, which the app cannot move into the
 * user's folders. The staged copy is deleted when seeding ends. The download becomes
 * SEEDING with `seedUntil = now + seedHours`.
 *
 * Torrents upload only while seeding is allowed ([setAllowed]), which the seeding job does
 * while the device is charging on an unmetered network; otherwise they are paused. Time on
 * pause counts towards the limit: the deadline is fixed when the download completes. At the
 * deadline, or when the user stops seeding, the torrent leaves the session and the download
 * becomes COMPLETED; the library files stay.
 *
 * Seeding state lives in the records, so after a process restart seeding continues with the
 * time left, and downloads whose deadline passed meanwhile are completed.
 *
 * @param stopSeeding ends seeding of one download: the queue's stopSeeding, which records
 *   COMPLETED, drops the torrent and its saved `.torrent`.
 * @param onSeeding called while something is seeding, to make sure the seeding job is
 *   scheduled.
 * @param log receives plain-English log lines.
 * @param report receives errors nobody expected while updating seeding.
 */
class SeedingManager(
    private val repository: DownloadRepository,
    private val settings: SettingsRepository,
    private val storage: FileStorage,
    private val torrents: TorrentEngine,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val stopSeeding: suspend (String) -> Unit,
    private val onSeeding: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val report: (Throwable) -> Unit = {},
) : SeedingHandoff {

    private val mutex = Mutex()
    private val allowed = MutableStateFlow(false)

    // Downloads handed to the engine for seeding in this process. Guarded by mutex.
    private val seeding = mutableSetOf<String>()

    private val seedingIds: Flow<Set<String>> = repository.observeQueue()
        .map { list -> list.filter { it.status == DownloadStatus.SEEDING }.map { it.id }.toSet() }
        .distinctUntilChanged()

    /** True while some download is SEEDING; the seeding job lives this long. */
    val hasSeeding: Flow<Boolean> = seedingIds.map { it.isNotEmpty() }.distinctUntilChanged()

    init {
        scope.launch { supervise() }
    }

    /** True when some download is SEEDING right now. */
    suspend fun hasSeedingNow(): Boolean = repository.listByStatus(setOf(DownloadStatus.SEEDING)).isNotEmpty()

    /** Lets torrents upload (true) or pauses them (false). */
    fun setAllowed(value: Boolean) {
        if (allowed.value != value) log(if (value) "Seeding allowed" else "Seeding paused")
        allowed.value = value
    }

    override suspend fun takeOver(download: Download, stagingDir: File): Download? {
        val hours = settings.settings.first().seedHours
        if (hours <= 0) return null
        val layout = planTorrentLayout(download, storage)
        // A missing file is reported by the queue's own placement.
        if (layout.targets.any { (file, _) -> !File(stagingDir, file.path).exists() }) return null
        placeTorrentFiles(storage, download.kind, layout, stagingDir)
        val now = clock.now()
        val size = download.files.sumOf { it.size }
        log("Download ${download.id} placed and kept for seeding for $hours hours")
        return download.copy(
            status = DownloadStatus.SEEDING,
            progress = DownloadProgress(size, size),
            queuePosition = null,
            paths = layout.paths,
            size = size,
            error = null,
            completedAt = now,
            seedUntil = now.plus(hours.hours.toJavaDuration()),
        )
    }

    /**
     * Reconciles seeding whenever the permission or the set of seeding downloads changes,
     * and at the nearest deadline.
     */
    private suspend fun supervise() {
        combine(allowed, seedingIds) { allowed, ids -> allowed to ids }.collectLatest { (allowed, ids) ->
            if (ids.isNotEmpty()) onSeeding()
            while (true) {
                val next = try {
                    withContext(NonCancellable) { reconcile(allowed) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Cannot update seeding, retrying later: ${e.message}")
                    if (e !is AppError) report(e)
                    clock.now().plus(MAX_WAIT.toJavaDuration())
                } ?: break
                val wait = java.time.Duration.between(clock.now(), next).toKotlinDuration()
                // Capped: coroutine delays may stall while the device sleeps, the clock does not.
                delay(wait.coerceIn(Duration.ZERO, MAX_WAIT))
            }
        }
    }

    /** One pass over the seeding downloads; returns the nearest deadline still ahead. */
    private suspend fun reconcile(allowed: Boolean): Instant? = mutex.withLock {
        val list = repository.listByStatus(setOf(DownloadStatus.SEEDING))
        seeding.retainAll(list.map { it.id }.toSet())
        val now = clock.now()
        var next: Instant? = null
        for (download in list) {
            val until = download.seedUntil
            if (until == null || !now.isBefore(until)) {
                finish(download.id, "its time is up")
                continue
            }
            val kept = when {
                allowed && download.id !in seeding -> start(download.id, until, now)
                !allowed && download.id in seeding -> pause(download.id)
                else -> true
            }
            if (!kept) {
                finish(download.id, "the torrent cannot seed")
                continue
            }
            if (next == null || until.isBefore(next)) next = until
        }
        next
    }

    private suspend fun start(id: String, until: Instant, now: Instant): Boolean {
        try {
            torrents.seed(id, java.time.Duration.between(now, until).toKotlinDuration())
        } catch (e: AppError) {
            log("Cannot seed download $id: ${e.code}")
            return false
        }
        seeding += id
        log("Seeding download $id until $until")
        return true
    }

    private suspend fun pause(id: String): Boolean {
        seeding -= id
        try {
            torrents.pauseSeeding(id)
        } catch (e: AppError) {
            log("Cannot pause seeding of download $id: ${e.code}")
        }
        return true
    }

    private suspend fun finish(id: String, reason: String) {
        seeding -= id
        log("Seeding of download $id finished: $reason")
        try {
            stopSeeding(id)
        } catch (e: AppError) {
            log("Cannot finish seeding of download $id: ${e.code}")
        }
    }

    private companion object {
        val MAX_WAIT = 1.minutes
    }
}
