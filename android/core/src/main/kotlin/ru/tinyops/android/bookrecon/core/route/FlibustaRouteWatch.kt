package ru.tinyops.android.bookrecon.core.route

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.SourceRoute
import ru.tinyops.android.bookrecon.core.domain.hostOf
import ru.tinyops.android.bookrecon.core.mirror.MirrorFallback
import ru.tinyops.android.bookrecon.core.mirror.MirrorSites
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Decides when to run a [FlibustaRouteCheck] in [scope]: at the start and after a change of
 * the Flibusta settings when nothing is known about their mirrors yet (no working address, no
 * remembered mirror), and when
 * Flibusta has trouble, at most once per [cooldown]. Only while Flibusta is on and reached
 * directly: through the server the addresses of the site are not ours to pick. One check at
 * a time; [ready] is waited for first, so the stored routes are in before anything is decided.
 */
class FlibustaRouteWatch(
    private val check: FlibustaRouteCheck,
    private val memory: RouteMemory,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val ready: suspend () -> Unit = {},
    private val cooldown: Duration = DEFAULT_COOLDOWN,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastStartedAt: Long? = null

    /**
     * Checks the routes unless they are known, now and whenever the Flibusta settings change
     * (another mirror, the route, Flibusta turned on). Called once at app start; the job lasts
     * as long as [scope].
     */
    fun start(): Job = scope.launch {
        ready()
        settings.settings
            .distinctUntilChangedBy { Triple(it.flibustaEnabled, it.flibustaRoute, it.flibustaUrls) }
            .collect { current ->
                if (applies(current) && !knows(current)) runCheck(current, "no route is known yet")
            }
    }

    /** Flibusta could not be reached: checks the routes again unless a check ran lately. */
    fun reportTrouble(): Job = scope.launch {
        ready()
        val current = settings.settings.first()
        if (!applies(current)) return@launch
        val last = lastStartedAt
        if (last != null && now() - last < cooldown.inWholeMilliseconds) return@launch
        runCheck(current, "Flibusta had trouble")
    }

    private fun applies(settings: Settings): Boolean =
        settings.flibustaEnabled && settings.flibustaRoute == SourceRoute.DIRECT

    private fun knows(settings: Settings): Boolean {
        val mirrors = settings.flibustaUrls
        val key = MirrorFallback.keyOf(MirrorSites.FLIBUSTA, mirrors)
        return (key != null && memory.preferred(key) != null) || memory.knowsWorkingAddress(mirrors.map(::hostOf))
    }

    @Suppress("TooGenericExceptionCaught") // a broken check must not end the watch
    private suspend fun runCheck(settings: Settings, reason: String) {
        if (!running.compareAndSet(false, true)) return
        try {
            lastStartedAt = now()
            log("Flibusta route check started: $reason")
            check.run(settings.flibustaUrls)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Flibusta route check failed: ${e.message}")
        } finally {
            running.set(false)
        }
    }

    companion object {
        val DEFAULT_COOLDOWN: Duration = 2.minutes
    }
}
