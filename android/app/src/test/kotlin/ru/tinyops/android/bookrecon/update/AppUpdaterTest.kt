package ru.tinyops.android.bookrecon.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.update.ApkAsset
import ru.tinyops.android.bookrecon.core.update.AppVersion
import ru.tinyops.android.bookrecon.core.update.Release
import ru.tinyops.android.bookrecon.core.update.UpdateCheck
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdaterTest {
    private val settings = FakeSettingsRepository(Settings(autoCheckUpdates = true))
    private var answer: suspend () -> UpdateCheck = { UpdateCheck.Available(RELEASE) }
    private var checks = 0
    private val downloader = FakeDownloader()
    private val installer = FakeInstaller()

    private fun TestScope.updater() = AppUpdater(
        fetchLatest = {
            checks++
            answer()
        },
        downloader = downloader,
        installer = installer,
        settings = settings,
        scope = backgroundScope,
    )

    @Test
    fun startCheckIsSkippedWhenTurnedOff() = runTest {
        settings.update { it.copy(autoCheckUpdates = false) }
        val updater = updater()
        updater.checkOnStart()
        runCurrent()
        assertEquals(0, checks)
        assertEquals(UpdateState.Idle, updater.state.value)
        assertNull(updater.prompt.value)
    }

    @Test
    fun startCheckRunsOncePerProcessAndOffersTheRelease() = runTest {
        val updater = updater()
        updater.checkOnStart()
        updater.checkOnStart()
        runCurrent()
        updater.checkOnStart()
        runCurrent()
        assertEquals(1, checks)
        assertEquals(UpdateState.Available(RELEASE), updater.state.value)
        assertEquals(RELEASE, updater.prompt.value)
    }

    @Test
    fun startCheckDoesNotOfferASkippedVersion() = runTest {
        settings.update { it.copy(skippedUpdateVersion = "0.10.0") }
        val updater = updater()
        updater.checkOnStart()
        runCurrent()
        assertNull(updater.prompt.value)
        assertEquals(UpdateState.Available(RELEASE), updater.state.value)
    }

    @Test
    fun aFailedStartCheckStaysQuiet() = runTest {
        answer = { throw AppError.Network() }
        val updater = updater()
        updater.checkOnStart()
        runCurrent()
        assertEquals(UpdateState.Idle, updater.state.value)
        assertNull(updater.prompt.value)
    }

    @Test
    fun manualCheckShowsProgressAndResult() = runTest {
        val gate = CompletableDeferred<UpdateCheck>()
        answer = { gate.await() }
        val updater = updater()
        updater.check()
        runCurrent()
        assertEquals(UpdateState.Checking, updater.state.value)
        gate.complete(UpdateCheck.UpToDate(AppVersion(0, 9, 1)))
        runCurrent()
        assertEquals(UpdateState.UpToDate(AppVersion(0, 9, 1)), updater.state.value)
        assertTrue(downloader.cleared, "a leftover APK is removed once the app is up to date")
    }

    @Test
    fun manualCheckOffersASkippedVersionToo() = runTest {
        settings.update { it.copy(skippedUpdateVersion = "0.10.0") }
        val updater = updater()
        updater.check()
        runCurrent()
        assertEquals(UpdateState.Available(RELEASE), updater.state.value)
        assertNull(updater.prompt.value)
    }

    @Test
    fun manualCheckFailureIsShown() = runTest {
        answer = { throw AppError.ServiceDown("github answered 403") }
        val updater = updater()
        updater.check()
        runCurrent()
        assertEquals(UpdateState.Failed(UpdateFailure.SERVICE_DOWN, null), updater.state.value)
    }

    @Test
    fun installDownloadsAndHandsTheApkToTheSystem() = runTest {
        val updater = updater()
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()
        assertEquals(listOf(RELEASE.apk), downloader.requested)
        assertEquals(listOf(downloader.file), installer.installed)
        assertEquals(UpdateState.Installing(RELEASE), updater.state.value)
    }

    @Test
    fun downloadReportsProgressInPercentSteps() = runTest {
        downloader.steps = listOf(1_000L, 1_005L, 50_000L, 100_000L)
        val updater = updater()
        updater.check()
        runCurrent()
        val seen = mutableListOf<Long>()
        downloader.onStep = { (updater.state.value as? UpdateState.Downloading)?.let { seen += it.downloaded } }
        updater.install()
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_000L, 50_000L, 100_000L), seen, "1005 bytes is still 1% of 100000")
    }

    @Test
    fun cancelStopsTheDownload() = runTest {
        downloader.gate = CompletableDeferred()
        val updater = updater()
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()
        assertInstanceOf(UpdateState.Downloading::class.java, updater.state.value)
        updater.cancel()
        runCurrent()
        assertEquals(UpdateState.Available(RELEASE), updater.state.value)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun aCorruptedApkIsNotInstalled() = runTest {
        downloader.failure = ApkCorrupted("sha-256 does not match")
        val updater = updater()
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()
        assertEquals(UpdateState.Failed(UpdateFailure.CORRUPTED, RELEASE), updater.state.value)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun withoutInstallPermissionTheUserIsAskedAndTheInstallGoesOnAfter() = runTest {
        installer.allowed = false
        val updater = updater()
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()
        assertEquals(UpdateState.NeedsPermission(RELEASE), updater.state.value)

        updater.onResume()
        runCurrent()
        assertEquals(UpdateState.NeedsPermission(RELEASE), updater.state.value, "still not allowed")

        installer.allowed = true
        updater.onResume()
        runCurrent()
        assertEquals(UpdateState.Installing(RELEASE), updater.state.value)
        assertEquals(1, downloader.requested.size, "the downloaded APK is reused")
        assertEquals(listOf(downloader.file), installer.installed)
    }

    @Test
    fun installResults() = runTest {
        val updater = updater()
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()

        updater.onInstallResult(InstallResult.ABORTED)
        assertEquals(UpdateState.Available(RELEASE), updater.state.value, "the user said no: offer again")

        updater.install()
        runCurrent()
        updater.onInstallResult(InstallResult.CONFLICT)
        assertEquals(UpdateState.Failed(UpdateFailure.SIGNATURE, RELEASE), updater.state.value)

        updater.install()
        runCurrent()
        updater.onInstallResult(InstallResult.FAILED)
        assertEquals(UpdateState.Failed(UpdateFailure.INSTALL_FAILED, RELEASE), updater.state.value)
    }

    @Test
    fun anInstallResultOutsideAnInstallIsIgnored() = runTest {
        val updater = updater()
        updater.onInstallResult(InstallResult.FAILED)
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test
    fun promptChoices() = runTest {
        val updater = updater()
        updater.checkOnStart()
        runCurrent()
        updater.dismissPrompt()
        assertNull(updater.prompt.value)
        assertEquals("", settings.settings.first().skippedUpdateVersion)
    }

    @Test
    fun skippingRemembersTheVersion() = runTest {
        val updater = updater()
        updater.checkOnStart()
        runCurrent()
        updater.skip(RELEASE)
        runCurrent()
        assertNull(updater.prompt.value)
        assertEquals("0.10.0", settings.settings.first().skippedUpdateVersion)
    }

    @Test
    fun acceptingThePromptStartsTheInstall() = runTest {
        val updater = updater()
        updater.checkOnStart()
        runCurrent()
        updater.acceptPrompt()
        runCurrent()
        assertNull(updater.prompt.value)
        assertEquals(UpdateState.Installing(RELEASE), updater.state.value)
    }

    @Test
    fun checkIsIgnoredWhileDownloading() = runTest {
        downloader.gate = CompletableDeferred()
        val updater = updater()
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()
        updater.check()
        runCurrent()
        assertEquals(1, checks)
        assertFalse(updater.state.value is UpdateState.Checking)
    }

    private class FakeDownloader : ApkDownloader {
        val file = File("update.apk")
        val requested = mutableListOf<ApkAsset>()
        var steps: List<Long> = emptyList()
        var onStep: () -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var cleared = false

        override suspend fun download(asset: ApkAsset, onProgress: (Long) -> Unit): File {
            requested += asset
            onStep()
            for (step in steps) {
                onProgress(step)
                onStep()
            }
            gate?.await()
            failure?.let { throw it }
            return file
        }

        override fun clear() {
            cleared = true
        }
    }

    private class FakeInstaller : ApkInstaller {
        var allowed = true
        val installed = mutableListOf<File>()

        override fun canInstall(): Boolean = allowed

        override suspend fun install(apk: File) {
            installed += apk
        }
    }

    private companion object {
        val RELEASE = Release(
            version = AppVersion(0, 10, 0),
            notes = "• Faster search",
            pageUrl = "https://github.com/lebe-dev/book-recon/releases/tag/v0.10.0",
            apk = ApkAsset("book-recon-0.10.0.apk", "https://example.org/book-recon-0.10.0.apk", 100_000, null),
        )
    }
}
