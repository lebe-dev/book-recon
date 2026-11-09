package ru.tinyops.android.bookrecon.download

import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus

/**
 * What the ongoing download notification shows.
 *
 * @property current the download the notification follows and offers to cancel: the
 *   earliest running one.
 * @property percent progress of [current] in 0..100, null while its size is unknown.
 * @property running downloads in progress, [current] included.
 * @property queued downloads waiting for a slot.
 */
data class QueueSummary(
    val current: Download?,
    val percent: Int?,
    val running: Int,
    val queued: Int,
) {
    companion object {
        val EMPTY = QueueSummary(current = null, percent = null, running = 0, queued = 0)
    }
}

/** Summarizes the queue as ordered by [ru.tinyops.android.bookrecon.core.port.DownloadQueue.observe]. */
fun summarize(queue: List<Download>): QueueSummary {
    val running = queue.filter { it.status == DownloadStatus.DOWNLOADING }
    val current = running.firstOrNull()
    val percent = current?.progress?.takeIf { it.total > 0 }?.let { (it.fraction * PERCENT).toInt() }
    return QueueSummary(
        current = current,
        percent = percent,
        running = running.size,
        queued = queue.count { it.status == DownloadStatus.QUEUED },
    )
}

private const val PERCENT = 100
