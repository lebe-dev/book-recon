package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.port.TorrentEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Torrent engine that understands [FakeData.torrentBytes]. A download emits [steps] progress
 * updates [stepDelay] apart and then writes a small placeholder for each selected file into
 * the staging folder, so moving files around can be tested on a real file system.
 *
 * [behavior] is consulted when a download starts; [failAtStep] makes the next download
 * throw that error midway.
 */
class FakeTorrentEngine(
    val behavior: FakeBehavior = FakeBehavior(),
    private val steps: Int = 5,
    private val stepDelay: Duration = 200.milliseconds,
) : TorrentEngine {

    /** What the engine knows about a job. */
    enum class JobState { DOWNLOADING, PAUSED, DONE, SEEDING, SEEDING_PAUSED }

    private class Job(val info: TorrentInfo, val bytes: ByteArray, val indexes: Set<Int>, var dir: File) {
        @Volatile
        var state: JobState = JobState.DOWNLOADING

        @Volatile
        var seedDuration: Duration? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Error thrown by the next download halfway through, then reset. */
    @Volatile
    var failAtStep: AppError? = null

    fun state(jobId: String): JobState? = jobs[jobId]?.state

    /** Duration passed to [seed] for [jobId], null if it is not seeding. */
    fun seedDuration(jobId: String): Duration? = jobs[jobId]?.seedDuration

    fun dataDir(jobId: String): File? = jobs[jobId]?.dir

    override fun parse(bytes: ByteArray): TorrentInfo {
        val topicId = FakeData.topicIdOf(bytes) ?: throw AppError.ParseError("not a fake torrent")
        return FakeData.torrentInfo(topicId)
    }

    override fun download(jobId: String, bytes: ByteArray, indexes: Set<Int>, stagingDir: File): Flow<DownloadProgress> {
        val info = parse(bytes)
        return run(jobId) { Job(info, bytes, indexes, stagingDir) }
    }

    override fun resume(jobId: String): Flow<DownloadProgress> {
        val job = jobs[jobId] ?: throw AppError.NotFound("no resume data for job $jobId")
        return run(jobId) { job }
    }

    private fun run(jobId: String, create: () -> Job): Flow<DownloadProgress> = flow {
        val job = create()
        val busy = jobs.entries.any { (id, other) ->
            id != jobId && other.info.infoHash == job.info.infoHash && other.state == JobState.DOWNLOADING
        }
        if (busy) throw AppError.AlreadyDownloading()
        behavior.step()
        job.state = JobState.DOWNLOADING
        jobs[jobId] = job

        val total = job.info.files.filter { it.index in job.indexes }.sumOf { it.size }
        try {
            emit(DownloadProgress(0, total, peers = 0, speedBytesPerSec = 0))
            for (step in 1..steps) {
                delay(stepDelay)
                if (step == steps / 2 + 1) failAtStep?.let { failAtStep = null; throw it }
                val completed = total * step / steps
                emit(DownloadProgress(completed, total, peers = 4, speedBytesPerSec = total / steps))
            }
            writeFiles(job)
            job.state = JobState.DONE
        } finally {
            if (job.state == JobState.DOWNLOADING) job.state = JobState.PAUSED
        }
    }

    private fun writeFiles(job: Job) {
        job.info.files.filter { it.index in job.indexes }.forEach { file ->
            val target = File(job.dir, file.path)
            target.parentFile?.mkdirs()
            target.writeText("${file.path}\n")
        }
    }

    override suspend fun cancel(jobId: String) {
        jobs.remove(jobId)
    }

    override suspend fun seed(jobId: String, duration: Duration) {
        val job = require(jobId)
        job.state = JobState.SEEDING
        job.seedDuration = duration
    }

    override suspend fun pauseSeeding(jobId: String) {
        val job = require(jobId)
        if (job.state == JobState.SEEDING) job.state = JobState.SEEDING_PAUSED
    }

    override suspend fun resumeSeeding(jobId: String) {
        val job = require(jobId)
        if (job.state == JobState.SEEDING_PAUSED) job.state = JobState.SEEDING
    }

    override suspend fun moveStorage(jobId: String, dir: File) {
        val job = require(jobId)
        val root = job.info.files.firstOrNull()?.path?.substringBefore('/') ?: return
        val from = File(job.dir, root)
        if (from.exists()) {
            dir.mkdirs()
            if (!from.renameTo(File(dir, root))) throw AppError.StorageFailed("cannot move torrent data")
        }
        job.dir = dir
    }

    private fun require(jobId: String): Job = jobs[jobId] ?: throw AppError.NotFound("unknown job $jobId")
}
