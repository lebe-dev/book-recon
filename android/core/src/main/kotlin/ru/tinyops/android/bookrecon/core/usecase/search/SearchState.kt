package ru.tinyops.android.bookrecon.core.usecase.search

import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import java.time.Instant

/** Progress of one source within a search. */
sealed interface SourceState {
    /** The source has not answered yet. */
    data object Loading : SourceState

    /**
     * The source answered; [results] may be empty. [cachedAt] is set when the results come
     * from the search cache and tells when the source was actually asked.
     */
    data class Loaded(val results: List<BookResult>, val cachedAt: Instant? = null) : SourceState

    /** The source failed; the other sources are not affected. */
    data class Failed(val error: AppError) : SourceState

    /**
     * The source needs the user: a Cloudflare check or a RuTracker captcha. The UI opens [url]
     * in a visible WebView and repeats the search once the check is passed.
     */
    data class VerificationRequired(val error: AppError, val url: String) : SourceState
}

/** A check the user has to pass in a WebView before [source] can be searched. */
data class Verification(val source: Source, val error: AppError, val url: String)

/**
 * Snapshot of a running search. A new snapshot is emitted every time a source answers; the
 * last one has [isComplete] set.
 *
 * @property query the query as sent to the sources: trimmed and truncated.
 * @property sources state of every searched source, in display order.
 */
data class SearchState(
    val query: String,
    val kind: MediaKind,
    val sources: Map<Source, SourceState>,
) {
    /** Results of all sources that answered, in source order. */
    val results: List<BookResult>
        get() = sources.keys.flatMap(::resultsOf)

    /** Errors of failed sources; sources waiting for verification are not listed here. */
    val errors: Map<Source, AppError>
        get() = sources.mapNotNull { (source, state) -> (state as? SourceState.Failed)?.let { source to it.error } }.toMap()

    /** The first source that waits for a WebView check, or null. */
    val verification: Verification?
        get() = sources.firstNotNullOfOrNull { (source, state) ->
            (state as? SourceState.VerificationRequired)?.let { Verification(source, it.error, it.url) }
        }

    /** When the oldest cached answer was fetched, or null when every answer is fresh. */
    val cachedAt: Instant?
        get() = sources.values.mapNotNull { (it as? SourceState.Loaded)?.cachedAt }.minOrNull()

    /** True while at least one source has not answered. */
    val isLoading: Boolean
        get() = sources.values.any { it == SourceState.Loading }

    /** True when every source has answered, failed or asked for verification. */
    val isComplete: Boolean
        get() = !isLoading

    fun sourceState(source: Source): SourceState? = sources[source]

    fun resultsOf(source: Source): List<BookResult> =
        (sources[source] as? SourceState.Loaded)?.results.orEmpty()

    internal fun with(source: Source, state: SourceState): SearchState =
        copy(sources = sources + (source to state))
}
