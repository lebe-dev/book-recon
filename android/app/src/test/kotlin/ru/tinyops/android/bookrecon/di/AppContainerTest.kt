package ru.tinyops.android.bookrecon.di

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.flibusta.FlibustaSourceImpl
import ru.tinyops.android.bookrecon.core.rutracker.RuTrackerClientImpl
import ru.tinyops.android.bookrecon.net.OkHttpHttpClient

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 fails to set up SDK 36+ on JDK 21 (ApplicationSharedMemory), so pin 35.
@Config(sdk = [35])
class AppContainerTest {
    @Test
    fun everyModuleProvidesItsPort() = runBlocking {
        val app = AppContainer(ApplicationProvider.getApplicationContext())

        assertEquals(2, app.settings.settingsRepository.settings.first().maxConcurrent)
        assertTrue(app.settings.fileStorage.hasAllFilesAccess())
        assertTrue(app.flibusta.flibustaSource is FlibustaSourceImpl)
        assertTrue(app.ruTracker.ruTrackerClient is RuTrackerClientImpl)
        assertEquals(
            FakeData.torrentInfo(FakeData.AUDIO_TOPIC_CD),
            app.torrent.torrentEngine.parse(FakeData.torrentBytes(FakeData.AUDIO_TOPIC_CD)),
        )
        assertEquals(3, app.database.downloadRepository.observeLibrary().first().size)
        assertTrue(app.network.httpClient is OkHttpHttpClient)
        assertTrue(app.download.downloadQueue.observe().first().isEmpty())
    }
}
