package ru.tinyops.android.bookrecon.core.fake

import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import java.time.Instant

/** Sample data in Russian for fakes, previews and tests. */
object FakeData {
    private const val MB = 1024L * 1024L

    /** Topic with a two-disc audiobook: `CD1/` and `CD2/` folders plus a cover and a text file. */
    const val AUDIO_TOPIC_CD = "6012345"

    /** Topic with a single-folder audiobook. */
    const val AUDIO_TOPIC_FLAT = "6023456"

    /** E-book topic with several formats of one book. */
    const val BOOK_TOPIC_FORMATS = "5934567"

    /** E-book topic with a multi-volume collection in nested folders. */
    const val BOOK_TOPIC_COLLECTION = "4945678"

    val flibustaBooks: List<FlibustaBookDetails> = listOf(
        FlibustaBookDetails("11111", "Мастер и Маргарита", "Михаил Булгаков", listOf(Format.FB2, Format.EPUB, Format.MOBI)),
        FlibustaBookDetails("22222", "Преступление и наказание", "Фёдор Достоевский", listOf(Format.FB2, Format.EPUB)),
        FlibustaBookDetails(
            "33333",
            "Пикник на обочине",
            "Аркадий Стругацкий, Борис Стругацкий",
            listOf(Format.FB2, Format.EPUB, Format.MOBI, Format.PDF),
        ),
        FlibustaBookDetails("44444", "Война и мир", "Лев Толстой", listOf(Format.FB2, Format.EPUB, Format.DJVU)),
        FlibustaBookDetails("55555", "Мы", "Евгений Замятин", listOf(Format.PDF)),
        FlibustaBookDetails("66666", "Мастер и Маргарита. Черновые редакции", "Михаил Булгаков", listOf(Format.FB2)),
    )

    val topics: List<TorrentTopic> by lazy {
        listOf(
            TorrentTopic(
                AUDIO_TOPIC_CD,
                "Булгаков Михаил - Мастер и Маргарита [Басилашвили Олег, 2008, MP3, 128 kbps]",
                size = 0,
                seeders = 42,
                peers = 3,
                kind = MediaKind.AUDIO,
            ),
            TorrentTopic(
                AUDIO_TOPIC_FLAT,
                "Стругацкие Аркадий и Борис - Пикник на обочине [Герасимов Вячеслав, 2011, MP3, 96 kbps]",
                size = 0,
                seeders = 7,
                peers = 1,
                kind = MediaKind.AUDIO,
            ),
            TorrentTopic(
                BOOK_TOPIC_FORMATS,
                "Достоевский Фёдор - Преступление и наказание [2015, EPUB, FB2, MOBI, eBook]",
                size = 0,
                seeders = 15,
                peers = 0,
                kind = MediaKind.BOOK,
            ),
            TorrentTopic(
                BOOK_TOPIC_COLLECTION,
                "Толстой Лев - Собрание сочинений в 22 томах (1978-1985) [DjVu, PDF]",
                size = 0,
                seeders = 4,
                peers = 2,
                kind = MediaKind.BOOK,
            ),
        ).map { it.copy(size = torrentInfo(it.topicId).totalSize) }
    }

    /** Torrent content of a sample topic; throws for an unknown topic. */
    fun torrentInfo(topicId: String): TorrentInfo = torrents.getValue(topicId)

    /** Bytes the fake tracker returns for [topicId]; the fake engine parses them back. */
    fun torrentBytes(topicId: String): ByteArray = "$TORRENT_PREFIX$topicId".toByteArray()

