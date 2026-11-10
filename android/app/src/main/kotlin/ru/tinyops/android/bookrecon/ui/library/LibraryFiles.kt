package ru.tinyops.android.bookrecon.ui.library

import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.naming.isAudioFile
import ru.tinyops.android.bookrecon.core.naming.splitExtension
import ru.tinyops.android.bookrecon.core.port.FileStorage
import ru.tinyops.android.bookrecon.core.selection.NaturalOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What "Open" leads to for one library entry. */
sealed interface OpenTarget {
    /** View the document at the content [uri] as [mimeType]. */
    data class Open(val uri: String, val mimeType: String) : OpenTarget

    /** None of the download's paths exists any more. */
    data object Missing : OpenTarget

    /** The audiobook folder holds no audio file. */
    data object NoAudioFiles : OpenTarget
}

/** Any type: lets every app that views arbitrary files offer itself. */
const val ANY_MIME_TYPE = "*/*"

private val mimeTypes: Map<String, String> = mapOf(
    "epub" to "application/epub+zip",
    "fb2" to "application/x-fictionbook+xml",
    "fb2.zip" to "application/x-zip-compressed-fb2",
    "mobi" to "application/x-mobipocket-ebook",
    "pdf" to "application/pdf",
    "djvu" to "image/vnd.djvu",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "m4b" to "audio/mp4",
    "aac" to "audio/aac",
    "ogg" to "audio/ogg",
    "opus" to "audio/opus",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
    "wma" to "audio/x-ms-wma",
)

/** MIME type for a file name by its extension; [ANY_MIME_TYPE] when the extension is unknown. */
fun mimeTypeOf(fileName: String): String = mimeTypes[splitExtension(fileName).second] ?: ANY_MIME_TYPE

/**
 * Formats shown next to a library entry: the chosen format of a Flibusta book, the distinct
 * e-book extensions of a torrent's files, or the audio extensions of an audiobook. Null when
 * nothing is known.
 */
fun displayFormat(download: Download): String? {
    download.format?.let { return it.name }
    val names = when (download.kind) {
        MediaKind.BOOK -> download.paths.ifEmpty { download.files.map { it.path } }
        MediaKind.AUDIO -> download.files.map { it.path }.filter(::isAudioFile)
    }
    val formats = names
        .map { splitExtension(it.substringAfterLast('/')).second }
        .filter { it.isNotEmpty() }
        .map { it.removeSuffix(".zip").uppercase() }
        .distinct()
    return formats.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** The day of [instant] in [zone], "dd.MM.yyyy". */
fun libraryDate(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(dateFormat)

/**
 * Finds what to open for [download]. A book opens its first existing file. An audiobook
 * opens its first audio file in natural order, looking into subfolders such as CD1/CD2:
 * players cannot be handed a folder reliably, but they continue with the next tracks
 * themselves. Reads the roots through [storage], so call it off the main thread.
 */
fun resolveOpenTarget(download: Download, storage: FileStorage): OpenTarget {
    val kind = download.kind
    val existing = download.paths.firstOrNull { storage.exists(kind, it) } ?: return OpenTarget.Missing
    if (kind == MediaKind.BOOK || !storage.isFolder(kind, existing)) {
        val uri = storage.uriOf(kind, existing) ?: return OpenTarget.Missing
        return OpenTarget.Open(uri, mimeTypeOf(existing.substringAfterLast('/')))
    }
    // The files of one folder share the folder's path, so natural order of whole paths is
    // natural order inside the folder.
    val first = storage.listFiles(kind, existing)
        .filter(::isAudioFile)
        .minWithOrNull(compareBy(NaturalOrder) { it })
        ?: return OpenTarget.NoAudioFiles
    val uri = storage.uriOf(kind, first) ?: return OpenTarget.Missing
    return OpenTarget.Open(uri, mimeTypeOf(first.substringAfterLast('/')))
}
