package ru.tinyops.android.bookrecon.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.route.AddressRecord
import ru.tinyops.android.bookrecon.core.route.RouteSnapshot
import java.io.File

class StoredRoutesTest {
    @TempDir
    lateinit var dir: File

    private fun open(job: Job): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(dir, "settings.preferences_pb") }

    @Test
    fun `the store keeps a snapshot and gives an empty one when nothing or garbage is stored`() = runBlocking {
        val job = Job()
        val data = open(job)
        val store = DataStoreRouteStore(data)
        assertEquals(RouteSnapshot(), store.load())

        val snapshot = RouteSnapshot(
            mirrors = mapOf("Flibusta https://flibusta.site" to "https://flibusta.is"),
            addresses = mapOf("flibusta.site" to mapOf("5.45.87.206" to AddressRecord(okAt = 5, failedAt = 2))),
        )
        store.save(snapshot)
        assertEquals(snapshot, store.load())

        data.edit { it[stringPreferencesKey("routes")] = "{not json" }
        assertEquals(RouteSnapshot(), store.load())
        job.cancelAndJoin()
    }

    @Test
    fun `routes learnt in one run are there in the next`() = runBlocking {
        val first = Job()
        val data = open(first)
        val routes = StoredRoutes(DataStoreRouteStore(data), CoroutineScope(Dispatchers.IO + first))
        routes.awaitRestored()
        routes.memory.addressFailed("flibusta.site", "5.61.56.204")
        routes.memory.addressWorked("flibusta.site", "5.45.87.206")
        routes.memory.remember("Flibusta https://flibusta.site", "https://flibusta.is")
        waitUntilStored(data) { "https://flibusta.is" in it && "5.45.87.206" in it }
        first.cancelAndJoin()

        val second = Job()
        val reopened = StoredRoutes(DataStoreRouteStore(open(second)), CoroutineScope(Dispatchers.IO + second))
        reopened.awaitRestored()

        assertEquals("https://flibusta.is", reopened.memory.preferred("Flibusta https://flibusta.site"))
        assertEquals(
            listOf("5.45.87.206", "5.61.59.57", "5.61.56.204"),
            reopened.memory.order("flibusta.site", listOf("5.61.56.204", "5.61.59.57", "5.45.87.206")),
        )
        second.cancelAndJoin()
    }

    @Test
    fun `what is learnt before the stored routes are read is kept and saved`() = runBlocking {
        val first = Job()
        val data = open(first)
        DataStoreRouteStore(data).save(
            RouteSnapshot(addresses = mapOf("flibusta.site" to mapOf("5.61.56.204" to AddressRecord(okAt = 1)))),
        )
        val routes = StoredRoutes(DataStoreRouteStore(data), CoroutineScope(Dispatchers.IO + first))
        routes.memory.addressFailed("flibusta.site", "5.61.56.204")
        routes.awaitRestored()

        assertEquals(false, routes.memory.snapshot().addresses.getValue("flibusta.site").getValue("5.61.56.204").works)
        waitUntilStored(data) { "failedAt" in it }
        first.cancelAndJoin()
    }

    @Test
    fun `a store that cannot be read still lets the routes work`() = runBlocking {
        val broken = object : RouteStore {
            override suspend fun load(): RouteSnapshot = throw java.io.IOException("disk is gone")
            override suspend fun save(snapshot: RouteSnapshot) = Unit
        }
        val job = Job()
        val routes = StoredRoutes(broken, CoroutineScope(Dispatchers.IO + job))

        withTimeout(5_000) { routes.awaitRestored() }
        routes.memory.addressWorked("flibusta.site", "5.45.87.206")
        assertTrue(routes.memory.knowsWorkingAddress(listOf("flibusta.site")))
        job.cancelAndJoin()
    }

    private suspend fun waitUntilStored(data: DataStore<Preferences>, done: (String) -> Boolean) = withTimeout(5_000) {
        while (!done(data.data.first()[stringPreferencesKey("routes")].orEmpty())) delay(20)
    }
}
