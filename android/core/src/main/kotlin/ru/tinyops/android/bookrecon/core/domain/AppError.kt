package ru.tinyops.android.bookrecon.core.domain

/** Stable error code. Persisted with failed downloads, so existing names must not change. */
enum class ErrorCode {
    NETWORK,
    SERVICE_DOWN,
    TIMEOUT,
    NOT_FOUND,
    PARSE_ERROR,
    FORMAT_NOT_AVAILABLE,
    BOOK_UNAVAILABLE,
    VALIDATION,
    CLOUDFLARE_CHALLENGE,
    RUTRACKER_AUTH_REQUIRED,
    RUTRACKER_LOGIN_FAILED,
    RUTRACKER_CAPTCHA,
    TORRENT_TOO_LARGE,
    NO_SEEDERS,
    STALLED,
    ALREADY_DOWNLOADING,
    SELECTION_EMPTY,
    NO_MATCHING_FILES,
    STORAGE_NOT_GRANTED,
    ROOT_NOT_CONFIGURED,
    STORAGE_FAILED,
    INTERRUPTED,
    UNKNOWN,
}

/**
 * Every error that crosses a port. Implementations translate library exceptions (OkHttp,
 * libtorrent, I/O) into one of these; UI maps [code] to a Russian message.
 *
 * [message] is English and meant for logs. [detail] is the only payload besides the code:
 * together they survive a round-trip through the database via [restore]. Two errors are
 * equal when their code and detail are equal.
 *
 * Cancellation is not an error: coroutine cancellation propagates as usual.
 */
