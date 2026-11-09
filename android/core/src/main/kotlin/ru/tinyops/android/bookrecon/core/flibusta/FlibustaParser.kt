package ru.tinyops.android.bookrecon.core.flibusta

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format

/** Most authors matched by a search whose books are followed. */
internal const val MAX_AUTHORS_TO_FOLLOW = 3

/** Extracts the numeric book ID from a URL like `/b/435845`. */
private val bookIdRe = Regex("""/b/(\d+)""")

/** A plain book link like `/b/435845`, without an action suffix. */
private val bookPathRe = Regex("""^/b/(\d+)/?$""")

/** A book action link like `/b/435845/fb2`, `/b/435845/read` or `/b/435845/download`. */
private val bookActionRe = Regex("""^/b/(\d+)/([a-z0-9]+)$""")

/** The format named by an original-file link, whose text reads "(скачать pdf)". */
private val downloadTextRe = Regex("""скачать\s+([a-z0-9]+)""", RegexOption.IGNORE_CASE)

/** The script on a book page that names the book, absent on a "not found" page. */
private val bookIdScriptRe = Regex("""var\s+bookId\s*=\s*(\d+)""")

/** The format suffix of a book page heading: "Пушкин (fb2)". */
private val headingFormatRe = Regex("""\s*\([a-z0-9.]+\)$""")

/** Formats served under their own path; every other format is the original upload. */
private val pathFormats = mapOf(
    "fb2" to Format.FB2,
    "epub" to Format.EPUB,
    "mobi" to Format.MOBI,
)

/** Formats recognised in the text of a `/download` link. */
private val downloadFormats = mapOf(
    "pdf" to Format.PDF,
    "djvu" to Format.DJVU,
)

/** One book found on a search or author page. [formats] is empty when the page shows none. */
internal data class SearchEntry(
    val id: String,
    val title: String,
    val author: String,
    val formats: List<Format> = emptyList(),
)

/** One author matched by a search; [path] is like `/a/151853`. */
internal data class AuthorEntry(val name: String, val path: String)

/**
 * Both blocks a search page may contain. [authors] is filled only when no book matched,
 * since the book block is what the caller wants whenever present.
 */
internal data class SearchPage(val books: List<SearchEntry>, val authors: List<AuthorEntry>)

/**
 * Parses a search page: the "Найденные книги" block, up to [limit] books, or, when it is
 * missing, up to [MAX_AUTHORS_TO_FOLLOW] authors of the "Найденные писатели" block.
 */
internal fun parseSearchPage(doc: Document, limit: Int): SearchPage {
    val books = headingListItems(doc, "Найденные книги")
        .mapNotNull(::parseBookItem)
        .take(limit.coerceAtLeast(0))
    if (books.isNotEmpty()) return SearchPage(books, emptyList())

    val authors = headingListItems(doc, "Найденные писатели")
        .mapNotNull(::parseAuthorItem)
        .take(MAX_AUTHORS_TO_FOLLOW)
    return SearchPage(emptyList(), authors)
}

/**
 * Extracts up to [limit] books of one author from their page. The page also links books
 * of unrelated blocks (comments, new arrivals), so only books backed by download links
 * count; those links also give the formats.
 */
internal fun parseAuthorBooks(doc: Document, author: String, limit: Int): List<SearchEntry> {
    if (limit <= 0) return emptyList()

    val formats = collectBookFormats(doc)
    val titles = LinkedHashMap<String, String>()
    for (link in doc.select("a[href]")) {
        val id = bookPathRe.find(link.attr("href"))?.groupValues?.get(1) ?: continue
        if (id in titles) continue
        val title = cleanTitle(link.text())
        if (title.isNotEmpty()) titles[id] = title
    }

    return titles.entries
        .filter { (id, _) -> !formats[id].isNullOrEmpty() }
        .take(limit)
        .map { (id, title) -> SearchEntry(id, title, author, formats.getValue(id)) }
}

/**
 * Parses a book page. Returns null when the page is not a book page: Flibusta answers an
 * unknown ID with a title search instead of a 404. Formats are empty for a book that offers
 * no download.
 */
