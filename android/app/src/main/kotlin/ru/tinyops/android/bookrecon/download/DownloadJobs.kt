package ru.tinyops.android.bookrecon.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Schedules the single user-initiated data transfer job that runs the download queue. The job
 * is persisted: a reboot in the middle of a download does not lose it, and the system starts
 * it again once the network is up, with no need to open the app.
 */
object DownloadJobs {
    const val JOB_ID = 1001
    private const val TAG = "DownloadJobs"

    /**
     * True from the job's start until it decides to finish. While it runs, new work is picked
     * up by the running job, so nothing is scheduled.
     */
    @Volatile
    var running: Boolean = false

    /**
     * Schedules the job unless it already runs or waits for its network. Allowed only while
     * the app is on screen; returns false when the system refused.
     */
    fun schedule(context: Context, wifiOnly: Boolean): Boolean {
        if (isPending(context)) return true
        return scheduleJob(context, context.getSystemService(JobScheduler::class.java), wifiOnly)
    }

    /** True when the job runs or waits for its network, for example after a reboot. */
    fun isPending(context: Context): Boolean =
        running || context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    /**
     * Applies a changed "Wi-Fi only" setting to the job that is already scheduled or running:
     * the job is replaced by one with the new network constraint. A running job is stopped by
     * the system first, which puts its downloads back in the queue; they continue once the
     * new job may run. Nothing happens when there is no job.
     */
    fun updateNetwork(context: Context, wifiOnly: Boolean) {
        if (!isPending(context)) return
        Log.i(TAG, "The Wi-Fi only setting changed, rescheduling the download job")
        scheduleJob(context, context.getSystemService(JobScheduler::class.java), wifiOnly)
    }

    private fun scheduleJob(context: Context, scheduler: JobScheduler, wifiOnly: Boolean): Boolean {
        val networkType = if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(networkType)
            .setPersisted(true)
            .build()
        val scheduled = try {
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (e: IllegalStateException) {
            Log.w(TAG, "The system refused to schedule the download job", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "The download job is not allowed", e)
            false
        }
        if (scheduled) Log.i(TAG, "Scheduled the download job, Wi-Fi only: $wifiOnly")
        return scheduled
    }
}
