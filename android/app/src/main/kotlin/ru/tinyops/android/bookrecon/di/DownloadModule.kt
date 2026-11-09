package ru.tinyops.android.bookrecon.di

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import ru.tinyops.android.bookrecon.download.BootResume
import ru.tinyops.android.bookrecon.download.DownloadJobs
import ru.tinyops.android.bookrecon.download.DownloadNotifications
import ru.tinyops.android.bookrecon.download.DownloadQueueImpl
import ru.tinyops.android.bookrecon.download.seeding.SeedingJobs
import ru.tinyops.android.bookrecon.download.seeding.SeedingManager
import java.io.File

class DownloadModule(private val app: AppContainer) {
    /** Lives as long as the process; downloads must outlive any screen. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** Seeding after download; its job runs on Wi-Fi and charger only. */
    val seeding: SeedingManager by lazy {
        SeedingManager(
            repository = app.database.downloadRepository,
            settings = app.settings.settingsRepository,
            storage = app.settings.fileStorage,
            torrents = app.torrent.torrentEngine,
            scope = scope,
            stopSeeding = { queue.stopSeeding(it) },
            onSeeding = { SeedingJobs.schedule(app.context) },
            log = { Log.i(SEEDING_TAG, it) },
        )
    }

    val queue: DownloadQueueImpl by lazy {
        DownloadQueueImpl(
            repository = app.database.downloadRepository,
            settings = app.settings.settingsRepository,
            storage = app.settings.fileStorage,
            flibusta = app.flibusta.flibustaSource,
            torrents = app.torrent.torrentEngine,
            torrentStore = File(app.context.filesDir, "torrents"),
            scope = scope,
            seeding = seeding,
            onWork = ::scheduleJob,
            log = { Log.i(TAG, it) },
        ).also { queue ->
            scope.launch {
                DownloadNotifications.ensureChannel(app.context)
                queue.events.collect { DownloadNotifications.showResult(app.context, it) }
            }
            scope.launch { applySettingChanges(queue) }
        }
    }

    val downloadQueue: DownloadQueue get() = queue

    /**
     * Called when the app comes on screen: puts downloads interrupted by a process death back
     * in the queue and schedules the job for them while the system allows it.
     */
    fun onAppVisible() {
        scope.launch {
            if (queue.recover()) scheduleJob()
        }
    }

    /**
     * Called after a reboot, with the app not on screen: confirms the persisted jobs are still
     * there and schedules what the system allows from the background.
     */
    suspend fun onBoot() {
        BootResume(
            hasWork = { queue.hasWorkNow() },
            hasSeeding = { seeding.hasSeedingNow() },
            downloadJobPending = { DownloadJobs.isPending(app.context) },
            scheduleDownloads = {
                val wifiOnly = app.settings.settingsRepository.settings.first().wifiOnly
                DownloadJobs.schedule(app.context, wifiOnly)
            },
            scheduleSeeding = { SeedingJobs.schedule(app.context) },
            log = { Log.i(BOOT_TAG, it) },
        ).run()
    }

    @Volatile
    private var pausing: Job? = null

    /** Pauses the queue in the background, for a job the system stopped. */
    fun pauseQueue() {
        pausing = scope.launch { queue.pause() }
    }

    /** Waits for a pause started by [pauseQueue], so a new job's start comes after it. */
    suspend fun awaitPause() {
        pausing?.join()
    }

    /**
     * Settings that the queue and its job read only at certain moments take effect right
     * away: a raised parallel limit starts waiting downloads, a changed "Wi-Fi only" setting
     * replaces the job's network constraint.
     */
    private suspend fun applySettingChanges(queue: DownloadQueueImpl) {
        val settings = app.settings.settingsRepository.settings
        coroutineScope {
            launch {
                settings.map { it.maxConcurrent }.distinctUntilChanged().drop(1).collect { queue.reschedule() }
            }
            launch {
                settings.map { it.wifiOnly }.distinctUntilChanged().drop(1).collect { wifiOnly ->
                    DownloadJobs.updateNetwork(app.context, wifiOnly)
                }
            }
        }
    }

    private fun scheduleJob() {
        scope.launch {
            val wifiOnly = app.settings.settingsRepository.settings.first().wifiOnly
            DownloadJobs.schedule(app.context, wifiOnly)
        }
    }

    private companion object {
        const val TAG = "DownloadQueue"
        const val SEEDING_TAG = "Seeding"
        const val BOOT_TAG = "BootResume"
    }
}
