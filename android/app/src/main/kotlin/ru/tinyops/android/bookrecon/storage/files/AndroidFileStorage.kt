package ru.tinyops.android.bookrecon.storage.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    /** The app holds no grant on the folder. */
    NO_ACCESS,

    /** The folder does not exist any more. */
    MISSING,

    /** The folder exists but a file cannot be created in it. */
    NOT_WRITABLE,
}

/**
 * [FileStorage] over document tree grants. The app has no all-files access, so the two roots
 * picked by the user are the only places on shared storage it can read or write. Downloads
 * are staged in [stagingBase], a private folder of the app, and copied into a root once
 * complete.
 *
 * Library paths are relative to the root of their kind. Records written by version 0.9.0 and
 * older hold absolute paths; those under the folder of the current root are read as relative
 * paths, others count as missing.
 *
 * Reads of settings block briefly (`runBlocking`), so call the query methods off the main
 * thread.
 *
 * @param primaryRoot the primary shared storage volume, to read old absolute paths.
 */
class AndroidFileStorage(
    private val settings: SettingsRepository,
    private val tree: DocumentTree,
    private val stagingBase: File,
    private val primaryRoot: String = TreeUris.DEFAULT_PRIMARY_ROOT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileStorage {

    override suspend fun requireRoot(kind: MediaKind) {
        val root = rootUri(kind) ?: throw AppError.RootNotConfigured(kind)
        withContext(io) {
            if (!tree.hasGrant(root)) throw AppError.StorageNotGranted()
            if (!tree.isFolder(root, "")) throw AppError.RootNotConfigured(kind)
        }
    }

    override suspend fun stagingDir(jobId: String): File {
        val dir = File(stagingBase, jobId)
        withContext(io) {
            if (!dir.isDirectory && !dir.mkdirs()) throw AppError.StorageFailed("cannot create the staging folder for $jobId")
        }
        return dir
    }

    override suspend fun deleteStaging(jobId: String) {
        withContext(io) { File(stagingBase, jobId).deleteRecursively() }
    }

    override fun exists(kind: MediaKind, path: String): Boolean {
        val (root, relative) = usable(kind, path) ?: return false
        return tree.exists(root, relative)
    }

    override fun isFolder(kind: MediaKind, path: String): Boolean {
        val (root, relative) = usable(kind, path) ?: return false
        return tree.isFolder(root, relative)
    }

    override fun listFiles(kind: MediaKind, path: String): List<String> {
        val (root, relative) = usable(kind, path) ?: return emptyList()
        return tree.listFiles(root, relative)
    }

    override suspend fun copyInto(kind: MediaKind, from: File, to: String) {
        val relative = relativeOf(kind, to)?.takeIf { it.isNotEmpty() } ?: throw AppError.Validation("invalid library path")
        requireRoot(kind)
        val root = rootUri(kind) ?: throw AppError.RootNotConfigured(kind)
        withContext(io) {
            if (tree.exists(root, relative)) throw AppError.StorageFailed("target already exists: ${relative.substringAfterLast('/')}")
            try {
                tree.copyInto(root, from, relative)
            } catch (e: IOException) {
                throw AppError.StorageFailed("cannot copy ${from.name}", e)
            } catch (e: SecurityException) {
                throw AppError.StorageFailed("cannot copy ${from.name}", e)
            }
        }
    }

    override suspend fun delete(kind: MediaKind, path: String) {
        withContext(io) {
            val (root, relative) = usable(kind, path) ?: return@withContext
            // The root itself is never deleted.
            if (relative.isEmpty()) return@withContext
            tree.delete(root, relative)
        }
    }

    override fun uriOf(kind: MediaKind, path: String): String? {
        val (root, relative) = usable(kind, path) ?: return null
        return tree.uriOf(root, relative)
    }

    /**
     * Checks a folder picked as a root: the app must hold a grant, the folder must exist and
     * accept new files. The check creates and removes a hidden probe file.
     */
    suspend fun checkRoot(root: String): RootCheck = withContext(io) {
        if (!tree.hasGrant(root)) return@withContext RootCheck.NO_ACCESS
        if (!tree.isFolder(root, "")) return@withContext RootCheck.MISSING
        val name = ".book-recon-probe-${UUID.randomUUID()}"
        val probe = File(stagingBase.apply { mkdirs() }, name)
        try {
            probe.writeText("")
            tree.copyInto(root, probe, name)
            tree.delete(root, name)
            RootCheck.OK
        } catch (_: IOException) {
            RootCheck.NOT_WRITABLE
        } catch (_: SecurityException) {
            RootCheck.NOT_WRITABLE
        } finally {
            probe.delete()
        }
    }

    private fun rootUri(kind: MediaKind): String? = runBlocking { settings.settings.first().rootFor(kind) }

    /** The root of [kind] and the relative form of [path], null unless the root can be used. */
    private fun usable(kind: MediaKind, path: String): Pair<String, String>? {
        val root = rootUri(kind) ?: return null
        val relative = relativeOf(kind, path, root) ?: return null
        if (!tree.hasGrant(root)) return null
        return root to relative
    }

    private fun relativeOf(kind: MediaKind, path: String, root: String? = rootUri(kind)): String? {
        val inRoot = if (path.startsWith("/")) underRootFolder(path, root) ?: return null else path
        val segments = inRoot.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** [path] relative to the folder of [root], for absolute paths of old records. */
    private fun underRootFolder(path: String, root: String?): String? {
        val folder = root?.let { TreeUris.pathOf(it, primaryRoot) }?.trimEnd('/') ?: return null
        if (!path.startsWith("$folder/")) return null
        return path.removePrefix("$folder/")
    }
}
