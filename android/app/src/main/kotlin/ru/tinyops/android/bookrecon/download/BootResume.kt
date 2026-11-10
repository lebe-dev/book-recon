package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.CancellationException

/** What happened to the download queue after a reboot. */
enum class DownloadsOnBoot {
    /** Nothing was queued or downloading. */
    NOTHING,

    /** The persisted download job survived the reboot; the system starts it on its own. */
    JOB_KEPT,

    /** The job was lost and the system let us schedule it again. */
    SCHEDULED,

    /** The job was lost and may be scheduled only while the app is on screen. */
    WAITS_FOR_APP,
}

data class BootOutcome(val downloads: DownloadsOnBoot, val seeding: Boolean)

/**
 * Brings downloads and seeding back after a reboot, without the app on screen.
 *
 * Both jobs are persisted, so normally the system restores them itself and this only
 * confirms it. A job can still be missing, for example one scheduled by an older version
 * that did not persist it. The seeding job is a regular job and may be scheduled from the
 * background. The download job is user-initiated: the system allows scheduling it only while
 * the app is visible, so if it is lost, downloads wait until the app is opened.
 */
class BootResume(
    private val hasWork: suspend () -> Boolean,
    private val hasSeeding: suspend () -> Boolean,
    private val downloadJobPending: () -> Boolean,
    private val scheduleDownloads: suspend () -> Boolean,
    private val scheduleSeeding: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    suspend fun run(): BootOutcome {
        val downloads = resumeDownloads()
        val seeding = resumeSeeding()
        return BootOutcome(downloads, seeding)
    }

    private suspend fun resumeDownloads(): DownloadsOnBoot {
        val work = attempt("check the download queue") { hasWork() } ?: return DownloadsOnBoot.NOTHING
        if (!work) return DownloadsOnBoot.NOTHING
        if (downloadJobPending()) {
            log("The download job survived the reboot and continues on its own")
            return DownloadsOnBoot.JOB_KEPT
        }
        if (attempt("schedule the download job") { scheduleDownloads() } == true) {
            log("Scheduled the download job after the reboot")
            return DownloadsOnBoot.SCHEDULED
        }
        log("The download job cannot be scheduled from the background; downloads continue once the app is opened")
        return DownloadsOnBoot.WAITS_FOR_APP
    }

    private suspend fun resumeSeeding(): Boolean {
        val seeding = attempt("check seeding downloads") { hasSeeding() } ?: return false
        if (!seeding) return false
        scheduleSeeding()
        log("Seeding resumes after the reboot once the device is charging on Wi-Fi")
        return true
    }

    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Cannot $what after the reboot: ${e.message}")
        null
    }
}
