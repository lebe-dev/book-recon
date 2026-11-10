package ru.tinyops.android.bookrecon.core.rutracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic

class RuTrackerParserTest {

    private fun doc(name: String) = RuTrackerParser.parse(Fixtures.text(name), "https://rutracker.org/forum/tracker.php")

    @Test
    fun `audio search results are parsed in page order`() {
        val topics = RuTrackerParser.parseSearchResults(doc("search-audio.html"), MediaKind.AUDIO)

        // The page has 26 rows; topic 6202692 has no seeders and is dropped.
        assertEquals(25, topics.size)
        assertFalse(topics.any { it.topicId == "6202692" })
        assertEquals(
            TorrentTopic(
                topicId = "1224115",
                title = "Лукьяненко Сергей - Ночной дозор, Дневной дозор, Сумеречный дозор, Последний дозор, " +
                    "Лик черной Пальмиры [Старчиков, Грачева, Хорлин, Карпов, Петров, Ковалев, Хабенский, 2007, 160-192 kbps]",
                size = 4_788_349_282,
                seeders = 77,
                peers = 14,
                kind = MediaKind.AUDIO,
            ),
            topics.first(),
        )
        assertEquals(
            TorrentTopic("2042520", "Сергей Лукьяненко - \"Мелкий дозор\" рассказ [death by sexy, 2009, 128 kbps]", 32_293_420, 4, 0, MediaKind.AUDIO),
            topics.last(),
        )
    }

    @Test
    fun `book search results keep the kind they were searched for`() {
        val topics = RuTrackerParser.parseSearchResults(doc("search-books.html"), MediaKind.BOOK)

        assertEquals(50, topics.size)
        assertTrue(topics.all { it.kind == MediaKind.BOOK })
        assertEquals(TorrentTopic("4995626", "Лукьяненко Сергей - Сборник произведений [1992-2025, FB2, RUS]", 411_782_101, 355, 56, MediaKind.BOOK), topics.first())
        assertEquals(topics.size, topics.map { it.topicId }.toSet().size)
    }

    @Test
    fun `empty search has no results`() {
        assertEquals(emptyList<TorrentTopic>(), RuTrackerParser.parseSearchResults(doc("search-empty.html"), MediaKind.AUDIO))
    }

    @Test
    fun `topics without seeders are dropped`() {
        val zeroSeeders = Fixtures.text("search-audio.html")
            .replace("<b class=\"seedmed\">77</b>", "<b class=\"seedmed\">0</b>")
        val noSeederCell = Fixtures.text("search-audio.html")
            .replace("<b class=\"seedmed\">64</b>", "<span>-</span>")

        val withoutFirst = RuTrackerParser.parseSearchResults(RuTrackerParser.parse(zeroSeeders, "https://rutracker.org/"), MediaKind.AUDIO)
        val withoutSecond = RuTrackerParser.parseSearchResults(RuTrackerParser.parse(noSeederCell, "https://rutracker.org/"), MediaKind.AUDIO)

        assertEquals(24, withoutFirst.size)
        assertFalse(withoutFirst.any { it.topicId == "1224115" })
        assertEquals(24, withoutSecond.size)
        assertFalse(withoutSecond.any { it.topicId == "4911904" })
    }

    @Test
    fun `page without the results table is a parse error`() {
        assertThrows<AppError.ParseError> {
            RuTrackerParser.parseSearchResults(doc("dl-not-found.html"), MediaKind.AUDIO)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["login-form.html", "login-required.html", "login-failed.html", "login-captcha.html"])
    fun `login pages are recognized`(name: String) {
        val page = doc(name)
        assertTrue(RuTrackerParser.isLoginPage(page))
        assertFalse(RuTrackerParser.isLoggedIn(page))
    }

    @ParameterizedTest
    @ValueSource(strings = ["search-audio.html", "search-books.html", "search-empty.html"])
    fun `search pages are logged in pages`(name: String) {
        val page = doc(name)
        assertFalse(RuTrackerParser.isLoginPage(page))
        assertFalse(RuTrackerParser.hasCaptcha(page))
        assertTrue(RuTrackerParser.isLoggedIn(page))
    }

    @Test
    fun `captcha is detected only on the captcha page`() {
        assertTrue(RuTrackerParser.hasCaptcha(doc("login-captcha.html")))
        assertFalse(RuTrackerParser.hasCaptcha(doc("login-failed.html")))
        assertFalse(RuTrackerParser.hasCaptcha(doc("login-form.html")))
    }
}
