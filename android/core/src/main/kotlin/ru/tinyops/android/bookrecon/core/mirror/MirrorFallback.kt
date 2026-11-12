package ru.tinyops.android.bookrecon.core.mirror

import ru.tinyops.android.bookrecon.core.domain.AppError

/**
 * Runs a request against the mirrors of a site in turn until one answers, and remembers the one
 * that did so it goes first next time. [site] names the site in messages.
 */
class MirrorFallback(private val site: String) {
    @Volatile
    private var lastWorking: String? = null

    /**
     * Runs [block] against [bases] (the remembered mirror first). Only failures to reach a
     * mirror move on to the next one; a missing book or a rejected password would be the same
     * everywhere.
     */
    suspend fun <T> run(bases: List<String>, block: suspend (base: String) -> T): T {
        var failure: AppError? = null
        for (base in ordered(bases)) {
            try {
                return block(base).also { lastWorking = base }
            } catch (e: AppError) {
                if (e !is AppError.Network && e !is AppError.ServiceDown && e !is AppError.Timeout) throw e
                failure = e
            }
        }
        throw failure ?: AppError.Network("no $site mirror is configured")
    }

    private fun ordered(bases: List<String>): List<String> {
        val preferred = lastWorking?.takeIf { it in bases } ?: return bases
        return listOf(preferred) + (bases - preferred)
    }
}
