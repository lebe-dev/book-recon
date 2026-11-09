package ru.tinyops.android.bookrecon.download

import android.Manifest
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tinyops.android.bookrecon.download.seeding.SeedingJobs

/** The jobs survive a reboot and the app hears about the reboot itself. */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 fails to set up SDK 36+ on JDK 21 (ApplicationSharedMemory), so pin 35.
@Config(sdk = [35])
class BootSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @After
    fun tearDown() {
        scheduler.cancelAll()
        DownloadJobs.running = false
        SeedingJobs.running = false
    }

    @Test
    fun downloadJobIsPersistedUserInitiatedJob() {
        assertTrue(DownloadJobs.schedule(context, wifiOnly = false))

        val job = scheduler.getPendingJob(DownloadJobs.JOB_ID)
        assertNotNull(job)
        assertTrue(job!!.isPersisted)
        assertTrue(job.isUserInitiated)
        assertTrue(DownloadJobs.isPending(context))
    }

    @Test
    fun replacedDownloadJobStaysPersisted() {
        DownloadJobs.schedule(context, wifiOnly = false)

        DownloadJobs.updateNetwork(context, wifiOnly = true)

        val job = scheduler.getPendingJob(DownloadJobs.JOB_ID)!!
        assertTrue(job.isPersisted)
        assertNotNull(job.requiredNetwork)
    }

    @Test
    fun noDownloadJobIsNotPending() {
        assertEquals(false, DownloadJobs.isPending(context))
    }

    @Test
    fun seedingJobIsPersisted() {
        SeedingJobs.schedule(context)

        val job = scheduler.getPendingJob(SeedingJobs.JOB_ID)
        assertNotNull(job)
        assertTrue(job!!.isPersisted)
        assertTrue(job.isRequireCharging)
    }

    @Test
    fun manifestRequestsBootPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in info.requestedPermissions.orEmpty())
    }

    @Test
    fun bootReceiverIsDeclared() {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName)

        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertEquals(listOf(BootReceiver::class.java.name), receivers.map { it.activityInfo.name })
    }
}
