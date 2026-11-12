package ru.tinyops.android.bookrecon.core.mirror

import ru.tinyops.android.bookrecon.core.domain.AppError
import java.util.concurrent.ConcurrentHashMap

/** Remembers which mirror answered, by a key the [MirrorFallback] builds. */
interface MirrorMemory {
    /** The mirror that answered last for [key], null when none did. */
    fun preferred(key: String): String?

    /** Notes that [base] answered for [key]. */
    fun remember(key: String, base: String)
}

/** [MirrorMemory] that lives as long as the process. */
class InMemoryMirrorMemory : MirrorMemory {
    private val mirrors = ConcurrentHashMap<String, String>()

    override fun preferred(key: String): String? = mirrors[key]

    override fun remember(key: String, base: String) {
        mirrors[key] = base
    }
}

/** Names of the sites in [MirrorFallback] keys. */
object MirrorSites {
    const val FLIBUSTA = "Flibusta"
    const val RUTRACKER = "RuTracker"
}

/**
 * Runs a request against the mirrors of a site in turn until one answers, and remembers the one
 * that did so it goes first next time. [site] names the site in messages and in [memory]; what
 * is remembered belongs to the first mirror of the list ([keyOf]), so a mirror the user picks
 * anew is tried first again. [onTrouble] hears about every mirror that could not be reached.
 */
class MirrorFallback(
    private val site: String,
    private val memory: MirrorMemory = InMemoryMirrorMemory(),
    private val onTrouble: (base: String, error: AppError) -> Unit = { _, _ -> },
) {
    /**
     * Runs [block] against [bases] (the remembered mirror first). Only failures to reach a
     * mirror move on to the next one; a missing book or a rejected password would be the same
     * everywhere. When every mirror fails, the error of the one that got furthest is thrown:
     * a server error over a timeout over no connection, the earlier mirror on a tie.
     */
    suspend fun <T> run(bases: List<String>, block: suspend (base: String) -> T): T {
        val key = keyOf(site, bases)
        var failure: AppError? = null
        for (base in ordered(key, bases)) {
            try {
                return block(base).also { if (key != null && memory.preferred(key) != base) memory.remember(key, base) }
            } catch (e: AppError) {
                if (!isUnreachable(e)) throw e
                onTrouble(base, e)
                if (failure == null || reach(e) > reach(failure)) failure = e
            }
        }
        throw failure ?: AppError.Network("no $site mirror is configured")
    }

    private fun ordered(key: String?, bases: List<String>): List<String> {
        val preferred = key?.let(memory::preferred)?.takeIf { it in bases } ?: return bases
        return listOf(preferred) + (bases - preferred)
    }

    companion object {
        /** The [MirrorMemory] key of [site] with the mirror list [bases]; null for no mirrors. */
        fun keyOf(site: String, bases: List<String>): String? = bases.firstOrNull()?.let { "$site $it" }

        /** True for an error that says the mirror could not be reached, not what it answered. */
        fun isUnreachable(error: AppError): Boolean =
            error is AppError.Network || error is AppError.ServiceDown || error is AppError.Timeout
    }

    /** How far a request got before failing with [error]: the higher, the more telling. */
    private fun reach(error: AppError): Int = when (error) {
        is AppError.ServiceDown -> 2
        is AppError.Timeout -> 1
        else -> 0
    }
}
