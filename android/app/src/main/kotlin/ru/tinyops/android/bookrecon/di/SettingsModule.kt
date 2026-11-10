package ru.tinyops.android.bookrecon.di

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.storage.files.AndroidFileStorage
import ru.tinyops.android.bookrecon.storage.files.DocumentsTree
import ru.tinyops.android.bookrecon.storage.settings.AesGcmCipher
import ru.tinyops.android.bookrecon.storage.settings.DataStoreSettingsRepository
import ru.tinyops.android.bookrecon.storage.settings.KeystoreKeys
import java.io.File

/** One store per process: DataStore refuses two active instances on the same file. */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsModule(private val app: AppContainer) {
    /** The settings DataStore; the server module keeps its own keys in it. */
    val dataStore: DataStore<Preferences> get() = app.context.settingsStore

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(
            store = app.context.settingsStore,
            cipher = AesGcmCipher { KeystoreKeys.aesKey(PASSWORD_KEY_ALIAS) },
            log = { app.log.appLog.warn(TAG, it) },
        )
    }

    /**
     * The real storage, with the root check the settings screen needs. Downloads are staged
     * in the app's private folder on external storage, which needs no permission, and fall
     * back to internal storage where there is none.
     */
    val storage: AndroidFileStorage by lazy {
        AndroidFileStorage(
            settings = settingsRepository,
            tree = DocumentsTree(app.context.contentResolver),
            stagingBase = File(app.context.getExternalFilesDir(null) ?: app.context.filesDir, STAGING_FOLDER),
            primaryRoot = Environment.getExternalStorageDirectory().path,
        )
    }

    val fileStorage: FileStorage get() = storage

    /**
     * Asks the system to keep the grant on a folder the user picked, so the roots stay
     * reachable after a restart. False when the system refuses.
     */
    fun takeGrant(treeUri: String): Boolean = try {
        app.context.contentResolver.takePersistableUriPermission(Uri.parse(treeUri), GRANT_FLAGS)
        true
    } catch (e: SecurityException) {
        app.log.appLog.warn(TAG, "The system did not keep the grant on the picked folder", e)
        false
    }

    /** Gives a grant back, so the app keeps access to its two roots only. */
    fun releaseGrant(treeUri: String) {
        try {
            app.context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), GRANT_FLAGS)
        } catch (e: SecurityException) {
            app.log.appLog.warn(TAG, "Cannot release the grant on a folder", e)
        }
    }

    private companion object {
        const val TAG = "Settings"
        const val STAGING_FOLDER = "staging"
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        const val PASSWORD_KEY_ALIAS = "rutracker_password"
    }
}
