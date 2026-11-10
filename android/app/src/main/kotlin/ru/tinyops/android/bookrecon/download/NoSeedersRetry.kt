package ru.tinyops.android.bookrecon.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Retries torrents that failed because nobody seeds them (NoSeeders, Stalled), after
 * `Settings.noSeedersRetryMinutes`, for as long as [run] runs: the app runs it while it is on
 * screen. Each failure gets its own timer, started when the download is seen failed; a
 * download retried, cancelled or dismissed by hand loses its timer. A changed interval
 * restarts all timers.
 */
class NoSeedersRetry(
    private val queue: DownloadQueue,
    private val settings: SettingsRepository,
    private val log: (String) -> Unit = {},
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        settings.settings.map { it.noSeedersRetryMinutes }.distinctUntilChanged().collectLatest { minutes ->
            if (minutes <= 0) return@collectLatest
            watch(minutes.minutes)
        }
    }

    private suspend fun watch(interval: Duration) = coroutineScope {
        val timers = mutableMapOf<String, Job>()
        queue.observe().collect { list ->
            val waiting = list.filter(::isWithoutSeeders).map { it.id }.toSet()
            timers.keys.filter { it !in waiting }.forEach { timers.remove(it)?.cancel() }
            waiting.filter { it !in timers }.forEach { id ->
                timers[id] = launch {
                    delay(interval)
                    log("Retrying download $id that had no seeders")
                    queue.retry(id)
                }
            }
        }
    }

    private fun isWithoutSeeders(download: Download): Boolean =
        download.status == DownloadStatus.FAILED && download.error?.code in RETRIED

    private companion object {
        val RETRIED = setOf(ErrorCode.NO_SEEDERS, ErrorCode.STALLED)
    }
}
