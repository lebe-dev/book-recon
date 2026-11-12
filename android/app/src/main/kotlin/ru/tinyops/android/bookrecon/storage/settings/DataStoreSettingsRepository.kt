package ru.tinyops.android.bookrecon.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

/** Preference keys of the settings store. */
internal object SettingsKeys {
    val AUDIO_ROOT = stringPreferencesKey("audio_root")
    val BOOKS_ROOT = stringPreferencesKey("books_root")
    val RUTRACKER_LOGIN = stringPreferencesKey("rutracker_login")
    val FLIBUSTA_BASE_URL = stringPreferencesKey("flibusta_base_url")
    val FLIBUSTA_TIMEOUT_SECONDS = intPreferencesKey("flibusta_timeout_seconds")
    val FLIBUSTA_ENABLED = booleanPreferencesKey("flibusta_enabled")
    val RUTRACKER_ENABLED = booleanPreferencesKey("rutracker_enabled")
    val PREFERRED_FORMAT = stringPreferencesKey("preferred_format")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
    val SEED_HOURS = intPreferencesKey("seed_hours")
    val NO_SEEDERS_RETRY_MINUTES = intPreferencesKey("no_seeders_retry_minutes")
    val SEND_ERROR_REPORTS = booleanPreferencesKey("send_error_reports")
    val TORRENT_PEERS_THROUGH_SERVER = booleanPreferencesKey("torrent_peers_through_server")
    val LOG_INFO = booleanPreferencesKey("log_info")
    val LOG_DEBUG = booleanPreferencesKey("log_debug")
    val COLOR_THEME = stringPreferencesKey("color_theme")
    val DARK_MODE = stringPreferencesKey("dark_mode")
    val CYRILLIC_APP_NAME = booleanPreferencesKey("cyrillic_app_name")
    val FLIBUSTA_ROUTE = stringPreferencesKey("flibusta_route")
    val RUTRACKER_ROUTE = stringPreferencesKey("rutracker_route")

    /**
     * Hosts the approved server tunnels, written by the server store in the same file. Read
     * here only for routes missing in the store: before routes existed, an approved server
     * carried both sources, and an upgrade must not cut it off.
     */
    val SERVER_APPROVED_HOSTS = stringSetPreferencesKey("server_approved_hosts")
    val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
    val SKIPPED_UPDATE_VERSION = stringPreferencesKey("skipped_update_version")

    /** Base64 of the password sealed by [SecretCipher]. */
    val RUTRACKER_PASSWORD = stringPreferencesKey("rutracker_password_sealed")
}

/**
 * [SettingsRepository] on DataStore Preferences. Absent keys read as the [Settings] defaults;
 * every write is normalized by [normalized]. The RuTracker password is sealed by [cipher]
 * (AES-GCM with a Keystore key in the app) and never logged. Keystore calls block, so the
 * cipher runs on [io].
 */
class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val log: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    override val settings: Flow<Settings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings()).normalized()
            prefs.write(updated)
        }
    }

    override suspend fun rutrackerPassword(): String? {
        val stored = store.data.first()[SettingsKeys.RUTRACKER_PASSWORD] ?: return null
        val plain = try {
            withContext(io) { cipher.decrypt(Base64.getDecoder().decode(stored)) }
        } catch (e: GeneralSecurityException) {
            dropUnreadablePassword(stored, e)
            return null
        } catch (e: ProviderException) {
            dropUnreadablePassword(stored, e)
            return null
        } catch (e: IllegalArgumentException) {
            dropUnreadablePassword(stored, e)
            return null
        }
        return String(plain, Charsets.UTF_8).ifEmpty { null }
    }

    override suspend fun setRutrackerPassword(password: String?) {
        if (password.isNullOrEmpty()) {
            store.edit { it.remove(SettingsKeys.RUTRACKER_PASSWORD) }
            return
        }
        val sealed = try {
            withContext(io) { Base64.getEncoder().encodeToString(cipher.encrypt(password.toByteArray(Charsets.UTF_8))) }
        } catch (e: GeneralSecurityException) {
            throw AppError.StorageFailed("cannot encrypt the rutracker password", e)
        } catch (e: ProviderException) {
            throw AppError.StorageFailed("cannot encrypt the rutracker password", e)
        }
        store.edit { it[SettingsKeys.RUTRACKER_PASSWORD] = sealed }
    }

    /**
     * The key is gone (app data restored on another device, Keystore reset) or the value is
     * corrupted: the password cannot come back, so it is removed and the user enters it again.
     */
    private suspend fun dropUnreadablePassword(stored: String, error: Exception) {
        log("Stored RuTracker password cannot be decrypted and was removed: ${error.javaClass.simpleName}")
        store.edit { prefs ->
            if (prefs[SettingsKeys.RUTRACKER_PASSWORD] == stored) prefs.remove(SettingsKeys.RUTRACKER_PASSWORD)
        }
    }
}

