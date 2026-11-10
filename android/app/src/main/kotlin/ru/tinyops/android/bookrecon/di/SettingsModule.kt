package ru.tinyops.android.bookrecon.di

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.storage.files.AndroidFileStorage
import ru.tinyops.android.bookrecon.storage.files.TreeUris
import ru.tinyops.android.bookrecon.storage.settings.AesGcmCipher
import ru.tinyops.android.bookrecon.storage.settings.DataStoreSettingsRepository
import ru.tinyops.android.bookrecon.storage.settings.KeystoreKeys

/** One store per process: DataStore refuses two active instances on the same file. */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsModule(private val app: AppContainer) {
    /** The settings DataStore; the server module keeps its own keys in it. */
    val dataStore: DataStore<Preferences> get() = app.context.settingsStore

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(
            store = app.context.settingsStore,
            cipher = AesGcmCipher { KeystoreKeys.aesKey(PASSWORD_KEY_ALIAS) },
            log = { Log.w(TAG, it) },
        )
    }

    /** The real storage, with the root check the settings screen needs. */
    val storage: AndroidFileStorage by lazy {
        AndroidFileStorage(
            settings = settingsRepository,
            hasAccess = { Environment.isExternalStorageManager() },
        )
    }

    val fileStorage: FileStorage get() = storage

    /** Converts a folder picked in the system picker into a path on shared storage. */
    fun pathOfTree(treeUri: String): String? =
        TreeUris.pathOf(treeUri, primaryRoot = Environment.getExternalStorageDirectory().path)

    private companion object {
        const val TAG = "Settings"
        const val PASSWORD_KEY_ALIAS = "rutracker_password"
    }
}
