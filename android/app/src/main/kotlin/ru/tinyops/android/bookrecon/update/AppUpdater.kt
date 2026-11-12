package ru.tinyops.android.bookrecon.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.core.update.ApkAsset
import ru.tinyops.android.bookrecon.core.update.AppVersion
import ru.tinyops.android.bookrecon.core.update.Release
import ru.tinyops.android.bookrecon.core.update.UpdateCheck
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads release APKs into a private folder. */
interface ApkDownloader {
    /**
     * Downloads [asset] and checks its size and SHA-256 against the release. [onProgress]
     * gets the bytes read so far. Throws [ApkCorrupted] when the file does not match, and
     * [AppError.Network], [AppError.Timeout], [AppError.ServiceDown] or [AppError.StorageFailed].
     */
    suspend fun download(asset: ApkAsset, onProgress: (Long) -> Unit): File

    /** Deletes downloaded APKs. */
    fun clear()
}

/** Hands an APK to the system package installer. */
interface ApkInstaller {
    /** True when the user allowed this app to install apps. */
    fun canInstall(): Boolean

    /**
     * Starts the install; the system answers through [AppUpdater.onInstallResult]. Throws
     * [IOException] or [SecurityException] when the system refuses the session.
     */
    suspend fun install(apk: File)
}

/** The downloaded APK does not match the release: wrong size or SHA-256. */
class ApkCorrupted(message: String) : Exception(message)

/** What the system installer answered. */
enum class InstallResult {
    /** The user declined the install. */
    ABORTED,

    /** The APK is signed with another key (a debug build is installed) or is older. */
    CONFLICT,

    /** Not enough space for the install. */
    STORAGE,
    FAILED,
}

/** Why an update step failed; the UI shows a Russian message for each. */
enum class UpdateFailure {
    NETWORK,
    TIMEOUT,
    SERVICE_DOWN,
    NO_RELEASES,
    BAD_ANSWER,
    CORRUPTED,
    STORAGE,
    SIGNATURE,
    INSTALL_FAILED,
    UNKNOWN,
}

/** Where the update is now. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val latest: AppVersion) : UpdateState

    data class Available(val release: Release) : UpdateState

    /** [downloaded] bytes of `release.apk.size`. */
    data class Downloading(val release: Release, val downloaded: Long) : UpdateState

    /** The APK is downloaded; the user has to allow installs from this app first. */
    data class NeedsPermission(val release: Release) : UpdateState

    /** The system installer has the APK and asks the user to confirm. */
    data class Installing(val release: Release) : UpdateState

    /** [release] is null when the check itself failed. */
    data class Failed(val failure: UpdateFailure, val release: Release?) : UpdateState
}

/** The release this state is about; null before a release is found. */
val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.NeedsPermission -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        UpdateState.Idle, UpdateState.Checking, is UpdateState.UpToDate -> null
    }

/**
 * Checks GitHub Releases for a newer version, downloads its APK and installs it. Lives as
 * long as the process, so a download goes on when the user leaves the updates page.
 *
 * The start check ([checkOnStart]) runs once per process and only when
 * [ru.tinyops.android.bookrecon.core.domain.Settings.autoCheckUpdates] is on; it stays quiet on
 * failure and offers a release in [prompt] unless the user skipped that version. A check by
 * hand ([check]) shows every result and ignores the skipped version.
 */