private fun Preferences.toSettings(): Settings {
    val defaults = Settings()
    val legacyRoute = if (this[SettingsKeys.SERVER_APPROVED_HOSTS] != null) SourceRoute.SERVER else SourceRoute.DIRECT
    return Settings(
        audioRoot = this[SettingsKeys.AUDIO_ROOT],
        booksRoot = this[SettingsKeys.BOOKS_ROOT],
        rutrackerLogin = this[SettingsKeys.RUTRACKER_LOGIN] ?: defaults.rutrackerLogin,
        flibustaBaseUrl = this[SettingsKeys.FLIBUSTA_BASE_URL] ?: defaults.flibustaBaseUrl,
        flibustaTimeoutSeconds = this[SettingsKeys.FLIBUSTA_TIMEOUT_SECONDS] ?: defaults.flibustaTimeoutSeconds,
        flibustaEnabled = this[SettingsKeys.FLIBUSTA_ENABLED] ?: defaults.flibustaEnabled,
        rutrackerEnabled = this[SettingsKeys.RUTRACKER_ENABLED] ?: defaults.rutrackerEnabled,
        preferredFormat = this[SettingsKeys.PREFERRED_FORMAT]
            ?.let { name -> Format.entries.firstOrNull { it.name == name } }
            ?: defaults.preferredFormat,
        wifiOnly = this[SettingsKeys.WIFI_ONLY] ?: defaults.wifiOnly,
        maxConcurrent = this[SettingsKeys.MAX_CONCURRENT] ?: defaults.maxConcurrent,
        seedHours = this[SettingsKeys.SEED_HOURS] ?: defaults.seedHours,
        noSeedersRetryMinutes = this[SettingsKeys.NO_SEEDERS_RETRY_MINUTES] ?: defaults.noSeedersRetryMinutes,
        sendErrorReports = this[SettingsKeys.SEND_ERROR_REPORTS] ?: defaults.sendErrorReports,
        torrentPeersThroughServer = this[SettingsKeys.TORRENT_PEERS_THROUGH_SERVER] ?: defaults.torrentPeersThroughServer,
        logInfo = this[SettingsKeys.LOG_INFO] ?: defaults.logInfo,
        logDebug = this[SettingsKeys.LOG_DEBUG] ?: defaults.logDebug,
        colorTheme = enumOrNull<ColorTheme>(this[SettingsKeys.COLOR_THEME]) ?: defaults.colorTheme,
        darkMode = enumOrNull<DarkMode>(this[SettingsKeys.DARK_MODE]) ?: defaults.darkMode,
        cyrillicAppName = this[SettingsKeys.CYRILLIC_APP_NAME] ?: defaults.cyrillicAppName,
        flibustaRoute = enumOrNull<SourceRoute>(this[SettingsKeys.FLIBUSTA_ROUTE]) ?: legacyRoute,
        rutrackerRoute = enumOrNull<SourceRoute>(this[SettingsKeys.RUTRACKER_ROUTE]) ?: legacyRoute,
        autoCheckUpdates = this[SettingsKeys.AUTO_CHECK_UPDATES] ?: defaults.autoCheckUpdates,
        skippedUpdateVersion = this[SettingsKeys.SKIPPED_UPDATE_VERSION] ?: defaults.skippedUpdateVersion,
    ).normalized()
}

