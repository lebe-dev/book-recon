package ru.tinyops.android.bookrecon.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.download.seeding.SeedingJobRunner
import ru.tinyops.android.bookrecon.download.seeding.SeedingJobs
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the download queue as a user-initiated data transfer job, and the seeding job
 * ([SeedingJobs.JOB_ID]) under the same service. The job starts the queue,
 * keeps the progress notification up to date and finishes once nothing is queued or
 * downloading. When the system stops it (network lost, user stop), running downloads go back
 * to the queue and the job asks to be rescheduled.
 */
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var cancelReceiver: BroadcastReceiver? = null
    private val seeding by lazy { SeedingJobRunner(appContainer.download.seeding, appContainer.download.scope) }

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId == SeedingJobs.JOB_ID) {
            seeding.start { jobFinished(params, false) }
            return true
        }
        val module = appContainer.download
        DownloadJobs.running = true
        DownloadNotifications.ensureChannel(this)
        showProgress(params, QueueSummary.EMPTY)
        registerCancelReceiver()
        Log.i(TAG, "Download job started")

        work = scope.launch {
            val queue = module.queue
            // A replaced job is stopped right before this one starts; its pause must not
            // land after the start below, or the queue would sit paused with work in it.
            module.awaitPause()
            queue.start()
            val updates = launch {
                queue.observe().map(::summarize).distinctUntilChanged().conflate().collect { summary ->
                    showProgress(params, summary)
                    delay(NOTIFICATION_INTERVAL)
                }
            }
            // New work may arrive between the queue going idle and the job finishing; it is
            // either seen here or scheduled anew once running is false.
            while (true) {
                queue.hasWork.first { !it }
                DownloadJobs.running = false
                if (!queue.hasWorkNow()) break
                DownloadJobs.running = true
            }
            updates.cancel()
            queue.pause()
            Log.i(TAG, "Download queue is empty, finishing the job")
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (params.jobId == SeedingJobs.JOB_ID) {
            Log.i(TAG, "Seeding job stopped by the system, reason ${params.stopReason}")
            return seeding.stop()
        }
        Log.i(TAG, "Download job stopped by the system, reason ${params.stopReason}")
        work?.cancel()
        DownloadJobs.running = false
        appContainer.download.pauseQueue()
        return true
    }

    override fun onDestroy() {
        cancelReceiver?.let { unregisterReceiver(it) }
        cancelReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    private fun showProgress(params: JobParameters, summary: QueueSummary) {
        setNotification(
            params,
            DownloadNotifications.PROGRESS_ID,
            DownloadNotifications.progress(this, summary),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
    }

    private fun registerCancelReceiver() {
        if (cancelReceiver != null) return
        val module = appContainer.download
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getStringExtra(DownloadNotifications.EXTRA_DOWNLOAD_ID) ?: return
                Log.i(TAG, "Cancel requested from the notification for download $id")
                module.scope.launch { module.queue.cancel(id) }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(DownloadNotifications.ACTION_CANCEL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cancelReceiver = receiver
    }

    private companion object {
        const val TAG = "DownloadJob"
        val NOTIFICATION_INTERVAL = 1.seconds
    }
}
