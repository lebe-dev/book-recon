package ru.tinyops.android.bookrecon.torrent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.TorrentFile

class TorrentParserTest {
    @Test
    fun `multi-file torrent keeps the root folder and torrent indexes`() {
        val torrent = TestTorrent(
            "Author - Title",
            listOf(TestFile("CD1/01.mp3", 1000), TestFile("CD1/02.mp3", 2000), TestFile("cover.jpg", 30)),
        )

        val info = TorrentParser.parse(torrent.bytes)

        assertEquals(torrent.infoHash, info.infoHash)
        assertEquals("Author - Title", info.name)
        assertEquals(
            listOf(
                TorrentFile(0, "Author - Title/CD1/01.mp3", 1000),
                TorrentFile(1, "Author - Title/CD1/02.mp3", 2000),
                TorrentFile(2, "Author - Title/cover.jpg", 30),
            ),
            info.files,
        )
    }

    @Test
    fun `single-file torrent is one file named after the torrent`() {
        val bytes = TestBencode.encode(
            mapOf(
                "info" to mapOf(
                    "name" to "book.fb2",
                    "length" to 1234L,
                    "piece length" to 16384,
                    "pieces" to ByteArray(20),
                ),
            ),
        )

        val info = TorrentParser.parse(bytes)

        assertEquals(listOf(TorrentFile(0, "book.fb2", 1234)), info.files)
        assertEquals(1234, info.totalSize)
    }

    @Test
    fun `padding files are skipped but indexes stay those of the torrent`() {
        val torrent = TestTorrent(
            "Book",
            listOf(
                TestFile("a.mp3", 100),
                TestFile(listOf(".pad", "16284"), ByteArray(16284), attr = "p"),
                TestFile(listOf("_____padding_file_0_"), ByteArray(10)),
                TestFile("b.mp3", 100),
            ),
        )

        val info = TorrentParser.parse(torrent.bytes)

        assertEquals(listOf(0, 3), info.files.map { it.index })
        assertEquals(listOf("Book/a.mp3", "Book/b.mp3"), info.files.map { it.path })
    }

    @Test
    fun `unsafe paths are skipped`() {
        val torrent = TestTorrent(
            "Book",
            listOf(
                TestFile(listOf("..", "evil.sh"), ByteArray(1)),
                TestFile(listOf("", "etc", "passwd"), ByteArray(1)),
                TestFile(listOf("a", ".", "b"), ByteArray(1)),
                TestFile(listOf("dir/../../x"), ByteArray(1)),
                TestFile(listOf("back\\slash"), ByteArray(1)),
                TestFile(emptyList(), ByteArray(1)),
                TestFile("ok.mp3", 5),
            ),
        )

        val info = TorrentParser.parse(torrent.bytes)

        assertEquals(listOf(TorrentFile(6, "Book/ok.mp3", 5)), info.files)
    }

    @Test
    fun `utf-8 path is preferred and legacy cp1251 names are decoded`() {
        val cp1251 = "Глава 1.mp3".toByteArray(charset("windows-1251"))
        val torrent = TestTorrent(
            "Книга",
            listOf(
                TestFile(listOf(cp1251), ByteArray(3), extra = mapOf("path.utf-8" to listOf("Глава 1 (utf).mp3"))),
                TestFile(listOf("Глава 2.mp3".toByteArray(charset("windows-1251"))), ByteArray(4)),
            ),
        )

        val info = TorrentParser.parse(torrent.bytes)

        assertEquals(listOf("Книга/Глава 1 (utf).mp3", "Книга/Глава 2.mp3"), info.files.map { it.path })
    }

    @Test
    fun `info hash covers the raw info dictionary even with unknown keys`() {
        val torrent = TestTorrent("Book", listOf(TestFile("a.mp3", 10)), infoExtra = mapOf("private" to 1, "source" to "[rutracker.org]"))

        assertEquals(torrent.infoHash, TorrentParser.parse(torrent.bytes).infoHash)
    }

    @Test
    fun `garbage and incomplete torrents are parse errors`() {
        val cases = listOf(
            ByteArray(0),
            "not a torrent".toByteArray(),
            "d4:infoi1ee".toByteArray(),
            "d4:infod4:name1:xee".toByteArray(),
            "d4:infod4:name1:x5:filesli1eeee".toByteArray(),
            "d4:info".toByteArray(),
            "d4:infod4:name4:Book9:file treedee6:lengthi1eee".toByteArray(),
            "d4:infod4:name2:..6:lengthi1eee".toByteArray(),
            "d4:infod4:name1:x6:lengthi-1eee".toByteArray(),
        )

        cases.forEach { bytes ->
            assertThrows(AppError.ParseError::class.java, { TorrentParser.parse(bytes) }, bytes.decodeToString())
        }
    }

    @Test
    fun `v2-only torrent is rejected`() {
        val bytes = TestBencode.encode(
            mapOf(
                "info" to mapOf(
                    "name" to "Book",
                    "meta version" to 2,
                    "piece length" to 16384,
                    "file tree" to mapOf("a.mp3" to mapOf("" to mapOf("length" to 1L))),
                ),
            ),
        )

        assertThrows(AppError.ParseError::class.java) { TorrentParser.parse(bytes) }
    }

    @Test
    fun `deep nesting does not overflow the stack`() {
        val bytes = ("d4:info" + "l".repeat(100_000)).toByteArray()

        assertThrows(AppError.ParseError::class.java) { TorrentParser.parse(bytes) }
    }
}
