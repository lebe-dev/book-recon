package ru.tinyops.android.bookrecon.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.storage.settings.SecretCipher
import ru.tinyops.android.bookrecon.storage.settings.SettingsKeys
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

/**
 * What the app remembers about its Book Recon server.
 *
 * @property url base URL without a trailing slash, empty when not set.
 * @property token bearer token of this installation, null before registration.
 * @property approvedHosts hosts the server tunnels for this app, cached from the last
 *   "approved" answer so the tunnel works right after start; null when not approved.
 * @property telegramUsername Telegram account the installation is linked to, for display.
 * @property enabled the user turned the server on in the settings; off by default.
 */
data class StoredServer(
    val url: String = "",
    val token: String? = null,
    val approvedHosts: Set<String>? = null,
    val telegramUsername: String? = null,
    val enabled: Boolean = false,
)

/** Persistence of [StoredServer]. */
interface ServerStore {
    suspend fun load(): StoredServer

    suspend fun save(server: StoredServer)
}

/**
 * [ServerStore] in the settings DataStore. The token is sealed by [cipher] like the RuTracker
 * password and never logged; a token that cannot be decrypted is dropped, and the app
 * registers again.
 */
class DataStoreServerStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val log: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ServerStore {

    override suspend fun load(): StoredServer {
        val prefs = store.data.first()
        return StoredServer(
            url = prefs[Keys.URL].orEmpty(),
            token = prefs[Keys.TOKEN]?.let { unseal(it) },
            approvedHosts = prefs[Keys.APPROVED_HOSTS],
            telegramUsername = prefs[Keys.TELEGRAM_USERNAME],
            enabled = prefs[Keys.ENABLED] ?: false,
        )
    }

    override suspend fun save(server: StoredServer) {
        val sealed = server.token?.let { token ->
            withContext(io) { Base64.getEncoder().encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8))) }
        }
        store.edit { prefs ->
            prefs.setOrRemove(Keys.URL, server.url.ifEmpty { null })
            prefs.setOrRemove(Keys.TOKEN, sealed)
            prefs.setOrRemove(Keys.TELEGRAM_USERNAME, server.telegramUsername)
            prefs[Keys.ENABLED] = server.enabled
            val hosts = server.approvedHosts
            if (hosts == null) prefs.remove(Keys.APPROVED_HOSTS) else prefs[Keys.APPROVED_HOSTS] = hosts
        }
    }

    private suspend fun unseal(stored: String): String? = try {
        withContext(io) { String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8) }
    } catch (e: GeneralSecurityException) {
        dropToken(stored, e)
    } catch (e: ProviderException) {
        dropToken(stored, e)
    } catch (e: IllegalArgumentException) {
        dropToken(stored, e)
    }

    private suspend fun dropToken(stored: String, error: Exception): String? {
        log("Stored server token cannot be decrypted and was removed: ${error.javaClass.simpleName}")
        store.edit { prefs ->
            if (prefs[Keys.TOKEN] == stored) {
                prefs.remove(Keys.TOKEN)
                prefs.remove(Keys.APPROVED_HOSTS)
            }
        }
        return null
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private object Keys {
        val URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("server_token_sealed")
        val APPROVED_HOSTS = SettingsKeys.SERVER_APPROVED_HOSTS
        val TELEGRAM_USERNAME = stringPreferencesKey("server_telegram_username")
        val ENABLED = booleanPreferencesKey("server_enabled")
    }
}