private fun MutablePreferences.write(settings: Settings) {
    setOrRemove(SettingsKeys.AUDIO_ROOT, settings.audioRoot)
    setOrRemove(SettingsKeys.BOOKS_ROOT, settings.booksRoot)
    this[SettingsKeys.RUTRACKER_LOGIN] = settings.rutrackerLogin
    this[SettingsKeys.FLIBUSTA_BASE_URL] = settings.flibustaBaseUrl
    this[SettingsKeys.FLIBUSTA_TIMEOUT_SECONDS] = settings.flibustaTimeoutSeconds
    this[SettingsKeys.FLIBUSTA_ENABLED] = settings.flibustaEnabled
    this[SettingsKeys.RUTRACKER_ENABLED] = settings.rutrackerEnabled
    this[SettingsKeys.PREFERRED_FORMAT] = settings.preferredFormat.name
    this[SettingsKeys.WIFI_ONLY] = settings.wifiOnly
    this[SettingsKeys.MAX_CONCURRENT] = settings.maxConcurrent
    this[SettingsKeys.SEED_HOURS] = settings.seedHours
    this[SettingsKeys.NO_SEEDERS_RETRY_MINUTES] = settings.noSeedersRetryMinutes
    this[SettingsKeys.SEND_ERROR_REPORTS] = settings.sendErrorReports
    this[SettingsKeys.TORRENT_PEERS_THROUGH_SERVER] = settings.torrentPeersThroughServer
    this[SettingsKeys.LOG_INFO] = settings.logInfo
    this[SettingsKeys.LOG_DEBUG] = settings.logDebug
    this[SettingsKeys.COLOR_THEME] = settings.colorTheme.name
    this[SettingsKeys.DARK_MODE] = settings.darkMode.name
    this[SettingsKeys.CYRILLIC_APP_NAME] = settings.cyrillicAppName
    this[SettingsKeys.FLIBUSTA_ROUTE] = settings.flibustaRoute.name
    this[SettingsKeys.RUTRACKER_ROUTE] = settings.rutrackerRoute.name
    this[SettingsKeys.AUTO_CHECK_UPDATES] = settings.autoCheckUpdates
    this[SettingsKeys.SKIPPED_UPDATE_VERSION] = settings.skippedUpdateVersion
}

/** The constant named [name], null for a missing or unknown name (written by another version). */
private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }

private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}

/**
 * Settings with values the rest of the app can rely on: trimmed text, roots that are tree
 * URIs (blank means not chosen, and so does a plain path left by version 0.9.0 and older,
 * which the user has to pick again), a Flibusta URL without a trailing slash (blank
 * means the default), [Settings.maxConcurrent] in 1..[Settings.MAX_CONCURRENT_LIMIT] and
 * [Settings.seedHours] and [Settings.noSeedersRetryMinutes] not negative and
 * [Settings.flibustaTimeoutSeconds] within its limits.
 */
internal fun Settings.normalized(): Settings = copy(
    audioRoot = normalizeRoot(audioRoot),
    booksRoot = normalizeRoot(booksRoot),
    rutrackerLogin = rutrackerLogin.trim(),
    flibustaBaseUrl = flibustaBaseUrl.trim().trimEnd('/').ifEmpty { Settings.DEFAULT_FLIBUSTA_BASE_URL },
    flibustaTimeoutSeconds = flibustaTimeoutSeconds.coerceIn(
        Settings.MIN_FLIBUSTA_TIMEOUT_SECONDS,
        Settings.MAX_FLIBUSTA_TIMEOUT_SECONDS,
    ),
    maxConcurrent = maxConcurrent.coerceIn(1, Settings.MAX_CONCURRENT_LIMIT),
    seedHours = seedHours.coerceAtLeast(0),
    noSeedersRetryMinutes = noSeedersRetryMinutes.coerceAtLeast(0),
)

private fun normalizeRoot(root: String?): String? {
    val trimmed = root?.trim()?.ifEmpty { null } ?: return null
    return trimmed.takeIf { it.startsWith("content://") }
}
