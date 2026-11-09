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
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.fake.FakeData

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 fails to set up SDK 36+ on JDK 21 (ApplicationSharedMemory), so pin 35.
@Config(sdk = [35])
class AppContainerTest {
    @Test
    fun everyModuleProvidesItsPort() = runBlocking {
        val app = AppContainer(ApplicationProvider.getApplicationContext())

        assertEquals(2, app.settings.settingsRepository.settings.first().maxConcurrent)
        assertTrue(app.settings.fileStorage.hasAllFilesAccess())
        assertTrue(app.flibusta.flibustaSource.search("Булгаков", 10).isNotEmpty())
        assertTrue(app.ruTracker.ruTrackerClient.search("мастер", MediaKind.AUDIO, 10).isNotEmpty())
        assertEquals(
            FakeData.torrentInfo(FakeData.AUDIO_TOPIC_CD),
            app.torrent.torrentEngine.parse(FakeData.torrentBytes(FakeData.AUDIO_TOPIC_CD)),
        )
        assertEquals(3, app.database.downloadRepository.observeLibrary().first().size)
        assertEquals(404, app.network.httpClient.get("https://example.org/").use { it.code })
        assertTrue(app.download.downloadQueue.observe().first().isEmpty())
    }
}
