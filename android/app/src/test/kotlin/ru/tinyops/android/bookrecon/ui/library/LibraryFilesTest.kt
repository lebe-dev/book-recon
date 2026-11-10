package ru.tinyops.android.bookrecon.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.fake.FakeFileStorage
import java.io.File
import java.time.Instant
import java.time.ZoneId

class LibraryFilesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `MIME type follows the extension, ignoring case`() {
        assertEquals("application/epub+zip", mimeTypeOf("Мастер и Маргарита.EPUB"))
        assertEquals("application/x-fictionbook+xml", mimeTypeOf("book.fb2"))
        assertEquals("application/x-zip-compressed-fb2", mimeTypeOf("book.fb2.zip"))
        assertEquals("application/x-mobipocket-ebook", mimeTypeOf("book.mobi"))
        assertEquals("application/pdf", mimeTypeOf("book.pdf"))
        assertEquals("image/vnd.djvu", mimeTypeOf("book.djvu"))
        assertEquals("audio/mpeg", mimeTypeOf("01.mp3"))
        assertEquals("audio/mp4", mimeTypeOf("book.m4b"))
        assertEquals("audio/flac", mimeTypeOf("01.flac"))
        assertEquals("*/*", mimeTypeOf("readme"))
        assertEquals("*/*", mimeTypeOf("archive.rar"))
    }

    @Test
    fun `format of a Flibusta book comes from the record, of a torrent from its files`() {
        assertEquals("EPUB", displayFormat(record(MediaKind.BOOK, format = Format.EPUB)))
        assertEquals(
            "FB2",
            displayFormat(record(MediaKind.BOOK, paths = listOf("/Books/Собрание.fb2.zip", "/Books/Том 2.fb2"))),
        )
        assertEquals(
            "PDF, DJVU",
            displayFormat(record(MediaKind.BOOK, paths = listOf("/Books/a.pdf", "/Books/b.djvu", "/Books/c.pdf"))),
        )
        val audio = record(
            MediaKind.AUDIO,
            files = listOf(
                TorrentFile(0, "Book/CD1/01.mp3", 1),
                TorrentFile(1, "Book/cover.jpg", 1),
                TorrentFile(2, "Book/CD2/01.MP3", 1),
            ),
        )
        assertEquals("MP3", displayFormat(audio))
        assertNull(displayFormat(record(MediaKind.AUDIO)))
    }

    @Test
    fun `date is shown in the user's time zone`() {
        val moscow = ZoneId.of("Europe/Moscow")
        assertEquals("10.11.2026", libraryDate(Instant.parse("2026-11-09T22:30:00Z"), moscow))
        assertEquals("09.11.2026", libraryDate(Instant.parse("2026-11-09T20:30:00Z"), moscow))
    }

    private val storage by lazy { FakeFileStorage(dir) }

    private fun fileUri(kind: MediaKind, path: String): String = storage.uriOf(kind, path)!!

    @Test
    fun `book opens its first existing file`() {
        File(dir, "Books/Том 2.epub").apply { parentFile?.mkdirs() }.writeText("x")
        val download = record(MediaKind.BOOK, paths = listOf("Том 1.epub", "Том 2.epub"))

        assertEquals(
            OpenTarget.Open(fileUri(MediaKind.BOOK, "Том 2.epub"), "application/epub+zip"),
            resolveOpenTarget(download, storage),
        )
    }

    @Test
    fun `audiobook opens its first audio file in natural order, looking into subfolders`() {
        val book = File(dir, "Audiobooks/Автор - Книга")
        listOf("cover.jpg", "CD1/10 Глава.mp3", "CD1/2 Глава.mp3", "CD2/01 Глава.mp3", "CD1/notes.txt").forEach {
            File(book, it).apply { parentFile?.mkdirs() }.writeText("x")
        }

        val target = resolveOpenTarget(record(MediaKind.AUDIO, paths = listOf("Автор - Книга")), storage)

        assertEquals(OpenTarget.Open(fileUri(MediaKind.AUDIO, "Автор - Книга/CD1/2 Глава.mp3"), "audio/mpeg"), target)
    }

    @Test
    fun `audiobook path that is a single file opens that file`() {
        File(dir, "Audiobooks/Книга.m4b").apply { parentFile?.mkdirs() }.writeText("x")

        assertEquals(
            OpenTarget.Open(fileUri(MediaKind.AUDIO, "Книга.m4b"), "audio/mp4"),
            resolveOpenTarget(record(MediaKind.AUDIO, paths = listOf("Книга.m4b")), storage),
        )
    }

    @Test
    fun `audiobook folder without audio files has nothing to open`() {
        val book = File(dir, "Audiobooks/Книга").apply { mkdirs() }
        File(book, "cover.jpg").writeText("x")

        assertEquals(OpenTarget.NoAudioFiles, resolveOpenTarget(record(MediaKind.AUDIO, paths = listOf("Книга")), storage))
    }

    @Test
    fun `nothing on disk is reported as missing`() {
        assertEquals(OpenTarget.Missing, resolveOpenTarget(record(MediaKind.BOOK, paths = listOf("gone.epub")), storage))
        assertEquals(OpenTarget.Missing, resolveOpenTarget(record(MediaKind.BOOK), storage))
    }

    private fun record(
        kind: MediaKind,
        format: Format? = null,
        paths: List<String> = emptyList(),
        files: List<TorrentFile> = emptyList(),
    ) = Download(
        id = "d",
        kind = kind,
        source = if (format != null) Source.FLIBUSTA else Source.RUTRACKER,
        sourceId = "1",
        title = "Книга",
        author = "",
        format = format,
        status = DownloadStatus.COMPLETED,
        files = files,
        paths = paths,
        createdAt = Instant.EPOCH,
    )
}
