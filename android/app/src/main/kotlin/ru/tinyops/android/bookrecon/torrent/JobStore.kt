package ru.tinyops.android.bookrecon.torrent

import ru.tinyops.android.bookrecon.core.domain.AppError
import java.io.File
import java.io.IOException
import java.util.Properties

/** What the engine needs to restart a torrent job after the process dies. */
internal class StoredJob(
    val id: String,
    val torrent: ByteArray,
    val indexes: Set<Int>,
    val stagingDir: File,
)

/**
 * Keeps one folder per job in the app's private storage: the `.torrent`, the selection and
 * staging folder, and libtorrent resume data. Writes go through a temporary file and a
 * rename, so a crash never leaves a half-written file behind.
 */
internal class JobStore(private val dir: File) {
    fun create(job: StoredJob) {
        val jobDir = jobDir(job.id)
        jobDir.deleteRecursively()
        if (!jobDir.mkdirs()) throw AppError.StorageFailed("cannot create torrent state folder")
        val props = Properties().apply {
            setProperty(KEY_STAGING, job.stagingDir.absolutePath)
            setProperty(KEY_INDEXES, job.indexes.sorted().joinToString(","))
        }
        write(File(jobDir, TORRENT), job.torrent)
        write(File(jobDir, META), propsBytes(props))
    }

    fun load(id: String): StoredJob? {
        val jobDir = jobDir(id)
        val torrent = File(jobDir, TORRENT).takeIf { it.isFile } ?: return null
        val meta = File(jobDir, META).takeIf { it.isFile } ?: return null
        return try {
            val props = Properties().apply { meta.reader().use { load(it) } }
            val staging = props.getProperty(KEY_STAGING) ?: return null
            val indexes = props.getProperty(KEY_INDEXES).orEmpty().split(',').filter { it.isNotBlank() }
                .map { it.trim().toIntOrNull() ?: return null }
                .toSet()
            StoredJob(id, torrent.readBytes(), indexes, File(staging))
        } catch (_: IOException) {
            null
        }
    }

    /** Saves resume data of an existing job; does nothing once the job is deleted. */
    fun saveResume(id: String, data: ByteArray) {
        val jobDir = jobDir(id)
        if (!jobDir.isDirectory) return
        write(File(jobDir, RESUME), data)
    }

    fun loadResume(id: String): ByteArray? = File(jobDir(id), RESUME).takeIf { it.isFile }?.readBytes()

    fun delete(id: String) {
        jobDir(id).deleteRecursively()
    }

    private fun jobDir(id: String): File {
        val safe = id.isNotEmpty() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == '\u0000' }
        if (!safe) throw AppError.Validation("invalid job id")
        return File(dir, id)
    }

    private fun write(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            throw AppError.StorageFailed("cannot write torrent state", e)
        }
    }

    private fun propsBytes(props: Properties): ByteArray =
        java.io.ByteArrayOutputStream().also { out -> props.store(out, null) }.toByteArray()

    private companion object {
        const val TORRENT = "job.torrent"
        const val META = "job.properties"
        const val RESUME = "resume.dat"
        const val KEY_STAGING = "stagingDir"
        const val KEY_INDEXES = "indexes"
    }
}
