package ru.tinyops.android.bookrecon.core.flibusta

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.Format
import java.io.File

internal fun fixture(name: String): String =
    File(System.getProperty("testdata.dir"), "flibusta/$name").readText()

private fun doc(name: String): Document = Jsoup.parse(fixture(name), "https://flibusta.is/")

class FlibustaParserTest {

    @Test
    fun `search page with books and authors returns books only`() {
        val page = parseSearchPage(doc("search-books-authors.html"), 20)

        assertEquals(6, page.books.size)
        assertTrue(page.authors.isEmpty())
        val first = page.books.first()
        assertEquals("692119", first.id)
        assertEquals("Тынянов. Писатель и ученый", first.title)
        assertTrue(first.author.startsWith("Сергей Михайлович Эйзенштейн, Борис Михайлович Эйхенбаум"))
        assertTrue(page.books.all { it.formats.isEmpty() })
        assertEquals("57146", page.books.last().id)
    }

    @Test
    fun `search page respects the limit`() {
        val page = parseSearchPage(doc("search-books-authors.html"), 2)

        assertEquals(listOf("692119", "443953"), page.books.map { it.id })
    }

    @Test
    fun `alternative title suffix is stripped and authors are joined`() {
        val books = parseSearchPage(doc("search-alt-title.html"), 10).books

        assertEquals(listOf("Пушкин", "Война и мир", "Идиот"), books.map { it.title })
        assertEquals("Юрий Тынянов", books[0].author)
        assertEquals("Фёдор Достоевский, Редактор", books[2].author)
    }

    @Test
    fun `search page with authors only returns authors`() {
        val page = parseSearchPage(doc("search-authors-only.html"), 20)

        assertTrue(page.books.isEmpty())
        assertEquals(listOf(AuthorEntry("Шинзен Янг", "/a/151853")), page.authors)
    }

    @Test
    fun `empty search page has neither books nor authors`() {
        val page = parseSearchPage(doc("search-empty.html"), 20)

        assertTrue(page.books.isEmpty())
        assertTrue(page.authors.isEmpty())
    }

    @Test
    fun `author page keeps own books with download links`() {
        val books = parseAuthorBooks(doc("author-tynyanov.html"), "Юрий Тынянов", 100)

        assertEquals(46, books.size)
        assertEquals(SearchEntry("220961", "Воспоминания о Корнее Чуковском", "Юрий Тынянов", listOf(Format.FB2, Format.EPUB, Format.MOBI)), books[0])
        assertEquals(listOf(Format.PDF), books[1].formats)
        assertEquals("Пушкин", books.first { it.id == "435845" }.title)
        // Books linked from the comments block carry no download links.
        assertTrue(books.none { it.id == "894502" })
    }

    @Test
    fun `author page respects the limit`() {
        val books = parseAuthorBooks(doc("author-tynyanov.html"), "Юрий Тынянов", 3)

        assertEquals(listOf("220961", "507392", "728080"), books.map { it.id })
        assertTrue(parseAuthorBooks(doc("author-tynyanov.html"), "Юрий Тынянов", 0).isEmpty())
    }

    @Test
    fun `author page with the original pdf only`() {
        val books = parseAuthorBooks(doc("author-kleppmann.html"), "Мартин Клеппман", 10)

        assertEquals(listOf("617927"), books.map { it.id })
        assertEquals(listOf(Format.PDF), books[0].formats)
    }

    @Test
    fun `formats come from link paths and download link text`() {
        val books = parseAuthorBooks(doc("author-mixed.html"), "Автор", 10)

        assertEquals(
            listOf(
                "617927" to listOf(Format.PDF),
                "112458" to listOf(Format.FB2, Format.EPUB, Format.MOBI),
                "888888" to listOf(Format.DJVU),
            ),
            books.map { it.id to it.formats },
        )
    }

    @Test
    fun `collectBookFormats ignores read links and unknown originals`() {
        val formats = collectBookFormats(doc("author-mixed.html"))

        assertNull(formats["777777"])
        assertNull(formats["999999"])
        assertNull(formats["884328"])
    }

    @Test
    fun `book page with converted formats`() {
        val details = parseBookPage(doc("book-converted.html"), "435845")!!

        assertEquals("Пушкин", details.title)
        assertEquals("Юрий Николаевич Тынянов", details.author)
        assertEquals(listOf(Format.FB2, Format.EPUB, Format.MOBI), details.formats)
    }

    @Test
    fun `book page with the original pdf only`() {
        val details = parseBookPage(doc("book-pdf-only.html"), "617927")!!

        assertEquals("Высоконагруженные приложения. Программирование, масштабирование, поддержка", details.title)
        assertEquals("Мартин Клеппман", details.author)
        assertEquals(listOf(Format.PDF), details.formats)
    }

    @Test
    fun `book page with several authors`() {
        val details = parseBookPage(doc("book-multi-author.html"), "507392")!!

        assertEquals("Юрий Тынянов. Писатель и ученый", details.title)
        assertEquals(
            "Вениамин Александрович Каверин, Илья Григорьевич Эренбург, Корней Иванович Чуковский, " +
                "Юрий Николаевич Тынянов, Виктор Борисович Шкловский",
            details.author,
        )
        assertEquals(listOf(Format.PDF), details.formats)
    }

    @Test
    fun `book page without downloads has no formats`() {
        val details = parseBookPage(doc("book-no-downloads.html"), "435845")!!

        assertEquals("Пушкин", details.title)
        assertTrue(details.formats.isEmpty())
    }

    @Test
    fun `unknown book id gives a title search page, not a book`() {
        assertNull(parseBookPage(doc("book-missing.html"), "99999999"))
        assertNull(parseBookPage(doc("book-converted.html"), "1"))
    }

    @Test
    fun `format paths`() {
        assertEquals("fb2", formatPath(Format.FB2))
        assertEquals("epub", formatPath(Format.EPUB))
        assertEquals("mobi", formatPath(Format.MOBI))
        assertEquals("download", formatPath(Format.PDF))
        assertEquals("download", formatPath(Format.DJVU))
    }

    @Test
    fun `book id extraction`() {
        assertEquals("435845", extractBookId("/b/435845"))
        assertEquals("435845", extractBookId("https://flibusta.is/b/435845"))
        assertNull(extractBookId("/a/29081"))
        assertNull(extractBookId(""))
    }
}
