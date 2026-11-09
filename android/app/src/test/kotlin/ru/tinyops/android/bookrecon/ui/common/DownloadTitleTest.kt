package ru.tinyops.android.bookrecon.ui.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import java.time.Instant

class DownloadTitleTest {
    private fun download(kind: MediaKind, author: String, targetName: String?) = Download(
        id = "id",
        kind = kind,
        source = if (kind == MediaKind.AUDIO) Source.RUTRACKER else Source.FLIBUSTA,
        sourceId = "1",
        title = "Мастер и Маргарита [Читает Клюквин, 2020, MP3]",
        author = author,
        targetName = targetName,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun bookShowsAuthorAndTitle() {
        assertEquals(
            "Булгаков — Мастер и Маргарита [Читает Клюквин, 2020, MP3]",
            download(MediaKind.BOOK, "Булгаков", targetName = "ignored").displayTitle(),
        )
    }

    @Test
    fun audiobookShowsFolderName() {
        assertEquals("Булгаков - Мастер и Маргарита", download(MediaKind.AUDIO, "", "Булгаков - Мастер и Маргарита").displayTitle())
    }

    @Test
    fun audiobookWithoutFolderNameFallsBackToTitle() {
        assertEquals("Мастер и Маргарита [Читает Клюквин, 2020, MP3]", download(MediaKind.AUDIO, "", " ").displayTitle())
    }
}
