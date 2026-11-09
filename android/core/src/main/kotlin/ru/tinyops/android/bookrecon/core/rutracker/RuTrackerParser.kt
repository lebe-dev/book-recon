package ru.tinyops.android.bookrecon.core.rutracker

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import java.nio.charset.Charset

/**
 * Parsers for RuTracker pages. All pages are served in windows-1251.
 *
 * Search results (`tracker.php`) are rows `#tor-tbl tr[data-topic_id]`: the title is the
 * `a.tLink` link, the size in bytes is `data-ts_text` of `td.tor-size`, seeders are
 * `b.seedmed` and leechers `td.leechmed`. Topics nobody seeds are listed too, with
 * `b.seedmed` showing 0, and are dropped, as are rows without a seeders cell. An empty search
 * keeps the table with a "Не найдено" row.
 *
 * The login page has `form#login-form-full` with `login_username` and `login_password`; a
 * captcha adds `cap_sid` and `cap_code_<hash>` fields to it. A logged-in page shows
 * `#logged-in-username` in the header, an anonymous page the login link instead.
 */
object RuTrackerParser {
    val CHARSET: Charset = Charset.forName("windows-1251")

    fun parse(html: String, url: String): Document = Jsoup.parse(html, url)

    /**
     * Topics of a search results page with at least one seeder, in page order. Throws
     * [AppError.ParseError] when the page has no results table.
     */
    fun parseSearchResults(doc: Document, kind: MediaKind): List<TorrentTopic> {
        val table = doc.selectFirst("#tor-tbl") ?: throw AppError.ParseError("rutracker search page has no results table")
        return table.select("tr[data-topic_id]").mapNotNull { parseRow(it, kind) }
    }

    /** True for the login page, including the one an expired session is redirected to. */
    fun isLoginPage(doc: Document): Boolean = doc.selectFirst("form#login-form-full") != null

    /** True when the login form asks for a captcha. */
    fun hasCaptcha(doc: Document): Boolean =
        doc.selectFirst("input[name=cap_sid], input[name^=cap_code_]") != null

    /** True for a page rendered for a logged-in user. */
    fun isLoggedIn(doc: Document): Boolean = doc.selectFirst("#logged-in-username") != null

    private fun parseRow(row: Element, kind: MediaKind): TorrentTopic? {
        val topicId = row.attr("data-topic_id").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val title = row.selectFirst("a.tLink")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val seeders = row.selectFirst("b.seedmed")?.text()?.trim()?.toIntOrNull() ?: 0
        if (seeders <= 0) return null

        return TorrentTopic(
            topicId = topicId,
            title = title,
            size = row.selectFirst("td.tor-size")?.attr("data-ts_text")?.toLongOrNull() ?: 0,
            seeders = seeders,
            peers = row.selectFirst("td.leechmed")?.text()?.trim()?.toIntOrNull() ?: 0,
            kind = kind,
        )
    }
}
