package ru.tinyops.android.bookrecon.download

import android.app.job.JobParameters
import android.app.job.JobService

/** Runs queued downloads as user-initiated data transfer jobs. */
class DownloadJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean = false

    override fun onStopJob(params: JobParameters): Boolean = false
}
