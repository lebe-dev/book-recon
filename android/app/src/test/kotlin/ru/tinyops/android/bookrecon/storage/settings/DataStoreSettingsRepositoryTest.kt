package ru.tinyops.android.bookrecon.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.Settings
import java.io.File
import javax.crypto.KeyGenerator

class DataStoreSettingsRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher { key }
    private val logs = mutableListOf<String>()
    private val jobs = mutableListOf<Job>()

    private fun openStore(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            File(dir, "settings.preferences_pb")
        }
    }

    private suspend fun closeStores() {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
    }

    private fun repository(store: DataStore<Preferences> = openStore()) =
        DataStoreSettingsRepository(store, cipher, log = { logs += it })

    @AfterEach
    fun tearDown() = runBlocking { closeStores() }

    @Test
    fun defaults() = runBlocking {
        val settings = repository().settings.first()

        assertEquals(Settings(), settings)
        assertEquals("https://flibusta.is", settings.flibustaBaseUrl)
        assertEquals(Format.EPUB, settings.preferredFormat)
        assertTrue(settings.wifiOnly)
        assertEquals(2, settings.maxConcurrent)
        assertEquals(0, settings.seedHours)
        assertEquals(15, settings.noSeedersRetryMinutes)
        assertEquals(20, settings.flibustaTimeoutSeconds)
        assertFalse(settings.sendErrorReports)
        assertTrue(settings.logInfo)
        assertFalse(settings.logDebug)
        assertEquals(ColorTheme.BLUEBERRY, settings.colorTheme)
        assertEquals(DarkMode.SYSTEM, settings.darkMode)
        assertFalse(settings.cyrillicAppName)
        assertNull(settings.audioRoot)
        assertNull(settings.booksRoot)
        assertEquals("", settings.rutrackerLogin)
    }

    @Test
    fun valuesSurviveReopening() = runBlocking {
        val changed = Settings(
            audioRoot = AUDIO_TREE,
            booksRoot = BOOKS_TREE,
            rutrackerLogin = "reader",
            flibustaBaseUrl = "https://flibusta.site",
            preferredFormat = Format.FB2,
            wifiOnly = false,
            maxConcurrent = 3,
            seedHours = 6,
            noSeedersRetryMinutes = 60,
            flibustaTimeoutSeconds = 45,
            sendErrorReports = true,
            logInfo = false,
            logDebug = true,
            colorTheme = ColorTheme.COCOA,
            darkMode = DarkMode.DARK,
            cyrillicAppName = true,
        )
        repository().update { changed }
        closeStores()

        assertEquals(changed, repository().settings.first())
    }

    @Test
    fun settingsFlowEmitsAfterUpdate() = runBlocking {
        val repository = repository()
        repository.update { it.copy(wifiOnly = false) }
        repository.update { it.copy(maxConcurrent = it.maxConcurrent + 1) }

        val settings = repository.settings.first()
        assertFalse(settings.wifiOnly)
        assertEquals(3, settings.maxConcurrent)
    }

    @Test
    fun updateNormalizesValues() = runBlocking {
        val repository = repository()

        repository.update {
            it.copy(
                audioRoot = "  ",
                booksRoot = " $BOOKS_TREE ",
                rutrackerLogin = "  reader ",
                flibustaBaseUrl = " https://flibusta.site/ ",
                maxConcurrent = 9,
                seedHours = -3,
            )
        }
        var settings = repository.settings.first()
        assertNull(settings.audioRoot)
        assertEquals(BOOKS_TREE, settings.booksRoot)
        assertEquals("reader", settings.rutrackerLogin)
        assertEquals("https://flibusta.site", settings.flibustaBaseUrl)
        assertEquals(Settings.MAX_CONCURRENT_LIMIT, settings.maxConcurrent)
        assertEquals(0, settings.seedHours)

        repository.update { it.copy(flibustaBaseUrl = "", maxConcurrent = 0) }
        settings = repository.settings.first()
        assertEquals(Settings.DEFAULT_FLIBUSTA_BASE_URL, settings.flibustaBaseUrl)
        assertEquals(1, settings.maxConcurrent)

        repository.update { it.copy(flibustaTimeoutSeconds = 0) }
        assertEquals(Settings.MIN_FLIBUSTA_TIMEOUT_SECONDS, repository.settings.first().flibustaTimeoutSeconds)
        repository.update { it.copy(flibustaTimeoutSeconds = 100_000) }
        assertEquals(Settings.MAX_FLIBUSTA_TIMEOUT_SECONDS, repository.settings.first().flibustaTimeoutSeconds)
    }

    @Test
    fun plainPathRootsOfOldVersionsCountAsNotChosen() = runBlocking {
        val repository = repository()

        repository.update { it.copy(audioRoot = "/storage/emulated/0/Audiobooks", booksRoot = BOOKS_TREE) }

        val settings = repository.settings.first()
        assertNull(settings.audioRoot)
        assertEquals(BOOKS_TREE, settings.booksRoot)
    }

    @Test
    fun clearingARootRemovesIt() = runBlocking {
        val repository = repository()
        repository.update { it.copy(audioRoot = AUDIO_TREE) }
        repository.update { it.copy(audioRoot = null) }

        assertNull(repository.settings.first().audioRoot)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() = runBlocking {
        val store = openStore()
        store.edit {
            it[SettingsKeys.PREFERRED_FORMAT] = "DOCX"
            it[SettingsKeys.MAX_CONCURRENT] = 42
            it[SettingsKeys.COLOR_THEME] = "GRAPE"
            it[SettingsKeys.DARK_MODE] = "DIM"
        }

        val settings = repository(store).settings.first()
        assertEquals(ColorTheme.BLUEBERRY, settings.colorTheme)
        assertEquals(DarkMode.SYSTEM, settings.darkMode)
        assertEquals(Format.EPUB, settings.preferredFormat)
        assertEquals(Settings.MAX_CONCURRENT_LIMIT, settings.maxConcurrent)
    }

    @Test
    fun passwordRoundTripIsEncrypted() = runBlocking {
        val store = openStore()
        val repository = repository(store)
        assertNull(repository.rutrackerPassword())

        repository.setRutrackerPassword("секрет-hunter2")

        assertEquals("секрет-hunter2", repository.rutrackerPassword())
        val stored = store.data.first()[SettingsKeys.RUTRACKER_PASSWORD]
        assertNotNull(stored)
        assertFalse(stored!!.contains("hunter2"))
        assertFalse(File(dir, "settings.preferences_pb").readBytes().toString(Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun passwordSurvivesReopening() = runBlocking {
        repository().setRutrackerPassword("secret")
        closeStores()

        assertEquals("secret", repository().rutrackerPassword())
    }

    @Test
    fun emptyOrNullPasswordRemovesIt() = runBlocking {
        val repository = repository()
        repository.setRutrackerPassword("secret")
        repository.setRutrackerPassword("")
        assertNull(repository.rutrackerPassword())

        repository.setRutrackerPassword("secret")
        repository.setRutrackerPassword(null)
        assertNull(repository.rutrackerPassword())
    }

    @Test
    fun passwordIsNotPartOfSettings() = runBlocking {
        val repository = repository()
        repository.setRutrackerPassword("hunter2")

        assertFalse(repository.settings.first().toString().contains("hunter2"))
    }

    @Test
    fun undecryptablePasswordIsDroppedWithoutLeakingIt() = runBlocking {
        val store = openStore()
        repository(store).setRutrackerPassword("hunter2")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val repository = DataStoreSettingsRepository(store, AesGcmCipher { otherKey }, log = { logs += it })

        assertNull(repository.rutrackerPassword())
        assertNull(store.data.first()[SettingsKeys.RUTRACKER_PASSWORD])
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "hunter2" in it })
    }

    private companion object {
        const val AUDIO_TREE = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks"
        const val BOOKS_TREE = "content://com.android.externalstorage.documents/tree/1234-ABCD%3A%D0%9A%D0%BD%D0%B8%D0%B3%D0%B8"
    }
}
