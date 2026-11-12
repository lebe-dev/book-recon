package ru.tinyops.android.bookrecon.core.route

import kotlinx.serialization.Serializable
import ru.tinyops.android.bookrecon.core.mirror.MirrorMemory

/**
 * What one address of a host did last, in milliseconds of the clock; 0 means never.
 * The address works when it answered after its last failure.
 */
@Serializable
data class AddressRecord(val okAt: Long = 0, val failedAt: Long = 0) {
    val works: Boolean get() = okAt > failedAt
}

/** Everything [RouteMemory] knows, in a form that can be stored. */
@Serializable
data class RouteSnapshot(
    val mirrors: Map<String, String> = emptyMap(),
    val addresses: Map<String, Map<String, AddressRecord>> = emptyMap(),
)

/**
 * The routes that worked: which mirror of a site answered ([MirrorMemory]) and which addresses
 * of a host answered or failed. Sites such as Flibusta resolve to several addresses, some of
 * which may not answer for hours; trying the one that worked first saves a timeout.
 *
 * Thread-safe and in memory; [onChange] gets a snapshot whenever the preferred route changes
 * (not on every answer), so the caller can store it, and [restore] brings a stored one back.
 */
class RouteMemory(
    private val now: () -> Long = System::currentTimeMillis,
    private val onChange: (RouteSnapshot) -> Unit = {},
) : MirrorMemory {
    private val lock = Any()
    private val mirrors = mutableMapOf<String, String>()
    private val addresses = mutableMapOf<String, MutableMap<String, AddressRecord>>()

    override fun preferred(key: String): String? = synchronized(lock) { mirrors[key] }

    override fun remember(key: String, base: String) {
        val changed = synchronized(lock) { mirrors.put(key, base) != base }
        if (changed) changed()
    }

    /**
     * [addresses] of [host] in the order to try them: those that work, the last to answer
     * first; then those never tried, in the given order; then those that failed, the one that
     * failed longest ago first, since it had the most time to recover.
     */
    fun order(host: String, addresses: List<String>): List<String> {
        val known = synchronized(lock) { this.addresses[host]?.toMap() } ?: return addresses
        val working = addresses.filter { known[it]?.works == true }.sortedByDescending { known.getValue(it).okAt }
        val unknown = addresses.filter { it !in known }
        val failed = addresses.filter { known[it]?.works == false }.sortedBy { known.getValue(it).failedAt }
        return working + unknown + failed
    }

    /** [address] of [host] answered. */
    fun addressWorked(host: String, address: String) {
        val changed = synchronized(lock) {
            val records = recordsOf(host)
            val before = records[address]
            val wasFirst = before?.works == true && records.values.none { it.works && it.okAt > before.okAt }
            records[address] = (before ?: AddressRecord()).copy(okAt = now())
            trim()
            !wasFirst
        }
        if (changed) changed()
    }

    /** [address] of [host] could not be reached or did not answer in time. */
    fun addressFailed(host: String, address: String) {
        val changed = synchronized(lock) {
            val records = recordsOf(host)
            val before = records[address]
            records[address] = (before ?: AddressRecord()).copy(failedAt = now())
            trim()
            before?.works != false
        }
        if (changed) changed()
    }

    /** Takes in a stored [snapshot]; what was learnt since the start wins over it. */
    fun restore(snapshot: RouteSnapshot) {
        synchronized(lock) {
            snapshot.mirrors.forEach { (key, base) -> mirrors.putIfAbsent(key, base) }
            snapshot.addresses.forEach { (host, stored) ->
                val records = addresses.getOrPut(host) { mutableMapOf() }
                stored.forEach { (address, record) -> records.putIfAbsent(address, record) }
            }
            trim()
        }
    }

    fun snapshot(): RouteSnapshot = synchronized(lock) {
        RouteSnapshot(mirrors.toMap(), addresses.mapValues { it.value.toMap() })
    }

    private fun recordsOf(host: String): MutableMap<String, AddressRecord> = addresses.getOrPut(host) { mutableMapOf() }

    /** Keeps the [MAX_HOSTS] hosts used last and [MAX_ADDRESSES] addresses of each. */
    private fun trim() {
        addresses.values.forEach { records ->
            if (records.size > MAX_ADDRESSES) {
                records.entries.sortedBy { lastUse(it.value) }.take(records.size - MAX_ADDRESSES).forEach { records.remove(it.key) }
            }
        }
        if (addresses.size > MAX_HOSTS) {
            addresses.entries
                .sortedBy { entry -> entry.value.values.maxOfOrNull(::lastUse) ?: Long.MAX_VALUE }
                .take(addresses.size - MAX_HOSTS)
                .forEach { addresses.remove(it.key) }
        }
    }

    private fun lastUse(record: AddressRecord): Long = maxOf(record.okAt, record.failedAt)

    private fun changed() = onChange(snapshot())

    companion object {
        const val MAX_HOSTS = 32
        const val MAX_ADDRESSES = 16
    }
}