    /** Topic ID encoded in fake torrent bytes, or null for anything else. */
    fun topicIdOf(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.startsWith(TORRENT_PREFIX)) return null
        return text.removePrefix(TORRENT_PREFIX).takeIf { it in torrents }
    }

    /** Completed downloads for the library screen; one of them has its file deleted. */
    fun library(now: Instant): List<Download> = listOf(
        Download(
            id = "sample-1",
            kind = MediaKind.BOOK,
            source = Source.FLIBUSTA,
            sourceId = "11111",
            title = "Мастер и Маргарита",
            author = "Михаил Булгаков",
            format = Format.EPUB,
            status = DownloadStatus.COMPLETED,
            paths = listOf("Мастер и Маргарита.epub"),
            size = 1_234_567,
            createdAt = now.minusSeconds(3 * DAY),
            completedAt = now.minusSeconds(3 * DAY - 20),
        ),
        Download(
            id = "sample-2",
            kind = MediaKind.AUDIO,
            source = Source.RUTRACKER,
            sourceId = AUDIO_TOPIC_FLAT,
            title = "Стругацкие Аркадий и Борис - Пикник на обочине",
            author = "",
            targetName = "Стругацкие Аркадий и Борис - Пикник на обочине",
            status = DownloadStatus.COMPLETED,
            files = torrentInfo(AUDIO_TOPIC_FLAT).files,
            paths = listOf("Стругацкие Аркадий и Борис - Пикник на обочине"),
            size = torrentInfo(AUDIO_TOPIC_FLAT).totalSize,
            createdAt = now.minusSeconds(DAY),
            completedAt = now.minusSeconds(DAY - 900),
        ),
        Download(
            id = "sample-3",
            kind = MediaKind.BOOK,
            source = Source.FLIBUSTA,
            sourceId = "55555",
            title = "Мы",
            author = "Евгений Замятин",
            format = Format.PDF,
            status = DownloadStatus.COMPLETED,
            paths = listOf(MISSING_SAMPLE_PATH),
            size = 3_456_789,
            createdAt = now.minusSeconds(7 * DAY),
            completedAt = now.minusSeconds(7 * DAY - 15),
        ),
    )

    /** Path of the sample download whose file is gone; see [sampleFileExists]. */
    const val MISSING_SAMPLE_PATH = "Мы.pdf"

    /** File check for the sample library: everything exists except [MISSING_SAMPLE_PATH]. */
    fun sampleFileExists(path: String): Boolean = path != MISSING_SAMPLE_PATH

    private const val TORRENT_PREFIX = "fake-torrent:"
    private const val DAY = 24L * 60 * 60

    private val torrents: Map<String, TorrentInfo> = mapOf(
        AUDIO_TOPIC_CD to torrent(
            "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            "Булгаков - Мастер и Маргарита",
            "CD1/01 Глава 1. Никогда не разговаривайте с неизвестными.mp3" to 24 * MB,
            "CD1/02 Глава 2. Понтий Пилат.mp3" to 41 * MB,
            "CD1/10 Глава 10. Вести из Ялты.mp3" to 18 * MB,
            "CD2/01 Глава 13. Явление героя.mp3" to 37 * MB,
            "CD2/02 Глава 14. Слава петуху!.mp3" to 15 * MB,
            "CD2/11 Глава 32. Прощение и вечный приют.mp3" to 12 * MB,
            "cover.jpg" to 350 * 1024L,
            "Описание.txt" to 2 * 1024L,
        ),
        AUDIO_TOPIC_FLAT to torrent(
            "b2c3d4e5f60718293a4b5c6d7e8f901234567890",
            "Пикник на обочине",
            "01. Пролог.mp3" to 9 * MB,
            "02. Часть 1.mp3" to 48 * MB,
            "03. Часть 2.mp3" to 51 * MB,
            "04. Часть 3.mp3" to 46 * MB,
        ),
        BOOK_TOPIC_FORMATS to torrent(
            "c3d4e5f60718293a4b5c6d7e8f90123456789012",
            "Преступление и наказание",
            "Достоевский - Преступление и наказание.epub" to 1 * MB,
            "Достоевский - Преступление и наказание.fb2" to 3 * MB,
            "Достоевский - Преступление и наказание.mobi" to 2 * MB,
            "cover.jpg" to 200 * 1024L,
        ),
        BOOK_TOPIC_COLLECTION to torrent(
            "d4e5f60718293a4b5c6d7e8f9012345678901234",
            "Толстой - Собрание сочинений",
            "Том 01. Повести и рассказы 1852-1856/Том 01.djvu" to 12 * MB,
            "Том 04. Война и мир. Том 1/Том 04.djvu" to 15 * MB,
            "Том 05. Война и мир. Том 2/Том 05.djvu" to 14 * MB,
            "Том 05. Война и мир. Том 2/Том 05.pdf" to 22 * MB,
            "Содержание.txt" to 4 * 1024L,
        ),
    )

    private fun torrent(infoHash: String, name: String, vararg files: Pair<String, Long>): TorrentInfo =
        TorrentInfo(
            infoHash = infoHash,
            name = name,
            files = files.mapIndexed { index, (path, size) -> TorrentFile(index, "$name/$path", size) },
        )
}
