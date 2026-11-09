package ru.tinyops.android.bookrecon.download.seeding

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.download.DownloadJobService

/**
 * The seeding job: a regular (not user-initiated) job that runs only while the device is
 * charging on an unmetered network. It is served by [DownloadJobService] under its own ID,
 * so no extra service is declared. The job is persisted, so seeding continues after a reboot
 * once the conditions are met, without opening the app.
 */
object SeedingJobs {
    const val JOB_ID = 1002
    private const val TAG = "SeedingJobs"

    /** True from the job's start until it decides to finish; new seeding is picked up by it. */
    @Volatile
    var running: Boolean = false

    /** Schedules the job unless it already runs or waits for its conditions. */
    fun schedule(context: Context) {
        if (running) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true)
            .build()
        val scheduled = try {
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (e: IllegalStateException) {
            Log.w(TAG, "The system refused to schedule the seeding job", e)
            false
        }
        if (scheduled) Log.i(TAG, "Scheduled the seeding job")
    }
}

/**
 * What the seeding job does while it runs: allows seeding until nothing is left to seed.
 * When the system stops it (charger or Wi-Fi lost, or its time limit), seeding is paused and
 * the job asks to be run again once the conditions are back.
 */
class SeedingJobRunner(private val manager: SeedingManager, private val scope: CoroutineScope) {
    private var work: Job? = null

    /** Starts seeding; [finish] is called once nothing is seeding any more. */
    fun start(finish: () -> Unit) {
        work?.cancel()
        SeedingJobs.running = true
        Log.i(TAG, "Seeding job started")
        work = scope.launch {
            manager.setAllowed(true)
            // New seeding may arrive between going idle and finishing; it is either seen here
            // or schedules the job anew once running is false.
            while (true) {
                manager.hasSeeding.first { !it }
                SeedingJobs.running = false
                if (!manager.hasSeedingNow()) break
                SeedingJobs.running = true
            }
            manager.setAllowed(false)
            Log.i(TAG, "Nothing left to seed, finishing the seeding job")
            finish()
        }
    }

    /** The system stopped the job: pause seeding. Returns true to have the job run again. */
    fun stop(): Boolean {
        work?.cancel()
        work = null
        SeedingJobs.running = false
        manager.setAllowed(false)
        return true
    }

    private companion object {
        const val TAG = "SeedingJob"
    }
}
