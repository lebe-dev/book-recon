package ru.tinyops.android.bookrecon.core.usecase.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FlibustaSource
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Searches all sources of a media kind in parallel: e-books on Flibusta and RuTracker,
 * audiobooks on RuTracker. Every source has its own deadline, and a failed source never hides
 * the results of the others.
 *
 * @param sourceTimeout deadline of one source; RuTracker may spend tens of seconds on a
 *   Cloudflare check, so it stays at a minute.
 */
class SearchUseCase(
    private val flibusta: FlibustaSource,
    private val ruTracker: RuTrackerClient,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val sourceTimeout: Duration = DEFAULT_SOURCE_TIMEOUT,
) {
    /**
     * Starts a search when the flow is collected. Emits a snapshot with every source loading,
     * then a new snapshot each time a source answers, and completes after the last one.
     * A query shorter than [MIN_QUERY_LENGTH] after trimming gives an empty flow and reaches
     * no source.
     */
    fun search(query: String, kind: MediaKind): Flow<SearchState> {
        val normalized = normalize(query) ?: return emptyFlow()
        val sources = sourcesFor(kind)

        return channelFlow {
            val lock = Mutex()
            var state = SearchState(normalized, kind, sources.associateWith { SourceState.Loading })
            send(state)

            for (source in sources) {
                launch {
                    val result = load(source, normalized, kind)
                    lock.withLock {
                        state = state.with(source, result)
                        send(state)
                    }
                }
            }
        }
    }

    private suspend fun load(source: Source, query: String, kind: MediaKind): SourceState {
        val results = try {
            withTimeoutOrNull(sourceTimeout) { fetch(source, query, kind) }
                ?: return SourceState.Failed(AppError.Timeout("$source search took longer than $sourceTimeout"))
        } catch (e: AppError) {
            return failure(e)
        } catch (e: CancellationException) {
            // A deadline inside the source cancels only that call; real cancellation is rethrown.
            currentCoroutineContext().ensureActive()
            return SourceState.Failed(AppError.Timeout("$source search timed out", e))
        } catch (e: Exception) {
            return SourceState.Failed(AppError.Unknown("$source search failed: ${e.message}", e))
        }
        return SourceState.Loaded(results.map { markDownloaded(it) })
    }

    private suspend fun fetch(source: Source, query: String, kind: MediaKind): List<BookResult> = when (source) {
        Source.FLIBUSTA -> flibusta.search(query, SEARCH_LIMIT)
        Source.RUTRACKER -> {
            // The client checks the password itself; a missing login skips it without a request.
            if (settings.settings.first().rutrackerLogin.isBlank()) throw AppError.RuTrackerAuthRequired()
            ruTracker.search(query, kind, SEARCH_LIMIT).map { it.toResult() }
        }
    }.take(SEARCH_LIMIT)

    private suspend fun markDownloaded(result: BookResult): BookResult {
        val completed = try {
            downloads.findCompleted(result.source, result.sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The mark is a hint; a failed lookup must not hide the result.
            null
        }
        val onDisk = completed != null && !completed.fileMissing
        return result.copy(alreadyDownloaded = onDisk)
    }

    private fun failure(error: AppError): SourceState = when (error) {
        is AppError.CloudflareChallenge -> SourceState.VerificationRequired(error, error.url)
        is AppError.RuTrackerCaptcha -> SourceState.VerificationRequired(error, error.loginUrl)
        else -> SourceState.Failed(error)
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 200

        /** Results taken from one source. */
        const val SEARCH_LIMIT = 20

        val DEFAULT_SOURCE_TIMEOUT: Duration = 60.seconds

        /** True when [query] is long enough to be searched. */
        fun accepts(query: String): Boolean = normalize(query) != null

        /** Sources searched for [kind], in display order. */
        fun sourcesFor(kind: MediaKind): List<Source> = when (kind) {
            MediaKind.BOOK -> listOf(Source.FLIBUSTA, Source.RUTRACKER)
            MediaKind.AUDIO -> listOf(Source.RUTRACKER)
        }

        private fun normalize(query: String): String? {
            val trimmed = query.trim()
            if (trimmed.length < MIN_QUERY_LENGTH) return null
            return trimmed.take(MAX_QUERY_LENGTH).trimEnd()
        }

        private fun TorrentTopic.toResult() = BookResult(
            source = Source.RUTRACKER,
            sourceId = topicId,
            title = title,
            author = "",
            kind = kind,
            size = size,
            seeders = seeders,
            peers = peers,
        )
    }
}
