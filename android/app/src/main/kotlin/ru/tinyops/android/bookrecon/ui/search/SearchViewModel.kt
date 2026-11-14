package ru.tinyops.android.bookrecon.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.port.SearchHistory
import ru.tinyops.android.bookrecon.core.usecase.search.SearchState
import ru.tinyops.android.bookrecon.core.usecase.search.SearchUseCase
import ru.tinyops.android.bookrecon.core.usecase.search.Verification
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

/** A web check the screen has to open: [url] in a visible WebView. */
data class VerificationRequest(val url: String, val purpose: VerificationPurpose)

/**
 * State of the search screen.
 *
 * @property query text in the search field, not yet submitted.
 * @property submittedQuery the last query sent to the sources; switching the tab repeats it.
 * @property search the latest snapshot of the running or finished search, null before the first one.
 * @property queryTooShort the user submitted a query the sources would not accept.
 * @property chosenKinds kinds whose download folder is chosen; a kind outside it cannot be
 *   selected or searched, since nothing could be downloaded.
 * @property history the last submitted queries, newest first.
 * @property pendingVerification a web check to open now; the screen acknowledges it with
 *   [SearchViewModel.onVerificationOpened].
 */
data class SearchUiState(
    val query: String = "",
    val kind: MediaKind = MediaKind.BOOK,
    val submittedQuery: String? = null,
    val search: SearchState? = null,
    val queryTooShort: Boolean = false,
    val chosenKinds: Set<MediaKind> = MediaKind.entries.toSet(),
    val pendingVerification: VerificationRequest? = null,
    val history: List<String> = emptyList(),
)

/**
 * Runs searches through [searchUseCase]. The query, the tab and the last submitted query live
 * in [savedState], so they survive rotation and process death; a restored submitted query is
 * searched again. Every accepted query goes to [history].
 *
 * Only kinds in [chosenKinds] (those with a chosen download folder) are searched. A kind
 * without a folder can still be opened, so the screen explains what is missing; the tab moves
 * to another kind when its folder is gone, and without any folder nothing is searched.
 */
class SearchViewModel(
    private val searchUseCase: SearchUseCase,
    private val history: SearchHistory,
    private val savedState: SavedStateHandle,
    private val chosenKinds: Flow<Set<MediaKind>> = flowOf(MediaKind.entries.toSet()),
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SearchUiState(
            query = savedState[KEY_QUERY] ?: "",
            kind = savedState.get<String>(KEY_KIND)?.let { name -> MediaKind.entries.firstOrNull { it.name == name } }
                ?: MediaKind.BOOK,
            submittedQuery = savedState[KEY_SUBMITTED],
        ),
    )
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private var searchJob: Job? = null

    /** Set once the current search has asked to open a web check, so it is opened only once. */
    private var verificationRequested = false

    init {
        mutableState.value.submittedQuery?.let { start(it, mutableState.value.kind) }
        viewModelScope.launch { chosenKinds.collect(::onChosenKinds) }
        viewModelScope.launch {
            history.queries.collect { queries -> mutableState.update { it.copy(history = queries) } }
        }
    }

    fun onQueryChange(query: String) {
        savedState[KEY_QUERY] = query
        mutableState.update { it.copy(query = query, queryTooShort = false) }
    }

    fun onKindChange(kind: MediaKind) {
        if (kind == mutableState.value.kind) return
        savedState[KEY_KIND] = kind.name
        mutableState.update { it.copy(kind = kind) }
        val submitted = mutableState.value.submittedQuery ?: return
        start(submitted, kind)
    }

    fun onSearch() {
        val query = mutableState.value.query
        if (mutableState.value.kind !in mutableState.value.chosenKinds) return
        if (!SearchUseCase.accepts(query)) {
            mutableState.update { it.copy(queryTooShort = true) }
            return
        }
        savedState[KEY_SUBMITTED] = query
        mutableState.update { it.copy(submittedQuery = query, queryTooShort = false) }
        start(query, mutableState.value.kind)
        viewModelScope.launch { history.add(query) }
    }

    /** Puts a history entry into the field and searches it. */
    fun onHistorySelect(query: String) {
        onQueryChange(query)
        onSearch()
    }

    fun onHistoryRemove(query: String) {
        viewModelScope.launch { history.remove(query) }
    }

    /** Repeats the last submitted search past the cache, for example after a source failed. */
    fun onRetry() {
        val submitted = mutableState.value.submittedQuery ?: return
        start(submitted, mutableState.value.kind, refresh = true)
    }

    /** The screen has navigated to [SearchUiState.pendingVerification]. */
    fun onVerificationOpened() {
        mutableState.update { it.copy(pendingVerification = null) }
    }

    /** Result of the web check: a passed check repeats the search, a failed one keeps the results. */
    fun onVerificationResult(passed: Boolean) {
        if (!passed) return
        onRetry()
    }

    /** The set of kinds with a download folder changed: keep the tab on one of them. */
    private fun onChosenKinds(kinds: Set<MediaKind>) {
        mutableState.update { it.copy(chosenKinds = kinds) }
        val current = mutableState.value.kind
        val submitted = mutableState.value.submittedQuery
        if (current in kinds) {
            // A folder chosen meanwhile brings back a search that was held back.
            if (submitted != null && mutableState.value.search == null) start(submitted, current)
            return
        }
        val replacement = MediaKind.entries.firstOrNull { it in kinds }
        if (replacement == null) {
            searchJob?.cancel()
            mutableState.update { it.copy(search = null) }
            return
        }
        savedState[KEY_KIND] = replacement.name
        mutableState.update { it.copy(kind = replacement) }
        if (submitted != null) start(submitted, replacement)
    }

    private fun start(query: String, kind: MediaKind, refresh: Boolean = false) {
        if (kind !in mutableState.value.chosenKinds) {
            searchJob?.cancel()
            mutableState.update { it.copy(search = null) }
            return
        }
        searchJob?.cancel()
        verificationRequested = false
        mutableState.update { it.copy(search = null, pendingVerification = null) }
        searchJob = viewModelScope.launch {
            searchUseCase.search(query, kind, refresh).collect(::onSnapshot)
        }
    }

    private fun onSnapshot(snapshot: SearchState) {
        val verification = snapshot.verification
        val request = if (verification != null && !verificationRequested) verification.toRequest() else null
        if (request != null) verificationRequested = true
        mutableState.update { current ->
            current.copy(search = snapshot, pendingVerification = request ?: current.pendingVerification)
        }
    }

    private companion object {
        const val KEY_QUERY = "search_query"
        const val KEY_KIND = "search_kind"
        const val KEY_SUBMITTED = "search_submitted"
    }
}

/** Which web check a verification needs. */
fun Verification.toRequest(): VerificationRequest = VerificationRequest(url, error.verificationPurpose())

internal fun AppError.verificationPurpose(): VerificationPurpose = when (this) {
    is AppError.RuTrackerCaptcha -> VerificationPurpose.RUTRACKER_LOGIN
    else -> VerificationPurpose.CLOUDFLARE
}
