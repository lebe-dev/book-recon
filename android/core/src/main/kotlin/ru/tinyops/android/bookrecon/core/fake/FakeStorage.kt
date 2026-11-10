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
 * File storage on the real file system under [baseDir]. Roots default to `<baseDir>/Books`
 * and `<baseDir>/Audiobooks` and staging lives in `<baseDir>/staging`; set a root to null to
 * test the "root not configured" path, and [hasAccess] to false to test lost grants.
 */
class FakeFileStorage(private val baseDir: File) : FileStorage {
    /** False simulates a root whose grant the user has revoked. */
    @Volatile
    var hasAccess: Boolean = true

    @Volatile
    var booksRoot: File? = File(baseDir, "Books")

    @Volatile
    var audioRoot: File? = File(baseDir, "Audiobooks")

    private fun rootOrNull(kind: MediaKind): File? = when (kind) {
        MediaKind.BOOK -> booksRoot
        MediaKind.AUDIO -> audioRoot
    }

    private fun resolve(kind: MediaKind, path: String): File? {
        if (!hasAccess) return null
        val root = rootOrNull(kind) ?: return null
        return File(root, path)
    }

    override suspend fun requireRoot(kind: MediaKind) {
        val root = rootOrNull(kind) ?: throw AppError.RootNotConfigured(kind)
        if (!hasAccess) throw AppError.StorageNotGranted()
        root.mkdirs()
    }

    override suspend fun stagingDir(jobId: String): File = File(baseDir, "$STAGING_FOLDER/$jobId").also { it.mkdirs() }

    override suspend fun deleteStaging(jobId: String) {
        File(baseDir, "$STAGING_FOLDER/$jobId").deleteRecursively()
    }

    override fun exists(kind: MediaKind, path: String): Boolean = resolve(kind, path)?.exists() == true

    override fun isFolder(kind: MediaKind, path: String): Boolean = resolve(kind, path)?.isDirectory == true

    override fun listFiles(kind: MediaKind, path: String): List<String> {
        val root = rootOrNull(kind) ?: return emptyList()
        val folder = resolve(kind, path)?.takeIf { it.isDirectory } ?: return emptyList()
        return folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    override suspend fun copyInto(kind: MediaKind, from: File, to: String) {
        requireRoot(kind)
        val target = resolve(kind, to) ?: throw AppError.StorageNotGranted()
        if (target.exists()) throw AppError.StorageFailed("target already exists: ${target.name}")
        target.parentFile?.mkdirs()
        if (!from.copyRecursively(target, overwrite = false)) {
            target.deleteRecursively()
            throw AppError.StorageFailed("cannot copy ${from.name}")
        }
    }

    override suspend fun delete(kind: MediaKind, path: String) {
        resolve(kind, path)?.deleteRecursively()
    }

    override fun uriOf(kind: MediaKind, path: String): String? = resolve(kind, path)?.toURI()?.toString()

    companion object {
        const val STAGING_FOLDER = "staging"
    }
}

/**
 * Download records in memory. [Download.fileMissing] is decided on every read: by [storage]
 * when given (paths are relative to the root of the download's kind), otherwise by
 * [fileExists], which by default checks the real file system.
 */
class FakeDownloadRepository(
    initial: List<Download> = emptyList(),
    private val fileExists: (String) -> Boolean = { File(it).exists() },
    private val storage: FileStorage? = null,
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
        return download.copy(fileMissing = download.paths.none { storage?.exists(download.kind, it) ?: fileExists(it) })
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
