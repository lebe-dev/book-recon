package ru.tinyops.android.bookrecon.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import java.time.Instant

class QueueSummaryTest {
    private fun download(id: String, status: DownloadStatus, progress: DownloadProgress = DownloadProgress()) = Download(
        id = id,
        kind = MediaKind.AUDIO,
        source = Source.RUTRACKER,
        sourceId = id,
        title = "Книга $id",
        author = "",
        status = status,
        progress = progress,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun emptyQueue() {
        assertEquals(QueueSummary.EMPTY, summarize(emptyList()))
    }

    @Test
    fun followsTheFirstRunningDownload() {
        val summary = summarize(
            listOf(
                download("a", DownloadStatus.DOWNLOADING, DownloadProgress(25, 100)),
                download("b", DownloadStatus.DOWNLOADING, DownloadProgress(90, 100)),
                download("c", DownloadStatus.SEEDING),
                download("d", DownloadStatus.QUEUED),
                download("e", DownloadStatus.FAILED),
            ),
        )
        assertEquals("a", summary.current?.id)
        assertEquals(25, summary.percent)
        assertEquals(2, summary.running)
        assertEquals(1, summary.queued)
    }

    @Test
    fun unknownSizeHasNoPercent() {
        val summary = summarize(listOf(download("a", DownloadStatus.DOWNLOADING)))
        assertNull(summary.percent)
    }

    @Test
    fun onlyQueued() {
        val summary = summarize(listOf(download("a", DownloadStatus.QUEUED), download("b", DownloadStatus.QUEUED)))
        assertNull(summary.current)
        assertEquals(2, summary.queued)
    }
}
