package ru.tinyops.android.bookrecon.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.storage.files.RootCheck
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import ru.tinyops.android.bookrecon.ui.search.VerificationRequest

/** Progress of the "check login" button. */
sealed interface LoginCheck {
    data object Idle : LoginCheck

    /** Logging in, or waiting for the user to pass a check in the WebView. */
    data object Running : LoginCheck

    data object Success : LoginCheck

    data class Failed(val error: AppError) : LoginCheck
}

/** Why a picked folder was not accepted as a root. */
enum class RootIssue {
    /** The picker returned a folder that is not on shared storage (cloud, media collections). */
    UNSUPPORTED,
    NO_ACCESS,
    MISSING,
    NOT_WRITABLE,
}

data class RootProblem(val kind: MediaKind, val issue: RootIssue)

/**
 * State of the settings screen.
 *
 * @property settings the stored settings; switches and choices show these directly.
 * @property login text of the RuTracker login field, saved by [SettingsViewModel.onCheckLogin].
 * @property password text of the password field; empty keeps the stored password.
 * @property passwordStored a RuTracker password is saved.
 * @property flibustaUrl text of the Flibusta URL field, saved by [SettingsViewModel.onSaveFlibustaUrl].
 * @property rootStatus result of checking each chosen root; a root that is not chosen is absent.
 * @property rootProblem why the last picked folder was rejected, null when it was accepted.
 * @property pendingVerification a web check to open now; acknowledged with
 *   [SettingsViewModel.onVerificationOpened].
 */
data class SettingsUiState(
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val login: String = "",
    val password: String = "",
    val passwordStored: Boolean = false,
    val loginCheck: LoginCheck = LoginCheck.Idle,
    val flibustaUrl: String = "",
    val flibustaUrlError: Boolean = false,
    val rootStatus: Map<MediaKind, RootCheck> = emptyMap(),
    val rootProblem: RootProblem? = null,
    val pendingVerification: VerificationRequest? = null,
) {
    /** The URL field differs from the stored URL. */
    val flibustaUrlChanged: Boolean
        get() = flibustaUrl.trim().trimEnd('/') != settings.flibustaBaseUrl
}

