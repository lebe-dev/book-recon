package ru.tinyops.android.bookrecon.storage.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.UUID

/** Result of checking a folder the user picked as a root. */
enum class RootCheck {
    OK,

    /** The app has no all-files access, so nothing can be checked. */
    NO_ACCESS,

    /** The path does not exist or is not a folder. */
    MISSING,

    /** The folder exists but a file cannot be created in it. */
    NOT_WRITABLE,
}

/**
 * [FileStorage] over plain paths on shared storage. Roots come from settings; all-files
 * access makes them readable and writable as ordinary files.
 *
 * @param hasAccess whether the app holds all-files access (`Environment.isExternalStorageManager`).
 * @param rename the rename step of [move]; replaced in tests to force the copy fallback.
 */
class AndroidFileStorage(
    private val settings: SettingsRepository,
    private val hasAccess: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
) : FileStorage {

    override fun hasAllFilesAccess(): Boolean = hasAccess()

    override suspend fun root(kind: MediaKind): File {
        if (!hasAccess()) throw AppError.StorageNotGranted()
        val path = settings.settings.first().rootFor(kind) ?: throw AppError.RootNotConfigured(kind)
        val root = File(path)
        val present = withContext(io) { root.isDirectory }
        if (!present) throw AppError.RootNotConfigured(kind)
        return root
    }

    override suspend fun stagingDir(kind: MediaKind, jobId: String): File {
        val staging = File(root(kind), STAGING_FOLDER)
        val dir = File(staging, jobId)
        withContext(io) {
            if (!dir.isDirectory && !dir.mkdirs()) throw AppError.StorageFailed("cannot create the staging folder for $jobId")
            val nomedia = File(staging, NOMEDIA)
            try {
                nomedia.createNewFile()
            } catch (e: IOException) {
                throw AppError.StorageFailed("cannot create ${nomedia.name} in the staging folder", e)
            }
        }
        return dir
    }

    override fun exists(file: File): Boolean = file.exists()

    override suspend fun move(from: File, to: File) = withContext(io) {
        if (to.exists()) throw AppError.StorageFailed("target already exists: ${to.name}")
        if (!from.exists()) throw AppError.StorageFailed("nothing to move: ${from.name}")
        val parent = to.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw AppError.StorageFailed("cannot create the folder for ${to.name}")
        }
        if (rename(from, to)) return@withContext
        copyAndDelete(from, to)
    }

    override suspend fun delete(file: File) {
        withContext(io) { file.deleteRecursively() }
    }

    /**
     * Checks a folder picked as a root: it must exist and accept new files. The check
     * creates and removes a hidden probe file.
     */
    suspend fun checkRoot(path: String): RootCheck {
        if (!hasAccess()) return RootCheck.NO_ACCESS
        return withContext(io) {
            val root = File(path)
            if (!root.isDirectory) return@withContext RootCheck.MISSING
            val probe = File(root, ".book-recon-probe-${UUID.randomUUID()}")
            val created = try {
                probe.createNewFile()
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
            probe.delete()
            if (created) RootCheck.OK else RootCheck.NOT_WRITABLE
        }
    }

    /** Copies across file systems; on failure removes the partial copy and keeps the source. */
    private fun copyAndDelete(from: File, to: File) {
        val copied = try {
            from.copyRecursively(to, overwrite = false)
        } catch (e: Exception) {
            to.deleteRecursively()
            throw AppError.StorageFailed("cannot copy ${from.name}", e)
        }
        if (!copied) {
            to.deleteRecursively()
            throw AppError.StorageFailed("cannot copy ${from.name}")
        }
        // The copy is complete, so a leftover original is not worth failing the move: it
        // sits in the staging folder, which the caller removes anyway.
        from.deleteRecursively()
    }

    companion object {
        const val STAGING_FOLDER = ".book-recon"
        const val NOMEDIA = ".nomedia"
    }
}
