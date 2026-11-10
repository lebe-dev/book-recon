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
import ru.tinyops.android.bookrecon.core.flibusta.FlibustaSourceImpl
import ru.tinyops.android.bookrecon.net.OkHttpHttpClient
import ru.tinyops.android.bookrecon.server.RoutingRuTrackerClient
import ru.tinyops.android.bookrecon.storage.db.RoomDownloadRepository
import ru.tinyops.android.bookrecon.storage.files.AndroidFileStorage
import ru.tinyops.android.bookrecon.storage.settings.DataStoreSettingsRepository
import ru.tinyops.android.bookrecon.torrent.LibTorrentEngine

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 fails to set up SDK 36+ on JDK 21 (ApplicationSharedMemory), so pin 35.
@Config(sdk = [35])
class AppContainerTest {
    @Test
    fun everyModuleProvidesItsPort() = runBlocking {
        val app = AppContainer(ApplicationProvider.getApplicationContext())

        assertEquals(2, app.settings.settingsRepository.settings.first().maxConcurrent)
        assertTrue(app.settings.settingsRepository is DataStoreSettingsRepository)
        assertTrue(app.settings.fileStorage is AndroidFileStorage)
        assertTrue(app.flibusta.flibustaSource is FlibustaSourceImpl)
        assertTrue(app.ruTracker.ruTrackerClient is RoutingRuTrackerClient)
        assertTrue(app.torrent.torrentEngine is LibTorrentEngine)
        assertTrue(app.database.downloadRepository is RoomDownloadRepository)
        assertTrue(app.network.httpClient is OkHttpHttpClient)
        assertTrue(app.download.downloadQueue.observe().first().isEmpty())
    }
}
