package ru.tinyops.android.bookrecon.core.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.tinyops.android.bookrecon.core.port.SearchHistory

/** In-memory [SearchHistory]. */
class FakeSearchHistory(initial: List<String> = emptyList()) : SearchHistory {
    private val state = MutableStateFlow(initial)

    override val queries: Flow<List<String>> = state

    override suspend fun add(query: String) = state.update { SearchHistory.push(it, query) }

    override suspend fun remove(query: String) = state.update { history -> history.filterNot { it == query } }
}