/**
 * Settings screen logic: edits go straight to [settings], except the RuTracker credentials
 * and the Flibusta URL, which are saved by their buttons.
 *
 * @param checkRoot checks that a folder exists and accepts files.
 * @param pathOfTree turns a folder picker result into a path, null when unsupported.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val ruTracker: RuTrackerClient,
    private val hasAllFilesAccess: () -> Boolean,
    private val checkRoot: suspend (String) -> RootCheck,
    private val pathOfTree: (String) -> String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(hasAllFilesAccess = hasAllFilesAccess()))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    /** The web check the login is waiting for, with the error that asked for it. */
    private var awaitedVerification: Pair<VerificationPurpose, AppError>? = null

    init {
        viewModelScope.launch {
            settings.settings.collect(::onSettings)
        }
        viewModelScope.launch {
            val stored = settings.rutrackerPassword() != null
            mutableState.update { it.copy(passwordStored = stored) }
        }
    }

    /** The screen is shown again, possibly after the system access settings. */
    fun onResume() {
        mutableState.update { it.copy(hasAllFilesAccess = hasAllFilesAccess()) }
        viewModelScope.launch { refreshRoots(mutableState.value.settings) }
    }

    fun onRootPicked(kind: MediaKind, treeUri: String?) {
        if (treeUri == null) return
        viewModelScope.launch {
            val issue = acceptRoot(kind, treeUri)
            mutableState.update { it.copy(rootProblem = issue?.let { RootProblem(kind, it) }) }
        }
    }

    fun onLoginChange(login: String) {
        mutableState.update { it.copy(login = login) }
    }

    fun onPasswordChange(password: String) {
        mutableState.update { it.copy(password = password) }
    }

    /** Saves the login and a newly typed password, then logs in to RuTracker. */
    fun onCheckLogin() {
        val current = mutableState.value
        if (current.loginCheck == LoginCheck.Running) return
        val login = current.login.trim()
        val password = current.password
        awaitedVerification = null
        mutableState.update { it.copy(login = login, loginCheck = LoginCheck.Running) }
        viewModelScope.launch {
            settings.update { it.copy(rutrackerLogin = login) }
            if (password.isNotEmpty()) {
                try {
                    settings.setRutrackerPassword(password)
                } catch (e: AppError) {
                    mutableState.update { it.copy(loginCheck = LoginCheck.Failed(e)) }
                    return@launch
                }
                mutableState.update { it.copy(password = "", passwordStored = true) }
            }
            attemptLogin()
        }
    }

    fun onForgetCredentials() {
        awaitedVerification = null
        mutableState.update {
            it.copy(login = "", password = "", passwordStored = false, loginCheck = LoginCheck.Idle)
        }
        viewModelScope.launch {
            settings.update { it.copy(rutrackerLogin = "") }
            settings.setRutrackerPassword(null)
        }
    }

    /** The screen has navigated to [SettingsUiState.pendingVerification]. */
    fun onVerificationOpened() {
        mutableState.update { it.copy(pendingVerification = null) }
    }

    /**
     * Result of the web check opened for the login. A passed login page means the session
     * cookie is set; a passed Cloudflare check repeats the login.
     */
    fun onVerificationResult(passed: Boolean) {
        val (purpose, error) = awaitedVerification ?: return
        awaitedVerification = null
        if (!passed) {
            mutableState.update { it.copy(loginCheck = LoginCheck.Failed(error)) }
            return
        }
        when (purpose) {
            VerificationPurpose.RUTRACKER_LOGIN -> mutableState.update { it.copy(loginCheck = LoginCheck.Success) }
            VerificationPurpose.CLOUDFLARE -> viewModelScope.launch { attemptLogin() }
        }
    }

    fun onFlibustaUrlChange(url: String) {
        mutableState.update { it.copy(flibustaUrl = url, flibustaUrlError = false) }
    }

    /** Saves the Flibusta URL; an empty field restores the default mirror. */
    fun onSaveFlibustaUrl() {
        val url = mutableState.value.flibustaUrl.trim().trimEnd('/')
        if (url.isNotEmpty() && !isValidBaseUrl(url)) {
            mutableState.update { it.copy(flibustaUrlError = true) }
            return
        }
        val saved = url.ifEmpty { Settings.DEFAULT_FLIBUSTA_BASE_URL }
        mutableState.update { it.copy(flibustaUrl = saved, flibustaUrlError = false) }
        viewModelScope.launch { settings.update { it.copy(flibustaBaseUrl = saved) } }
    }

    fun onPreferredFormatChange(format: Format) = save { it.copy(preferredFormat = format) }

    fun onWifiOnlyChange(wifiOnly: Boolean) = save { it.copy(wifiOnly = wifiOnly) }

    fun onMaxConcurrentChange(count: Int) = save { it.copy(maxConcurrent = count.coerceIn(1, Settings.MAX_CONCURRENT_LIMIT)) }

    fun onSeedHoursChange(hours: Int) = save { it.copy(seedHours = hours.coerceAtLeast(0)) }

    fun onNoSeedersRetryChange(minutes: Int) = save { it.copy(noSeedersRetryMinutes = minutes.coerceAtLeast(0)) }

    fun onSendErrorReportsChange(enabled: Boolean) = save { it.copy(sendErrorReports = enabled) }

    private fun save(transform: (Settings) -> Settings) {
        viewModelScope.launch { settings.update(transform) }
    }

    private suspend fun onSettings(stored: Settings) {
        val previous = mutableState.value
        val first = !previous.loaded
        mutableState.update {
            it.copy(
                settings = stored,
                loaded = true,
                login = if (first) stored.rutrackerLogin else it.login,
                flibustaUrl = if (first) stored.flibustaBaseUrl else it.flibustaUrl,
            )
        }
        val rootsChanged = stored.audioRoot != previous.settings.audioRoot || stored.booksRoot != previous.settings.booksRoot
        if (first || rootsChanged) refreshRoots(stored)
    }

    private suspend fun refreshRoots(stored: Settings) {
        val status = MediaKind.entries.mapNotNull { kind ->
            val path = stored.rootFor(kind) ?: return@mapNotNull null
            kind to checkRoot(path)
        }.toMap()
        mutableState.update { it.copy(rootStatus = status) }
    }

    /** Stores the picked folder as the root of [kind]; returns why it was rejected, if it was. */
    private suspend fun acceptRoot(kind: MediaKind, treeUri: String): RootIssue? {
        val path = pathOfTree(treeUri) ?: return RootIssue.UNSUPPORTED
        if (!hasAllFilesAccess()) return RootIssue.NO_ACCESS
        when (checkRoot(path)) {
            RootCheck.OK -> Unit
            RootCheck.NO_ACCESS -> return RootIssue.NO_ACCESS
            RootCheck.MISSING -> return RootIssue.MISSING
            RootCheck.NOT_WRITABLE -> return RootIssue.NOT_WRITABLE
        }
        settings.update {
            when (kind) {
                MediaKind.BOOK -> it.copy(booksRoot = path)
                MediaKind.AUDIO -> it.copy(audioRoot = path)
            }
        }
        return null
    }

    private suspend fun attemptLogin() {
        mutableState.update { it.copy(loginCheck = LoginCheck.Running) }
        val result = try {
            ruTracker.login()
            LoginCheck.Success
        } catch (e: AppError.RuTrackerCaptcha) {
            awaitVerification(VerificationRequest(e.loginUrl, VerificationPurpose.RUTRACKER_LOGIN), e)
            return
        } catch (e: AppError.CloudflareChallenge) {
            awaitVerification(VerificationRequest(e.url, VerificationPurpose.CLOUDFLARE), e)
            return
        } catch (e: AppError) {
            LoginCheck.Failed(e)
        }
        mutableState.update { it.copy(loginCheck = result) }
    }

    private fun awaitVerification(request: VerificationRequest, error: AppError) {
        awaitedVerification = request.purpose to error
        mutableState.update { it.copy(pendingVerification = request) }
    }

    companion object {
        /** Seeding choices in hours; 0 turns seeding off. */
        val SEED_HOURS_OPTIONS = listOf(0, 1, 3, 6, 12)

        /** Retry intervals for torrents without seeders, in minutes; 0 turns retries off. */
        val NO_SEEDERS_RETRY_OPTIONS = listOf(0, 5, 15, 30, 60)

        private fun isValidBaseUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.host.isNotEmpty() && parsed.query == null && parsed.fragment == null
        }
    }
}
