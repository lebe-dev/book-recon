package ru.tinyops.android.bookrecon.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.storage.files.RootCheck
import ru.tinyops.android.bookrecon.storage.files.TreeUris
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
    /** The picker returned a folder that is not on shared storage (cloud, media collections) or a whole volume. */
    UNSUPPORTED,

    /** The system did not keep the grant on the folder. */
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
    /** Both credentials are there: a login and a password, typed now or already stored. */
    val canCheckLogin: Boolean
        get() = login.isNotBlank() && (password.isNotEmpty() || passwordStored)

    /** The URL field differs from the stored URL. */
    val flibustaUrlChanged: Boolean
        get() = flibustaUrl.trim().trimEnd('/') != settings.flibustaBaseUrl
}

/**
 * Settings screen logic: edits go straight to [settings], except the RuTracker credentials
 * and the Flibusta URL, which are saved by their buttons.
 *
 * @param checkRoot checks that a picked folder is granted, exists and accepts files.
 * @param takeGrant asks the system to keep the grant on a picked folder across restarts;
 *   false when it refuses.
 * @param releaseGrant gives a kept grant back, so the app can reach nothing but its roots.
 * @param applyLauncherName switches the name under the launcher icon (true: Cyrillic).
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val ruTracker: RuTrackerClient,
    private val checkRoot: suspend (String) -> RootCheck,
    private val takeGrant: (String) -> Boolean,
    private val releaseGrant: (String) -> Unit,
    private val applyLauncherName: (Boolean) -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
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

    /** The screen is shown again; a folder may be gone or its grant revoked meanwhile. */
    fun onResume() {
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

    /** Saves the login and a newly typed password, then logs in to RuTracker; does nothing while a credential is missing. */
    fun onCheckLogin() {
        val current = mutableState.value
        if (current.loginCheck == LoginCheck.Running || !current.canCheckLogin) return
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

    fun onFlibustaTimeoutChange(seconds: Int) = save {
        it.copy(
            flibustaTimeoutSeconds = seconds.coerceIn(
                Settings.MIN_FLIBUSTA_TIMEOUT_SECONDS,
                Settings.MAX_FLIBUSTA_TIMEOUT_SECONDS,
            ),
        )
    }

    fun onFlibustaEnabledChange(enabled: Boolean) = save { it.copy(flibustaEnabled = enabled) }

    fun onFlibustaRouteChange(route: SourceRoute) = save { it.copy(flibustaRoute = route) }

    fun onRuTrackerRouteChange(route: SourceRoute) = save { it.copy(rutrackerRoute = route) }

    fun onRuTrackerEnabledChange(enabled: Boolean) = save { it.copy(rutrackerEnabled = enabled) }

    fun onPreferredFormatChange(format: Format) = save { it.copy(preferredFormat = format) }

    fun onWifiOnlyChange(wifiOnly: Boolean) = save { it.copy(wifiOnly = wifiOnly) }

    fun onTorrentPeersThroughServerChange(enabled: Boolean) = save { it.copy(torrentPeersThroughServer = enabled) }

    fun onMaxConcurrentChange(count: Int) = save { it.copy(maxConcurrent = count.coerceIn(1, Settings.MAX_CONCURRENT_LIMIT)) }

    fun onSeedHoursChange(hours: Int) = save { it.copy(seedHours = hours.coerceAtLeast(0)) }

    fun onNoSeedersRetryChange(minutes: Int) = save { it.copy(noSeedersRetryMinutes = minutes.coerceAtLeast(0)) }

    fun onSendErrorReportsChange(enabled: Boolean) = save { it.copy(sendErrorReports = enabled) }

    fun onColorThemeChange(theme: ColorTheme) = save { it.copy(colorTheme = theme) }

    fun onDarkModeChange(mode: DarkMode) = save { it.copy(darkMode = mode) }

    fun onCyrillicAppNameChange(enabled: Boolean) {
        applyLauncherName(enabled)
        save { it.copy(cyrillicAppName = enabled) }
    }

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
            val root = stored.rootFor(kind) ?: return@mapNotNull null
            kind to checkRoot(root)
        }.toMap()
        mutableState.update { it.copy(rootStatus = status) }
    }

    /** Stores the picked folder as the root of [kind]; returns why it was rejected, if it was. */
    private suspend fun acceptRoot(kind: MediaKind, treeUri: String): RootIssue? {
        if (!TreeUris.isSupported(treeUri)) return RootIssue.UNSUPPORTED
        if (!takeGrant(treeUri)) return RootIssue.NO_ACCESS
        val issue = when (checkRoot(treeUri)) {
            RootCheck.OK -> null
            RootCheck.NO_ACCESS -> RootIssue.NO_ACCESS
            RootCheck.MISSING -> RootIssue.MISSING
            RootCheck.NOT_WRITABLE -> RootIssue.NOT_WRITABLE
        }
        if (issue != null) {
            releaseIfUnused(treeUri)
            return issue
        }
        val previous = settings.settings.first().rootFor(kind)
        settings.update {
            when (kind) {
                MediaKind.BOOK -> it.copy(booksRoot = treeUri)
                MediaKind.AUDIO -> it.copy(audioRoot = treeUri)
            }
        }
        if (previous != null && previous != treeUri) releaseIfUnused(previous)
        return null
    }

    /** Gives the grant on [treeUri] back unless one of the roots still uses that folder. */
    private suspend fun releaseIfUnused(treeUri: String) {
        val stored = settings.settings.first()
        if (treeUri == stored.audioRoot || treeUri == stored.booksRoot) return
        releaseGrant(treeUri)
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
        val FLIBUSTA_TIMEOUT_OPTIONS = listOf(10, 20, 30, 45, 60)

        private fun isValidBaseUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.host.isNotEmpty() && parsed.query == null && parsed.fragment == null
        }
    }
}
