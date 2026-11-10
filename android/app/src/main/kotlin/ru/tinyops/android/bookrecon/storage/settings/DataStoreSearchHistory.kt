package ru.tinyops.android.bookrecon.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.tinyops.android.bookrecon.core.port.SearchHistory

/** [SearchHistory] in the settings store, one query per line. */
class DataStoreSearchHistory(private val store: DataStore<Preferences>) : SearchHistory {
    override val queries: Flow<List<String>> = store.data.map { it.history() }.distinctUntilChanged()

    override suspend fun add(query: String) {
        store.edit { it.setHistory(SearchHistory.push(it.history(), query)) }
    }

    override suspend fun remove(query: String) {
        store.edit { prefs -> prefs.setHistory(prefs.history().filterNot { it == query }) }
    }

    private fun Preferences.history(): List<String> =
        this[KEY]?.split('\n')?.filter { it.isNotEmpty() }?.take(SearchHistory.LIMIT) ?: emptyList()

    private fun androidx.datastore.preferences.core.MutablePreferences.setHistory(history: List<String>) {
        if (history.isEmpty()) remove(KEY) else this[KEY] = history.joinToString("\n")
    }

    private companion object {
        val KEY = stringPreferencesKey("search_history")
    }
}
