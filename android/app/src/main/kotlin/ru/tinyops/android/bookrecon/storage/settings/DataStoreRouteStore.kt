package ru.tinyops.android.bookrecon.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import ru.tinyops.android.bookrecon.core.route.RouteSnapshot

/** Keeps the routes that worked ([RouteSnapshot]) in the settings store as JSON. */
class DataStoreRouteStore(private val store: DataStore<Preferences>) {
    /** The stored routes; empty when none are stored or they cannot be read. */
    suspend fun load(): RouteSnapshot {
        val text = store.data.first()[KEY] ?: return RouteSnapshot()
        return try {
            json.decodeFromString<RouteSnapshot>(text)
        } catch (_: IllegalArgumentException) {
            // SerializationException is one: a damaged or outdated record is dropped.
            RouteSnapshot()
        }
    }

    suspend fun save(snapshot: RouteSnapshot) {
        val text = json.encodeToString(snapshot)
        store.edit { it[KEY] = text }
    }

    private companion object {
        val KEY = stringPreferencesKey("routes")
        val json = Json { ignoreUnknownKeys = true }
    }
}