sealed class AppError(
    val code: ErrorCode,
    val detail: String? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** No connection, DNS failure, TLS failure or a reset connection. */
    class Network(message: String = "network is unreachable", cause: Throwable? = null) :
        AppError(ErrorCode.NETWORK, null, message, cause)

    /** The site answered with a 5xx or an unexpected page. */
    class ServiceDown(message: String = "service is down", cause: Throwable? = null) :
        AppError(ErrorCode.SERVICE_DOWN, null, message, cause)

    /** A deadline set by the caller or the HTTP client ran out. */
    class Timeout(message: String = "request timed out", cause: Throwable? = null) :
        AppError(ErrorCode.TIMEOUT, null, message, cause)

    /** The book, topic or download does not exist. */
    class NotFound(message: String = "not found", cause: Throwable? = null) :
        AppError(ErrorCode.NOT_FOUND, null, message, cause)

    /** The page markup did not match what the parser expects. */
    class ParseError(message: String = "unexpected page markup", cause: Throwable? = null) :
        AppError(ErrorCode.PARSE_ERROR, null, message, cause)

    /** The book exists but not in the requested format. */
    class FormatNotAvailable(val format: Format) :
        AppError(ErrorCode.FORMAT_NOT_AVAILABLE, format.name, "format ${format.extension} is not available")

    /** The book page offers no download at all (removed by the right holder, for example). */
    class BookUnavailable(message: String = "book has no downloads") :
        AppError(ErrorCode.BOOK_UNAVAILABLE, null, message)

    /** Invalid input, such as a too short search query. */
    class Validation(message: String) : AppError(ErrorCode.VALIDATION, message, message)

    /**
     * Cloudflare challenge that the invisible WebView could not pass. The UI opens [url] in a
     * visible WebView and repeats the action once the user passes the check.
     */
    class CloudflareChallenge(val url: String) :
        AppError(ErrorCode.CLOUDFLARE_CHALLENGE, url, "cloudflare challenge was not solved")

    /** RuTracker login or password is not set in settings. */
    class RuTrackerAuthRequired :
        AppError(ErrorCode.RUTRACKER_AUTH_REQUIRED, null, "rutracker credentials are not set")

    /** RuTracker rejected the login or password. */
    class RuTrackerLoginFailed :
        AppError(ErrorCode.RUTRACKER_LOGIN_FAILED, null, "rutracker rejected the credentials")

    /**
     * RuTracker asks for a captcha. The UI opens [loginUrl] in a WebView where the user logs in
     * by hand; the session cookie is picked up from the shared cookie store.
     */
    class RuTrackerCaptcha(val loginUrl: String) :
        AppError(ErrorCode.RUTRACKER_CAPTCHA, loginUrl, "rutracker asks for a captcha")

    /** The `.torrent` file is larger than the allowed limit. */
    class TorrentTooLarge(message: String = "torrent file is too large") :
        AppError(ErrorCode.TORRENT_TOO_LARGE, null, message)

    /** No peers and no data: nobody seeds the torrent. */
    class NoSeeders : AppError(ErrorCode.NO_SEEDERS, null, "torrent has no seeders")

    /** Peers are connected but no progress was made for too long. */
    class Stalled : AppError(ErrorCode.STALLED, null, "download stalled")

    /** The same torrent or book is already being downloaded. */
    class AlreadyDownloading : AppError(ErrorCode.ALREADY_DOWNLOADING, null, "already downloading")

    /** No files were selected for download. */
    class SelectionEmpty : AppError(ErrorCode.SELECTION_EMPTY, null, "no files selected")

    /** The torrent has no files of the expected kind (no audio files, no e-book files). */
    class NoMatchingFiles(val kind: MediaKind) :
        AppError(ErrorCode.NO_MATCHING_FILES, kind.name, "torrent has no ${kind.name.lowercase()} files")

    /** The app has no all-files access. */
    class StorageNotGranted : AppError(ErrorCode.STORAGE_NOT_GRANTED, null, "all files access is not granted")

    /** The root folder for [kind] is not chosen in settings or is gone. */
    class RootNotConfigured(val kind: MediaKind) :
        AppError(ErrorCode.ROOT_NOT_CONFIGURED, kind.name, "root folder for ${kind.name.lowercase()} is not configured")

    /** Reading, writing or moving files failed (disk full, read-only, I/O error). */
    class StorageFailed(message: String = "storage operation failed", cause: Throwable? = null) :
        AppError(ErrorCode.STORAGE_FAILED, null, message, cause)

    /** The process died while the download was running and it could not be resumed. */
    class Interrupted : AppError(ErrorCode.INTERRUPTED, null, "download was interrupted")

    /** Anything else; [message] tells what happened. */
    class Unknown(message: String = "unexpected error", cause: Throwable? = null) :
        AppError(ErrorCode.UNKNOWN, null, message, cause)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppError) return false
        return code == other.code && detail == other.detail
    }

    override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)

    override fun toString(): String = if (detail == null) "$code: $message" else "$code($detail): $message"

    companion object {
        /**
         * Rebuilds an error from its stored [code] and [detail]. A detail that does not fit the
         * code (unknown enum name, missing URL) falls back to [Unknown].
         */
        fun restore(code: ErrorCode, detail: String?): AppError = when (code) {
            ErrorCode.NETWORK -> Network()
            ErrorCode.SERVICE_DOWN -> ServiceDown()
            ErrorCode.TIMEOUT -> Timeout()
            ErrorCode.NOT_FOUND -> NotFound()
            ErrorCode.PARSE_ERROR -> ParseError()
            ErrorCode.FORMAT_NOT_AVAILABLE ->
                enumOrNull<Format>(detail)?.let(::FormatNotAvailable) ?: Unknown("format not available")
            ErrorCode.BOOK_UNAVAILABLE -> BookUnavailable()
            ErrorCode.VALIDATION -> Validation(detail ?: "validation failed")
            ErrorCode.CLOUDFLARE_CHALLENGE ->
                detail?.let(::CloudflareChallenge) ?: Unknown("cloudflare challenge")
            ErrorCode.RUTRACKER_AUTH_REQUIRED -> RuTrackerAuthRequired()
            ErrorCode.RUTRACKER_LOGIN_FAILED -> RuTrackerLoginFailed()
            ErrorCode.RUTRACKER_CAPTCHA -> detail?.let(::RuTrackerCaptcha) ?: Unknown("rutracker captcha")
            ErrorCode.TORRENT_TOO_LARGE -> TorrentTooLarge()
            ErrorCode.NO_SEEDERS -> NoSeeders()
            ErrorCode.STALLED -> Stalled()
            ErrorCode.ALREADY_DOWNLOADING -> AlreadyDownloading()
            ErrorCode.SELECTION_EMPTY -> SelectionEmpty()
            ErrorCode.NO_MATCHING_FILES ->
                enumOrNull<MediaKind>(detail)?.let(::NoMatchingFiles) ?: Unknown("no matching files")
            ErrorCode.STORAGE_NOT_GRANTED -> StorageNotGranted()
            ErrorCode.ROOT_NOT_CONFIGURED ->
                enumOrNull<MediaKind>(detail)?.let(::RootNotConfigured) ?: Unknown("root not configured")
            ErrorCode.STORAGE_FAILED -> StorageFailed()
            ErrorCode.INTERRUPTED -> Interrupted()
            ErrorCode.UNKNOWN -> Unknown()
        }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
            enumValues<E>().firstOrNull { it.name == name }
    }
}
