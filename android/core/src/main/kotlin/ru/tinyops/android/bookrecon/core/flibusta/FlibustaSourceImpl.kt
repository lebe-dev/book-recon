package ru.tinyops.android.bookrecon.core.flibusta

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.mirror.InMemoryMirrorMemory
import ru.tinyops.android.bookrecon.core.mirror.MirrorFallback
import ru.tinyops.android.bookrecon.core.mirror.MirrorMemory
import ru.tinyops.android.bookrecon.core.port.DownloadedFile
import ru.tinyops.android.bookrecon.core.port.FlibustaSource
import ru.tinyops.android.bookrecon.core.port.HttpClient
import ru.tinyops.android.bookrecon.core.port.HttpResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [FlibustaSource] over the Flibusta HTML pages.
 *
 * @param baseUrl reads the mirror from settings; called on every operation, so a changed
 *   mirror applies at once.
 * @param fallbackUrls other mirrors to try, in order, when the one from [baseUrl] cannot be
 *   reached (network failure, timeout, server error). The mirror that answered last is tried
 *   first next time. Empty when the user pinned a mirror.
 * @param pageTimeout overall deadline of [search] and [details] on one mirror, including the
 *   book pages fetched for formats; read on every operation, so a changed timeout applies at
 *   once. [download] has none: a large file may take long, the caller limits it.
 * @param io dispatcher for blocking reads and file work.
 * @param mirrorMemory where the mirror that answered is kept, so it goes first after a restart.
 */
