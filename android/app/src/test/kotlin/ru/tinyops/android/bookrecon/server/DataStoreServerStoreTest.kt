package ru.tinyops.android.bookrecon.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.storage.settings.AesGcmCipher
import java.io.File
import javax.crypto.KeyGenerator

class DataStoreServerStoreTest {
    @TempDir
    lateinit var dir: File

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val jobs = mutableListOf<Job>()
    private val logs = mutableListOf<String>()

    private fun openStore(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            File(dir, "settings.preferences_pb")
        }
    }

    @AfterEach
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun `empty store reads as nothing configured`() = runBlocking {
        assertEquals(StoredServer(), DataStoreServerStore(openStore(), AesGcmCipher { key }).load())
    }

    @Test
    fun `round trip keeps every field and seals the token`() = runBlocking {
        val prefs = openStore()
        val store = DataStoreServerStore(prefs, AesGcmCipher { key })
        val server = StoredServer(
            url = "https://books.example.com",
            token = "secret-token",
            approvedHosts = setOf("rutracker.org", "flibusta.is"),
            telegramUsername = "reader",
            enabled = true,
        )

        store.save(server)

        assertEquals(server, store.load())
        val raw = prefs.data.first().asMap().values.joinToString()
        assertFalse(raw.contains("secret-token"), "token must not be stored in plain text")
    }

    @Test
    fun `null fields are removed`() = runBlocking {
        val store = DataStoreServerStore(openStore(), AesGcmCipher { key })
        store.save(StoredServer(url = "https://a", token = "t", approvedHosts = setOf("x"), telegramUsername = "u"))

        store.save(StoredServer(url = "https://a"))

        assertEquals(StoredServer(url = "https://a"), store.load())
    }

    @Test
    fun `token sealed with another key is dropped together with the hosts`() = runBlocking {
        val prefs = openStore()
        DataStoreServerStore(prefs, AesGcmCipher { key })
            .save(StoredServer(url = "https://a", token = "t", approvedHosts = setOf("x")))
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        val loaded = DataStoreServerStore(prefs, AesGcmCipher { otherKey }, log = { logs += it }).load()

        assertNull(loaded.token)
        assertNull(prefs.data.first()[stringPreferencesKey("server_token_sealed")])
        assertNull(DataStoreServerStore(prefs, AesGcmCipher { key }).load().approvedHosts)
        assertTrue(logs.single().contains("cannot be decrypted"))
        assertTrue(logs.none { it.contains("t\"") })
    }

    @Test
    fun `garbage token is dropped`() = runBlocking {
        val prefs = openStore()
        prefs.edit { it[stringPreferencesKey("server_token_sealed")] = "%%%" }

        assertNull(DataStoreServerStore(prefs, AesGcmCipher { key }).load().token)
    }
}