internal fun parseBookPage(doc: Document, bookId: String): FlibustaBookDetails? {
    val pageId = doc.select("script").firstNotNullOfOrNull { bookIdScriptRe.find(it.data()) }
    if (pageId?.groupValues?.get(1) != bookId) return null

    val heading = doc.selectFirst("h1.title") ?: return null
    val title = cleanTitle(heading.text().replace(headingFormatRe, ""))

    return FlibustaBookDetails(
        id = bookId,
        title = title,
        author = headingAuthors(heading).joinToString(", "),
        formats = collectBookFormats(doc)[bookId].orEmpty(),
    )
}

/** Maps every book ID on the page to the formats its download links offer, in page order. */
internal fun collectBookFormats(doc: Document): Map<String, List<Format>> {
    val formats = LinkedHashMap<String, MutableList<Format>>()
    for (link in doc.select("a[href]")) {
        val (id, format) = formatFromLink(link) ?: continue
        val list = formats.getOrPut(id) { mutableListOf() }
        if (format !in list) list += format
    }
    return formats
}

/** The URL path segment that serves [format]: its own path or `download` for originals. */
internal fun formatPath(format: Format): String =
    if (format.extension in pathFormats) format.extension else "download"

/** The book ID of a link like `/b/435845` or `https://flibusta.is/b/435845`, or null. */
internal fun extractBookId(url: String): String? = bookIdRe.find(url)?.groupValues?.get(1)

/**
 * The format a book link downloads, if any. Converted formats live under their own path,
 * `/b/435845/fb2`; the original upload is `/b/435845/download` and only the link text
 * names its format.
 */
private fun formatFromLink(link: Element): Pair<String, Format>? {
    val match = bookActionRe.find(link.attr("href")) ?: return null
    val (id, action) = match.destructured

    if (action != "download") return pathFormats[action]?.let { id to it }

    val name = downloadTextRe.find(link.text())?.groupValues?.get(1) ?: return null
    return downloadFormats[name.lowercase()]?.let { id to it }
}

/** The `<li>` items of the `<ul>` that follows the `<h3>` containing [heading]. */
private fun headingListItems(doc: Document, heading: String): List<Element> {
    val h3 = doc.select("h3").firstOrNull { heading in it.text() } ?: return emptyList()
    val list = h3.nextElementSiblings().firstOrNull { it.tagName() == "ul" } ?: return emptyList()
    return list.children().filter { it.tagName() == "li" }
}

/** A search page book item: the first book link is the title, `/a/` links are authors. */
private fun parseBookItem(item: Element): SearchEntry? {
    var id: String? = null
    var title = ""
    val authors = mutableListOf<String>()

    for (link in item.children().filter { it.tagName() == "a" }) {
        val href = link.attr("href")
        val linkId = extractBookId(href)
        if (linkId != null && id == null) {
            id = linkId
            title = cleanTitle(link.text())
            continue
        }
        if (href.startsWith("/a/")) authors += link.text()
    }

    if (id == null || title.isEmpty()) return null
    return SearchEntry(id, title, authors.joinToString(", "))
}

/** A search page author item: the first `/a/` link with a name. */
private fun parseAuthorItem(item: Element): AuthorEntry? = item.children()
    .filter { it.tagName() == "a" && it.attr("href").startsWith("/a/") }
    .firstNotNullOfOrNull { link ->
        val name = normalizeSpaces(link.text())
        if (name.isEmpty()) null else AuthorEntry(name, link.attr("href"))
    }

/**
 * The author links right after the book page heading. They are separated by `&nbsp;`; any
 * other text, such as a translator note, or any other element ends the list.
 */
private fun headingAuthors(heading: Element): List<String> {
    val authors = mutableListOf<String>()
    var node = heading.nextSibling()
    while (node != null) {
        when {
            node is TextNode -> if (node.text().replace(' ', ' ').isNotBlank()) break
            node is Element && node.tagName() == "script" -> Unit
            node is Element && node.tagName() == "a" && node.attr("href").startsWith("/a/") ->
                authors += normalizeSpaces(node.text())
            else -> break
        }
        node = node.nextSibling()
    }
    return authors.filter { it.isNotEmpty() }
}

/** Removes an alternative title suffix like " [= War and Peace]". */
private fun cleanTitle(title: String): String {
    val index = title.indexOf(" [=")
    return if (index < 0) title.trim() else title.substring(0, index).trim()
}

private fun normalizeSpaces(text: String): String = text.trim().split(Regex("\\s+")).joinToString(" ")
