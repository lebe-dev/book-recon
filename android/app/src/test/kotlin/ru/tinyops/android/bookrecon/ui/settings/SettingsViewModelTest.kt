package ru.tinyops.android.bookrecon.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeRuTrackerClient
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.storage.files.RootCheck
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import ru.tinyops.android.bookrecon.ui.search.VerificationRequest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeSettingsRepository(
        Settings(rutrackerLogin = "reader", flibustaBaseUrl = "https://flibusta.site", audioRoot = AUDIOBOOKS),
        password = "secret",
    )
    private val ruTracker = FakeRuTrackerClient()
    private var grantable = true
    private val released = mutableListOf<String>()
    private val launcherNames = mutableListOf<Boolean>()
    private val rootChecks = mutableMapOf<String, RootCheck>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        settings = settings,
        ruTracker = ruTracker,
        checkRoot = { root -> rootChecks[root] ?: RootCheck.OK },
        takeGrant = { grantable },
        releaseGrant = { released += it },
        applyLauncherName = { launcherNames += it },
    )

    private fun runVmTest(block: suspend TestScope.(SettingsViewModel) -> Unit) = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        block(vm)
    }

    @Test
    fun loadsSettingsIntoTheForm() = runVmTest { vm ->
        val state = vm.state.value
        assertTrue(state.loaded)
        assertEquals("reader", state.login)
        assertEquals("", state.password)
        assertTrue(state.passwordStored)
        assertEquals("https://flibusta.site", state.flibustaUrl)
        assertEquals(mapOf(MediaKind.AUDIO to RootCheck.OK), state.rootStatus)
    }

    @Test
    fun checkLoginSavesCredentialsFirst() = runVmTest { vm ->
        vm.onLoginChange(" new-user ")
        vm.onPasswordChange("новый пароль")
        vm.onCheckLogin()
        advanceUntilIdle()

        assertEquals("new-user", settings.settings.first().rutrackerLogin)
        assertEquals("новый пароль", settings.rutrackerPassword())
        assertEquals(LoginCheck.Success, vm.state.value.loginCheck)
        assertEquals("", vm.state.value.password)
        assertTrue(vm.state.value.passwordStored)
        assertEquals(1, ruTracker.loginCount)
    }

    @Test
    fun emptyPasswordFieldKeepsTheStoredPassword() = runVmTest { vm ->
        vm.onCheckLogin()
        advanceUntilIdle()

        assertEquals("secret", settings.rutrackerPassword())
        assertEquals(LoginCheck.Success, vm.state.value.loginCheck)
    }

    @Test
    fun checkLoginNeedsALoginAndAPassword() = runVmTest { vm ->
        assertTrue(vm.state.value.canCheckLogin)

        vm.onLoginChange("   ")
        assertFalse(vm.state.value.canCheckLogin)
        vm.onCheckLogin()
        advanceUntilIdle()
        assertEquals(0, ruTracker.loginCount)
        assertEquals(LoginCheck.Idle, vm.state.value.loginCheck)

        vm.onLoginChange("reader")
        assertTrue(vm.state.value.canCheckLogin)
    }

    @Test
    fun checkLoginWithoutAnyPasswordIsOff() = runVmTest { vm ->
        vm.onForgetCredentials()
        advanceUntilIdle()
        vm.onLoginChange("reader")
        assertFalse(vm.state.value.canCheckLogin)
        vm.onCheckLogin()
        advanceUntilIdle()
        assertEquals(0, ruTracker.loginCount)

        vm.onPasswordChange("typed")
        assertTrue(vm.state.value.canCheckLogin)
    }

    @Test
    fun rejectedLogin() = runVmTest { vm ->
        ruTracker.behavior.failNext(AppError.RuTrackerLoginFailed())
        vm.onCheckLogin()
        advanceUntilIdle()

        assertEquals(LoginCheck.Failed(AppError.RuTrackerLoginFailed()), vm.state.value.loginCheck)
    }

    @Test
    fun captchaOpensTheLoginPage() = runVmTest { vm ->
        ruTracker.captcha = true
        vm.onCheckLogin()
        advanceUntilIdle()

        val request = vm.state.value.pendingVerification
        assertEquals(VerificationRequest(FakeRuTrackerClient.LOGIN_URL, VerificationPurpose.RUTRACKER_LOGIN), request)
        vm.onVerificationOpened()
        assertNull(vm.state.value.pendingVerification)

        vm.onVerificationResult(true)
        advanceUntilIdle()
        assertEquals(LoginCheck.Success, vm.state.value.loginCheck)
        assertEquals(0, ruTracker.loginCount)
    }

    @Test
    fun captchaNotPassed() = runVmTest { vm ->
        ruTracker.captcha = true
        vm.onCheckLogin()
        advanceUntilIdle()
        vm.onVerificationOpened()
        vm.onVerificationResult(false)

        assertEquals(LoginCheck.Failed(AppError.RuTrackerCaptcha(FakeRuTrackerClient.LOGIN_URL)), vm.state.value.loginCheck)
    }

    @Test
    fun cloudflareCheckRepeatsTheLogin() = runVmTest { vm ->
        ruTracker.behavior.failNext(AppError.CloudflareChallenge("https://rutracker.org/forum/login.php"))
        vm.onCheckLogin()
        advanceUntilIdle()

        assertEquals(VerificationPurpose.CLOUDFLARE, vm.state.value.pendingVerification?.purpose)
        vm.onVerificationOpened()
        vm.onVerificationResult(true)
        advanceUntilIdle()

        assertEquals(LoginCheck.Success, vm.state.value.loginCheck)
        assertEquals(1, ruTracker.loginCount)
    }

    @Test
    fun unrelatedVerificationResultIsIgnored() = runVmTest { vm ->
        vm.onVerificationResult(true)
        advanceUntilIdle()

        assertEquals(LoginCheck.Idle, vm.state.value.loginCheck)
        assertEquals(0, ruTracker.loginCount)
    }

    @Test
    fun forgetCredentials() = runVmTest { vm ->
        vm.onForgetCredentials()
        advanceUntilIdle()

        assertEquals("", settings.settings.first().rutrackerLogin)
        assertNull(settings.rutrackerPassword())
        assertEquals("", vm.state.value.login)
        assertFalse(vm.state.value.passwordStored)
    }

    @Test
    fun flibustaUrl() = runVmTest { vm ->
        vm.onFlibustaUrlChange("not a url")
        vm.onSaveFlibustaUrl()
        advanceUntilIdle()
        assertTrue(vm.state.value.flibustaUrlError)
        assertEquals("https://flibusta.site", settings.settings.first().flibustaBaseUrl)

        vm.onFlibustaUrlChange("ftp://flibusta.is")
        vm.onSaveFlibustaUrl()
        advanceUntilIdle()
        assertTrue(vm.state.value.flibustaUrlError)

        vm.onFlibustaUrlChange(" http://flibusta.lib/ ")
        assertFalse(vm.state.value.flibustaUrlError)
        vm.onSaveFlibustaUrl()
        advanceUntilIdle()
        assertEquals("http://flibusta.lib", settings.settings.first().flibustaBaseUrl)
        assertEquals("http://flibusta.lib", vm.state.value.flibustaUrl)

        vm.onFlibustaUrlChange("")
        vm.onSaveFlibustaUrl()
        advanceUntilIdle()
        assertEquals(Settings.DEFAULT_FLIBUSTA_BASE_URL, settings.settings.first().flibustaBaseUrl)
        assertEquals(Settings.DEFAULT_FLIBUSTA_BASE_URL, vm.state.value.flibustaUrl)
    }

    @Test
    fun pickRoot() = runVmTest { vm ->
        vm.onRootPicked(MediaKind.BOOK, BOOKS)
        advanceUntilIdle()

        assertEquals(BOOKS, settings.settings.first().booksRoot)
        assertNull(vm.state.value.rootProblem)
        assertEquals(RootCheck.OK, vm.state.value.rootStatus[MediaKind.BOOK])
    }

    @Test
    fun pickedFolderThatCannotBeUsed() = runVmTest { vm ->
        vm.onRootPicked(MediaKind.BOOK, "content://com.google.android.apps.docs.storage/tree/x")
        advanceUntilIdle()
        assertEquals(RootProblem(MediaKind.BOOK, RootIssue.UNSUPPORTED), vm.state.value.rootProblem)

        rootChecks[CARD_BOOKS] = RootCheck.NOT_WRITABLE
        vm.onRootPicked(MediaKind.BOOK, CARD_BOOKS)
        advanceUntilIdle()
        assertEquals(RootProblem(MediaKind.BOOK, RootIssue.NOT_WRITABLE), vm.state.value.rootProblem)
        assertEquals(listOf(CARD_BOOKS), released)

        grantable = false
        vm.onRootPicked(MediaKind.AUDIO, "$TREE_BASE/primary%3AAudio")
        advanceUntilIdle()
        assertEquals(RootProblem(MediaKind.AUDIO, RootIssue.NO_ACCESS), vm.state.value.rootProblem)

        val stored = settings.settings.first()
        assertNull(stored.booksRoot)
        assertEquals(AUDIOBOOKS, stored.audioRoot)

        vm.onRootPicked(MediaKind.BOOK, null)
        assertEquals(RootProblem(MediaKind.AUDIO, RootIssue.NO_ACCESS), vm.state.value.rootProblem)
    }

    @Test
    fun replacedRootGivesItsGrantBack() = runVmTest { vm ->
        vm.onRootPicked(MediaKind.AUDIO, "$TREE_BASE/primary%3ANewAudio")
        advanceUntilIdle()

        assertEquals(listOf(AUDIOBOOKS), released)
    }

    @Test
    fun grantStaysWhileTheOtherRootUsesTheFolder() = runVmTest { vm ->
        vm.onRootPicked(MediaKind.BOOK, AUDIOBOOKS)
        advanceUntilIdle()
        vm.onRootPicked(MediaKind.AUDIO, "$TREE_BASE/primary%3ANewAudio")
        advanceUntilIdle()

        assertTrue(released.isEmpty())
    }

    @Test
    fun resumeRefreshesRoots() = runVmTest { vm ->
        rootChecks[AUDIOBOOKS] = RootCheck.MISSING
        vm.onResume()
        advanceUntilIdle()

        assertEquals(RootCheck.MISSING, vm.state.value.rootStatus[MediaKind.AUDIO])
    }

    @Test
    fun downloadOptions() = runVmTest { vm ->
        vm.onPreferredFormatChange(Format.FB2)
        vm.onWifiOnlyChange(false)
        vm.onMaxConcurrentChange(4)
        vm.onSeedHoursChange(6)
        advanceUntilIdle()

        val stored = settings.settings.first()
        assertEquals(Format.FB2, stored.preferredFormat)
        assertFalse(stored.wifiOnly)
        assertEquals(4, stored.maxConcurrent)
        assertEquals(6, stored.seedHours)
        assertEquals(stored, vm.state.value.settings)
    }

    @Test
    fun autoCheckUpdatesIsOffByDefaultAndSaved() = runVmTest { vm ->
        assertFalse(vm.state.value.settings.autoCheckUpdates)

        vm.onAutoCheckUpdatesChange(true)
        advanceUntilIdle()

        assertTrue(settings.settings.first().autoCheckUpdates)
    }

    @Test
    fun cyrillicAppNameIsSavedAndAppliedToTheLauncher() = runVmTest { vm ->
        vm.onCyrillicAppNameChange(true)
        advanceUntilIdle()

        assertTrue(settings.settings.first().cyrillicAppName)
        assertEquals(listOf(true), launcherNames)

        vm.onCyrillicAppNameChange(false)
        advanceUntilIdle()

        assertFalse(settings.settings.first().cyrillicAppName)
        assertEquals(listOf(true, false), launcherNames)
    }

    @Test
    fun flibustaTimeoutIsSavedWithinLimits() = runVmTest { vm ->
        assertEquals(20, vm.state.value.settings.flibustaTimeoutSeconds)

        vm.onFlibustaTimeoutChange(45)
        advanceUntilIdle()
        assertEquals(45, settings.settings.first().flibustaTimeoutSeconds)

        vm.onFlibustaTimeoutChange(0)
        advanceUntilIdle()
        assertEquals(Settings.MIN_FLIBUSTA_TIMEOUT_SECONDS, settings.settings.first().flibustaTimeoutSeconds)

        vm.onFlibustaTimeoutChange(10_000)
        advanceUntilIdle()
        assertEquals(Settings.MAX_FLIBUSTA_TIMEOUT_SECONDS, settings.settings.first().flibustaTimeoutSeconds)
    }

    @Test
    fun sourcesCanBeSwitchedOff() = runVmTest { vm ->
        assertTrue(vm.state.value.settings.flibustaEnabled)
        assertTrue(vm.state.value.settings.rutrackerEnabled)

        vm.onFlibustaEnabledChange(false)
        vm.onRuTrackerEnabledChange(false)
        advanceUntilIdle()

        assertFalse(settings.settings.first().flibustaEnabled)
        assertFalse(settings.settings.first().rutrackerEnabled)
    }

    @Test
    fun errorReportsAreOffUntilTurnedOn() = runVmTest { vm ->
        assertFalse(vm.state.value.settings.sendErrorReports)

        vm.onSendErrorReportsChange(true)
        advanceUntilIdle()
        assertTrue(settings.settings.first().sendErrorReports)
        assertTrue(vm.state.value.settings.sendErrorReports)

        vm.onSendErrorReportsChange(false)
        advanceUntilIdle()
        assertFalse(settings.settings.first().sendErrorReports)
    }

    @Test
    fun colorThemeAndDarkModeAreSaved() = runVmTest { vm ->
        assertEquals(ColorTheme.BLUEBERRY, vm.state.value.settings.colorTheme)
        assertEquals(DarkMode.SYSTEM, vm.state.value.settings.darkMode)

        vm.onColorThemeChange(ColorTheme.MARSHMALLOW)
        vm.onDarkModeChange(DarkMode.LIGHT)
        advanceUntilIdle()

        val stored = settings.settings.first()
        assertEquals(ColorTheme.MARSHMALLOW, stored.colorTheme)
        assertEquals(DarkMode.LIGHT, stored.darkMode)
        assertEquals(ColorTheme.MARSHMALLOW, vm.state.value.settings.colorTheme)
    }

    private companion object {
        const val TREE_BASE = "content://com.android.externalstorage.documents/tree"
        const val AUDIOBOOKS = "$TREE_BASE/primary%3AAudiobooks"
        const val BOOKS = "$TREE_BASE/primary%3ABooks"
        const val CARD_BOOKS = "$TREE_BASE/1234-ABCD%3ABooks"
    }
}
