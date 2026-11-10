package ru.tinyops.android.bookrecon.storage.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DataStoreSearchHistoryTest {
    @TempDir
    lateinit var dir: File

    private fun open(job: Job) = DataStoreSearchHistory(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(dir, "settings.preferences_pb") },
    )

    @Test
    fun `history survives reopening and keeps the newest five`() = runBlocking {
        val first = Job()
        val history = open(first)
        (1..6).forEach { history.add("запрос $it") }
        history.remove("запрос 4")
        first.cancelAndJoin()

        val second = Job()
        val reopened = open(second)

        assertEquals(listOf("запрос 6", "запрос 5", "запрос 3", "запрос 2"), reopened.queries.first())
        second.cancelAndJoin()
    }
}
