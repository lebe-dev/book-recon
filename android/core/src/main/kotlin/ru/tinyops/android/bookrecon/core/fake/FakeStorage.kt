package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import java.io.File

/** Settings kept in memory. */
class FakeSettingsRepository(
    initial: Settings = Settings(),
    private var password: String? = null,
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<Settings> = state.asStateFlow()

    override suspend fun update(transform: (Settings) -> Settings) {
        state.update(transform)
    }

    override suspend fun rutrackerPassword(): String? = password

    override suspend fun setRutrackerPassword(password: String?) {
        this.password = password?.takeIf { it.isNotEmpty() }
    }
}

/**
 * File storage on the real file system under [baseDir], with switchable access and roots.
 * Roots default to `<baseDir>/Books` and `<baseDir>/Audiobooks`; set one to null to test the
 * "root not configured" path.
 */
class FakeFileStorage(private val baseDir: File) : FileStorage {
    @Volatile
    var hasAccess: Boolean = true

    @Volatile
    var booksRoot: File? = File(baseDir, "Books")

    @Volatile
    var audioRoot: File? = File(baseDir, "Audiobooks")

    override fun hasAllFilesAccess(): Boolean = hasAccess

    override suspend fun root(kind: MediaKind): File {
        if (!hasAccess) throw AppError.StorageNotGranted()
        val root = when (kind) {
            MediaKind.BOOK -> booksRoot
            MediaKind.AUDIO -> audioRoot
        } ?: throw AppError.RootNotConfigured(kind)
        root.mkdirs()
        return root
    }

    override suspend fun stagingDir(kind: MediaKind, jobId: String): File {
        val staging = File(root(kind), STAGING_FOLDER)
        val dir = File(staging, jobId)
        dir.mkdirs()
        File(staging, NOMEDIA).createNewFile()
        return dir
    }

    override fun exists(file: File): Boolean = file.exists()

    override suspend fun move(from: File, to: File) {
        if (to.exists()) throw AppError.StorageFailed("target already exists: ${to.name}")
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw AppError.StorageFailed("cannot move ${from.name}")
    }

    override suspend fun delete(file: File) {
        file.deleteRecursively()
    }

    companion object {
        const val STAGING_FOLDER = ".book-recon"
        const val NOMEDIA = ".nomedia"
    }
}

/**
 * Download records in memory. [fileExists] decides [Download.fileMissing] on every read;
 * by default it checks the real file system.
 */
class FakeDownloadRepository(
    initial: List<Download> = emptyList(),
    private val fileExists: (String) -> Boolean = { File(it).exists() },
) : DownloadRepository {
    private val state = MutableStateFlow(initial.associateBy { it.id })

    /** Every record as stored, without [Download.fileMissing] computed. */
    val all: List<Download>
        get() = state.value.values.toList()

    override fun observeQueue(): Flow<List<Download>> = state.map { records ->
        records.values
            .filter { it.status in QUEUE_STATUSES }
            .sortedWith(queueOrder)
    }

    override fun observeLibrary(query: String, kind: MediaKind?): Flow<List<Download>> = state.map { records ->
        val needle = query.trim().lowercase()
        records.values
            .filter { it.status.isInLibrary }
            .filter { kind == null || it.kind == kind }
            .filter { needle.isEmpty() || needle in it.title.lowercase() || needle in it.author.lowercase() }
            .sortedByDescending { it.completedAt ?: it.createdAt }
            .map(::withFileCheck)
    }

    override suspend fun get(id: String): Download? = state.value[id]?.let(::withFileCheck)

    override suspend fun listByStatus(statuses: Set<DownloadStatus>): List<Download> =
        state.value.values.filter { it.status in statuses }.sortedBy { it.createdAt }

    override suspend fun findInLibrary(source: Source, sourceId: String): Download? =
        state.value.values
            .filter { it.status.isInLibrary && it.source == source && it.sourceId == sourceId }
            .maxByOrNull { it.completedAt ?: it.createdAt }
            ?.let(::withFileCheck)

    override suspend fun insert(download: Download) {
        state.update { records ->
            require(download.id !in records) { "download ${download.id} already exists" }
            records + (download.id to download.copy(fileMissing = false))
        }
    }

    override suspend fun update(download: Download) {
        state.update { records ->
            if (download.id !in records) throw AppError.NotFound("download ${download.id} not found")
            records + (download.id to download.copy(fileMissing = false))
        }
    }

    override suspend fun delete(id: String) {
        state.update { it - id }
    }

    private fun withFileCheck(download: Download): Download {
        if (!download.status.isInLibrary || download.paths.isEmpty()) return download
        return download.copy(fileMissing = download.paths.none(fileExists))
    }

    private companion object {
        val QUEUE_STATUSES = setOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.SEEDING,
            DownloadStatus.QUEUED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )

        val queueOrder: Comparator<Download> = compareBy<Download> {
            when (it.status) {
                DownloadStatus.DOWNLOADING -> 0
                DownloadStatus.SEEDING -> 1
                DownloadStatus.QUEUED -> 2
                DownloadStatus.FAILED -> 3
                else -> 4
            }
        }.thenBy { it.queuePosition ?: Int.MAX_VALUE }.thenBy { it.createdAt }
    }
}
