package ru.tinyops.android.bookrecon.storage.db

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.port.Clock
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FileStorage
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * [DownloadRepository] on Room.
 *
 * [Download.fileMissing] is computed on every read of a library download (COMPLETED or
 * SEEDING) by checking its paths through [storage], on [io]. An update that changes nothing but the progress is
 * written at most once per [progressInterval] per download; any other change is written at
 * once, with the latest progress.
 */
class RoomDownloadRepository(
    private val dao: DownloadDao,
    private val storage: FileStorage,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val progressInterval: Duration = Duration.ofSeconds(1),
) : DownloadRepository {
    private class Written(val download: Download, val at: Instant)

    private val writes = Mutex()
    private val lastWritten = HashMap<String, Written>()

    override fun observeQueue(): Flow<List<Download>> =
        dao.observeQueue().map { rows -> rows.map { it.toDomain() } }

    override fun observeLibrary(query: String, kind: MediaKind?): Flow<List<Download>> =
        dao.observeLibrary(likePattern(query), kind)
            .map { rows -> rows.map { withFileCheck(it.toDomain()) } }
            .flowOn(io)

    override suspend fun get(id: String): Download? = read { dao.get(id) }

    override suspend fun listByStatus(statuses: Set<DownloadStatus>): List<Download> {
        if (statuses.isEmpty()) return emptyList()
        return dao.listByStatus(statuses).map { it.toDomain() }
    }

    override suspend fun findInLibrary(source: Source, sourceId: String): Download? =
        read { dao.findInLibrary(source, sourceId) }

    override suspend fun insert(download: Download) {
        val stored = download.copy(fileMissing = false)
        writes.withLock {
            try {
                dao.insert(stored.toRow())
            } catch (e: SQLiteConstraintException) {
                throw IllegalArgumentException("download ${download.id} already exists", e)
            }
            lastWritten[stored.id] = Written(stored, clock.now())
        }
    }

    override suspend fun update(download: Download) {
        val stored = download.copy(fileMissing = false)
        writes.withLock {
            val now = clock.now()
            val previous = lastWritten[stored.id]
            if (previous != null && isProgressOnly(previous.download, stored) && isTooSoon(previous.at, now)) return
            if (!dao.replace(stored.toRow())) throw AppError.NotFound("download ${stored.id} not found")
            lastWritten[stored.id] = Written(stored, now)
        }
    }

    override suspend fun delete(id: String) {
        writes.withLock {
            dao.delete(id)
            lastWritten.remove(id)
        }
    }

    private suspend fun read(query: suspend () -> DownloadWithFiles?): Download? {
        val row = query() ?: return null
        return withContext(io) { withFileCheck(row.toDomain()) }
    }

    private fun withFileCheck(download: Download): Download {
        if (!download.status.isInLibrary || download.paths.isEmpty()) return download
        return download.copy(fileMissing = download.paths.none { storage.exists(File(it)) })
    }

    private fun isProgressOnly(previous: Download, next: Download): Boolean =
        previous.progress != next.progress && previous.copy(progress = next.progress) == next

    private fun isTooSoon(previous: Instant, now: Instant): Boolean =
        Duration.between(previous, now) < progressInterval

    private companion object {
        /** A LIKE pattern matching [query] anywhere, with `%`, `_` and `\` taken literally. */
        fun likePattern(query: String): String {
            val needle = query.trim().lowercase()
            val escaped = buildString {
                needle.forEach { char ->
                    if (char == '%' || char == '_' || char == '\\') append('\\')
                    append(char)
                }
            }
            return "%$escaped%"
        }
    }
}