class AppUpdater(
    private val fetchLatest: suspend () -> UpdateCheck,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val info: (String) -> Unit = {},
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val mutablePrompt = MutableStateFlow<Release?>(null)

    /** A release the start check offers in a dialog; null when there is nothing to offer. */
    val prompt: StateFlow<Release?> = mutablePrompt.asStateFlow()

    private val startChecked = AtomicBoolean(false)
    private var job: Job? = null
    private var downloaded: File? = null

    /** Checks in the background at app start, once per process, when the user turned it on. */
    fun checkOnStart() {
        if (!startChecked.compareAndSet(false, true)) return
        scope.launch {
            val stored = settings.settings.first()
            if (!stored.autoCheckUpdates) return@launch
            runCheck(quiet = true, skipped = stored.skippedUpdateVersion)
        }
    }

    /** The "check for updates" button. */
    fun check() {
        if (busy()) return
        job = scope.launch { runCheck(quiet = false, skipped = null) }
    }

    /** Downloads the offered release, if needed, and starts its install. */
    fun install() {
        val release = mutableState.value.release ?: return
        if (mutableState.value is UpdateState.Downloading || mutableState.value is UpdateState.Installing) return
        job = scope.launch { downloadAndInstall(release) }
    }

    /** Stops a running download. */
    fun cancel() {
        val downloading = mutableState.value as? UpdateState.Downloading ?: return
        job?.cancel()
        mutableState.value = UpdateState.Available(downloading.release)
        info("The update download was cancelled")
    }

    /** The app is shown again, maybe after the user allowed installs in the system settings. */
    fun onResume() {
        val waiting = mutableState.value as? UpdateState.NeedsPermission ?: return
        if (!installer.canInstall()) return
        job = scope.launch { downloadAndInstall(waiting.release) }
    }

    fun onInstallResult(result: InstallResult) {
        val installing = mutableState.value as? UpdateState.Installing ?: return
        val release = installing.release
        mutableState.value = when (result) {
            InstallResult.ABORTED -> UpdateState.Available(release)
            InstallResult.CONFLICT -> UpdateState.Failed(UpdateFailure.SIGNATURE, release)
            InstallResult.STORAGE -> UpdateState.Failed(UpdateFailure.STORAGE, release)
            InstallResult.FAILED -> UpdateState.Failed(UpdateFailure.INSTALL_FAILED, release)
        }
        info("The system installer answered $result for version ${release.version}")
    }

    /** "Later" in the dialog: offered again at the next start. */
    fun dismissPrompt() {
        mutablePrompt.value = null
    }

    /** "Skip this version" in the dialog. */
    fun skip(release: Release) {
        mutablePrompt.value = null
        scope.launch { settings.update { it.copy(skippedUpdateVersion = release.version.toString()) } }
    }

    /** "Update" in the dialog. */
    fun acceptPrompt() {
        mutablePrompt.value ?: return
        mutablePrompt.value = null
        install()
    }

    private fun busy(): Boolean = when (mutableState.value) {
        UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> true
        else -> false
    }

    private suspend fun runCheck(quiet: Boolean, skipped: String?) {
        val before = mutableState.value
        mutableState.value = UpdateState.Checking
        val result = try {
            fetchLatest()
        } catch (e: AppError) {
            warn("Cannot check for updates", e)
            mutableState.value = if (quiet) before else UpdateState.Failed(failureOf(e), null)
            return
        }
        when (result) {
            is UpdateCheck.UpToDate -> {
                mutableState.value = UpdateState.UpToDate(result.latest)
                downloaded = null
                downloader.clear()
            }
            is UpdateCheck.Available -> {
                info("Version ${result.release.version} is available")
                mutableState.value = UpdateState.Available(result.release)
                if (quiet && result.release.version.toString() != skipped) mutablePrompt.value = result.release
            }
        }
    }

    private suspend fun downloadAndInstall(release: Release) {
        val apk = try {
            downloaded ?: download(release)
        } catch (e: AppError) {
            failDownload(release, e)
            return
        } catch (e: ApkCorrupted) {
            failDownload(release, e)
            return
        }
        downloaded = apk
        if (!installer.canInstall()) {
            mutableState.value = UpdateState.NeedsPermission(release)
            return
        }
        mutableState.value = UpdateState.Installing(release)
        try {
            installer.install(apk)
        } catch (e: IOException) {
            failInstall(release, e)
        } catch (e: SecurityException) {
            failInstall(release, e)
        }
    }

    private fun failDownload(release: Release, error: Exception) {
        warn("Cannot download version ${release.version}", error)
        mutableState.value = UpdateState.Failed(failureOf(error), release)
    }

    private fun failInstall(release: Release, error: Exception) {
        warn("Cannot start the install of version ${release.version}", error)
        mutableState.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, release)
    }

    private suspend fun download(release: Release): File {
        val total = release.apk.size.coerceAtLeast(1)
        mutableState.value = UpdateState.Downloading(release, 0)
        var percent = 0L
        info("Downloading version ${release.version}")
        return downloader.download(release.apk) { bytes ->
            val now = bytes * PERCENT / total
            if (now == percent) return@download
            percent = now
            mutableState.value = UpdateState.Downloading(release, bytes)
        }
    }

    private companion object {
        const val PERCENT = 100

        fun failureOf(error: Exception): UpdateFailure = when (error) {
            is ApkCorrupted -> UpdateFailure.CORRUPTED
            is AppError.Network -> UpdateFailure.NETWORK
            is AppError.Timeout -> UpdateFailure.TIMEOUT
            is AppError.ServiceDown -> UpdateFailure.SERVICE_DOWN
            is AppError.NotFound -> UpdateFailure.NO_RELEASES
            is AppError.ParseError -> UpdateFailure.BAD_ANSWER
            is AppError.StorageFailed -> UpdateFailure.STORAGE
            else -> UpdateFailure.UNKNOWN
        }
    }
}