class FlibustaSourceImpl(
    private val http: HttpClient,
    private val baseUrl: suspend () -> String,
    private val fallbackUrls: suspend () -> List<String> = { emptyList() },
    private val pageTimeout: suspend () -> Duration = { DEFAULT_PAGE_TIMEOUT },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    mirrorMemory: MirrorMemory = InMemoryMirrorMemory(),
) : FlibustaSource {

    private val mirrors = MirrorFallback("Flibusta", mirrorMemory)

    override suspend fun search(query: String, limit: Int): List<BookResult> {
        if (limit <= 0 || query.isBlank()) return emptyList()

        return onMirrors { base -> withDeadline("search") { search(base, query, limit) } }
    }

    private suspend fun search(base: String, query: String, limit: Int): List<BookResult> {
        val searchUrl = "$base/booksearch?ask=" + URLEncoder.encode(query.trim(), Charsets.UTF_8)
        val page = parseSearchPage(fetchPage(searchUrl, "search page"), limit)

        val entries = if (page.books.isEmpty() && page.authors.isNotEmpty()) {
            booksByAuthors(base, page.authors, limit)
        } else {
            page.books
        }

        return withFormats(base, entries).map { entry ->
            BookResult(
                source = Source.FLIBUSTA,
                sourceId = entry.id,
                title = entry.title,
                author = entry.author,
                kind = MediaKind.BOOK,
                formats = entry.formats,
            )
        }
    }

    override suspend fun details(bookId: String): FlibustaBookDetails {
        requireBookId(bookId)
        return onMirrors { base ->
            withDeadline("book page") {
                val doc = fetchPage("$base/b/$bookId", "book page")
                parseBookPage(doc, bookId) ?: throw AppError.NotFound("flibusta book $bookId not found")
            }
        }
    }

    override suspend fun download(bookId: String, format: Format, dir: File): DownloadedFile {
        requireBookId(bookId)
        return onMirrors { base -> download(base, bookId, format, dir) }
    }

    private suspend fun download(base: String, bookId: String, format: Format, dir: File): DownloadedFile {
        val url = "$base/b/$bookId/${formatPath(format)}"

        val temp = createTempFile(dir, bookId)
        try {
            val disposition = http.get(url).use { response ->
                checkStatus(response, "download")
                // A format the book lacks is answered with an HTML page instead of a file.
                if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                    throw AppError.FormatNotAvailable(format)
                }
                copyBody(response.body, temp)
                response.header("Content-Disposition")
            }
            return finishDownload(temp, bookId, format, filenameFromDisposition(disposition), dir)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * Collects the books of the first matched authors. A failing author page is skipped:
     * partial results are better than none.
     */
    private suspend fun booksByAuthors(base: String, authors: List<AuthorEntry>, limit: Int): List<SearchEntry> {
        val collected = mutableListOf<SearchEntry>()
        for (author in authors.take(MAX_AUTHORS_TO_FOLLOW)) {
            if (collected.size >= limit) break
            val doc = try {
                fetchPage(base + author.path, "author page")
            } catch (_: AppError) {
                continue
            }
            collected += parseAuthorBooks(doc, author.name, limit - collected.size)
        }
        return collected
    }

    /**
     * Fills the formats of entries that came from the search page, which lists titles
     * without download links, from their book pages, at most [MAX_FORMAT_FETCHERS] at a
     * time. Books whose page offers no download are dropped.
     */
    private suspend fun withFormats(base: String, entries: List<SearchEntry>): List<SearchEntry> {
        val permits = Semaphore(MAX_FORMAT_FETCHERS)
        return coroutineScope {
            entries.map { entry ->
                async {
                    if (entry.formats.isNotEmpty()) return@async entry
                    permits.withPermit { entry.copy(formats = bookFormats(base, entry.id)) }
                }
            }.awaitAll()
        }.filter { it.formats.isNotEmpty() }
    }

    /**
     * The formats offered by a book page. A page that cannot be fetched falls back to the
     * formats most books have, so one broken page never hides a result.
     */
    private suspend fun bookFormats(base: String, bookId: String): List<Format> {
        val doc = try {
            fetchPage("$base/b/$bookId", "book page")
        } catch (_: AppError) {
            return DEFAULT_FORMATS
        }
        return collectBookFormats(doc)[bookId].orEmpty()
    }

    private suspend fun fetchPage(url: String, stage: String): Document = http.get(url).use { response ->
        checkStatus(response, stage)
        val html = withContext(io) { response.text() }
        Jsoup.parse(html, url)
    }

    /**
     * Finishes a download saved to [temp]: a zip with one file is unpacked, an epub (itself a
     * zip) or any other file is kept as is.
     */
    private suspend fun finishDownload(
        temp: File,
        bookId: String,
        format: Format,
        dispositionName: String?,
        dir: File,
    ): DownloadedFile {
        val zip = openZip(temp) ?: return DownloadedFile(temp, dispositionName ?: fallbackName(bookId, format.extension))

        val entries = zip.use { it.entries().asSequence().filterNot(ZipEntry::isDirectory).toList() }
        if (entries.isEmpty()) throw AppError.ParseError("flibusta sent an empty zip archive")

        if (entries.first().name == EPUB_MIMETYPE_ENTRY) {
            val name = dispositionName?.takeIf { it.endsWith(".epub", ignoreCase = true) }
            return DownloadedFile(temp, name ?: fallbackName(bookId, Format.EPUB.extension))
        }

        if (entries.size > 1) {
            return DownloadedFile(temp, dispositionName ?: fallbackName(bookId, "${format.extension}.zip"))
        }

        val entry = entries.single()
        val name = decodeZipEntryName(entry.name).substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: fallbackName(bookId, format.extension)
        val unpacked = createTempFile(dir, bookId)
        try {
            withContext(io) {
                ZipFile(temp, Charsets.ISO_8859_1).use { file ->
                    file.getInputStream(file.getEntry(entry.name)).use { input ->
                        unpacked.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        } catch (e: Throwable) {
            unpacked.delete()
            if (e is IOException) throw AppError.StorageFailed("failed to unpack the downloaded zip archive", e)
            throw e
        }
        temp.delete()
        return DownloadedFile(unpacked, name)
    }

    /** A name built from author and title, loaded from the book page; "book" if that fails. */
    private suspend fun fallbackName(bookId: String, extension: String): String {
        val details = try {
            details(bookId)
        } catch (_: AppError) {
            null
        }
        val base = listOfNotNull(details?.author, details?.title)
            .filter { it.isNotBlank() }
            .joinToString(". ")
            .ifEmpty { "book" }
        return "$base.$extension"
    }

    private suspend fun createTempFile(dir: File, bookId: String): File = withContext(io) {
        try {
            dir.mkdirs()
            File.createTempFile("flibusta-$bookId-", ".part", dir)
        } catch (e: IOException) {
            throw AppError.StorageFailed("failed to create a temporary file in $dir", e)
        }
    }

    /** Streams [body] into [target]; read failures are network errors, write ones storage. */
    private suspend fun copyBody(body: InputStream, target: File) = withContext(io) {
        val output = try {
            target.outputStream()
        } catch (e: IOException) {
            throw AppError.StorageFailed("failed to open $target for writing", e)
        }
        output.use {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                ensureActive()
                val read = try {
                    body.read(buffer)
                } catch (e: IOException) {
                    throw AppError.Network("download from flibusta was interrupted", e)
                }
                if (read < 0) break
                try {
                    output.write(buffer, 0, read)
                } catch (e: IOException) {
                    throw AppError.StorageFailed("failed to write the downloaded book", e)
                }
            }
        }
    }

    /** Opens [file] as a zip when it starts with the zip signature and is a valid archive. */
    private suspend fun openZip(file: File): ZipFile? = withContext(io) {
        val head = ByteArray(ZIP_MAGICS.first().size)
        val read = file.inputStream().use { it.read(head) }
        if (read != head.size || ZIP_MAGICS.none(head::contentEquals)) return@withContext null
        try {
            ZipFile(file, Charsets.ISO_8859_1)
        } catch (_: ZipException) {
            null
        }
    }

    private suspend fun <T> onMirrors(block: suspend (base: String) -> T): T {
        val all = (listOf(baseUrl()) + fallbackUrls()).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        return mirrors.run(all, block)
    }

    private suspend fun <T> withDeadline(stage: String, block: suspend () -> T): T {
        val deadline = pageTimeout()
        return try {
            withTimeout(deadline) { block() }
        } catch (e: TimeoutCancellationException) {
            throw AppError.Timeout("flibusta $stage did not finish within $deadline", e)
        }
    }

    private companion object {
        /** Book pages fetched at the same time when resolving formats of search results. */
        const val MAX_FORMAT_FETCHERS = 4

        val DEFAULT_PAGE_TIMEOUT = 30.seconds

        /** Assumed when a book page cannot be fetched: most books are converted to these. */
        val DEFAULT_FORMATS = listOf(Format.FB2, Format.EPUB, Format.MOBI)

        const val EPUB_MIMETYPE_ENTRY = "mimetype"

        /** Signatures of a zip with entries and of an empty zip. */
        val ZIP_MAGICS = listOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04), byteArrayOf(0x50, 0x4B, 0x05, 0x06))

        const val BUFFER_SIZE = 64 * 1024

        fun requireBookId(bookId: String) {
            if (bookId.isEmpty() || !bookId.all(Char::isDigit)) {
                throw AppError.NotFound("flibusta book id '$bookId' is not numeric")
            }
        }

        fun checkStatus(response: HttpResponse, stage: String) {
            if (response.code == 200) return
            if (response.code == 404) throw AppError.NotFound("flibusta $stage returned 404")
            throw AppError.ServiceDown("flibusta $stage returned status ${response.code}")
        }
    }
}
