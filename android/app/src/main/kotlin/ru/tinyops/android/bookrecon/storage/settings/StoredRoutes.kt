package ru.tinyops.android.bookrecon.storage.settings

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.route.RouteMemory
import ru.tinyops.android.bookrecon.core.route.RouteSnapshot

/** Where [StoredRoutes] keeps the routes. */
interface RouteStore {
    suspend fun load(): RouteSnapshot

    suspend fun save(snapshot: RouteSnapshot)
}

/**
 * A [RouteMemory] kept in [store] across restarts. The stored routes are read in [scope] (what
 * is learnt meanwhile wins over them), and every change after that is written, a burst of
 * changes once. A store that cannot be read leaves the memory empty.
 */
class StoredRoutes(private val store: RouteStore, scope: CoroutineScope) {
    private val pending = MutableStateFlow<RouteSnapshot?>(null)
    private val restored = CompletableDeferred<Unit>()

    val memory = RouteMemory(onChange = { pending.value = it })

    init {
        scope.launch {
            try {
                memory.restore(store.load())
            } catch (_: IOException) {
                // Nothing stored can be read: the routes are learnt again.
            } finally {
                restored.complete(Unit)
            }
            pending.value = memory.snapshot()
            pending.filterNotNull().collectLatest { store.save(it) }
        }
    }

    /** Returns once the stored routes are in the memory (or could not be read). */
    suspend fun awaitRestored() = restored.await()
}
