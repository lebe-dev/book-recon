package ru.tinyops.android.bookrecon.core.port

import kotlinx.coroutines.flow.Flow

/** The last submitted search queries, newest first, at most [LIMIT]. */
interface SearchHistory {
    val queries: Flow<List<String>>

    /** Puts [query] first; an equal query (ignoring case) already in the history moves up. */
    suspend fun add(query: String)

    suspend fun remove(query: String)

    companion object {
        const val LIMIT = 5

        /** [history] with [query] added the way [add] does it. */
        fun push(history: List<String>, query: String): List<String> {
            val entry = normalize(query)
            if (entry.isEmpty()) return history
            return (listOf(entry) + history.filterNot { it.equals(entry, ignoreCase = true) }).take(LIMIT)
        }

        /** One line with single spaces, so entries can be stored one per line. */
        fun normalize(query: String): String = query.trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("""\s+""")
    }
}
