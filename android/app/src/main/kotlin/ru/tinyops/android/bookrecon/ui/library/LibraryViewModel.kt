package ru.tinyops.android.bookrecon.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FileStorage
import java.io.File

/** Type filter of the library: all, e-books only or audiobooks only. */
enum class LibraryFilter(val kind: MediaKind?) {
    ALL(null),
    BOOKS(MediaKind.BOOK),
    AUDIO(MediaKind.AUDIO),
}

/** The screen should view [path] as [mimeType] in another app, then call [LibraryViewModel.onOpenHandled]. */
data class OpenRequest(val path: String, val mimeType: String)

/** One-off snackbar; the screen acknowledges it with [LibraryViewModel.onMessageShown]. */
sealed interface LibraryMessage {
    /** The file to open is gone from the disk. */
    data object FileMissing : LibraryMessage

    /** The audiobook folder has no audio file to play. */
    data object NoAudioFiles : LibraryMessage

    /** No installed app views this type of file. */
    data object NoApp : LibraryMessage

    /** Deleting the files failed; the record stays. */
    data class DeleteFailed(val error: AppError) : LibraryMessage

    /** Removing the record failed. */
    data class ForgetFailed(val error: AppError) : LibraryMessage
}

/**
 * State of the library screen.
 *
 * @property loading the library has not answered yet.
 * @property loadError reading the library failed; [items] are empty then.
 * @property items completed downloads matching [query] and [filter], newest first.
 * @property deleteCandidate the entry whose deletion waits for confirmation.
 * @property busy IDs of entries with an action in flight; their buttons are disabled.
 * @property open a file the screen should open now.
 */
data class LibraryUiState(
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val items: List<Download> = emptyList(),
    val deleteCandidate: Download? = null,
    val busy: Set<String> = emptySet(),
    val open: OpenRequest? = null,
    val message: LibraryMessage? = null,
) {
    /** Nothing to show for the current query and filter. */
    val isEmpty: Boolean
        get() = !loading && loadError == null && items.isEmpty()

    /** Nothing downloaded at all, as opposed to nothing matching the search. */
    val isLibraryEmpty: Boolean
        get() = isEmpty && query.isBlank() && filter == LibraryFilter.ALL
}

/**
 * Shows completed downloads with search and a type filter; opens, deletes and forgets them.
 * Missing files are re-checked on every [onRefresh], which the screen calls when it comes
 * back to the foreground.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: DownloadRepository,
    private val storage: FileStorage,
    private val savedState: SavedStateHandle,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LibraryUiState(
            query = savedState[KEY_QUERY] ?: "",
            filter = savedState.get<String>(KEY_FILTER)
                ?.let { name -> LibraryFilter.entries.firstOrNull { it.name == name } }
                ?: LibraryFilter.ALL,
        ),
    )
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    private val query = MutableStateFlow(mutableState.value.query)
    private val filter = MutableStateFlow(mutableState.value.filter)
    private val refreshes = MutableStateFlow(0)

    /** Kept apart from the state so the dialog follows the entry and closes once it is gone. */
    private var deleteId: String? = null

    init {
        viewModelScope.launch {
            combine(query, filter, refreshes) { q, f, _ -> q to f }
                .flatMapLatest { (q, f) ->
                    repository.observeLibrary(q.trim(), f.kind)
                        .catch { e ->
                            if (e !is AppError) throw e
                            mutableState.update { it.copy(loading = false, loadError = e, items = emptyList()) }
                        }
                }
                .collect(::onLibrary)
        }
    }

    fun onQueryChange(value: String) {
        savedState[KEY_QUERY] = value
        mutableState.update { it.copy(query = value) }
        query.value = value
    }

    fun onFilterChange(value: LibraryFilter) {
        savedState[KEY_FILTER] = value.name
        mutableState.update { it.copy(filter = value) }
        filter.value = value
    }

    /** Reads the library again so files deleted outside the app get marked missing. */
    fun onRefresh() {
        refreshes.update { it + 1 }
    }

    fun onOpen(id: String) {
        val download = find(id) ?: return
        viewModelScope.launch {
            val target = withContext(io) { resolveOpenTarget(download) }
            when (target) {
                is OpenTarget.Open -> mutableState.update { it.copy(open = OpenRequest(target.path, target.mimeType)) }
                OpenTarget.Missing -> {
                    mutableState.update { it.copy(message = LibraryMessage.FileMissing) }
                    onRefresh()
                }
                OpenTarget.NoAudioFiles -> mutableState.update { it.copy(message = LibraryMessage.NoAudioFiles) }
            }
        }
    }

    fun onOpenHandled() {
        mutableState.update { it.copy(open = null) }
    }

    /** The screen found no app for the requested file. */
    fun onOpenFailed() {
        mutableState.update { it.copy(open = null, message = LibraryMessage.NoApp) }
    }

    /** Asks for confirmation before deleting the files and the record. */
    fun onDeleteRequest(id: String) {
        val download = find(id) ?: return
        deleteId = download.id
        mutableState.update { it.copy(deleteCandidate = download) }
    }

    fun onDeleteDismiss() {
        deleteId = null
        mutableState.update { it.copy(deleteCandidate = null) }
    }

    fun onDeleteConfirm() {
        val download = mutableState.value.deleteCandidate ?: return
        onDeleteDismiss()
        run(download.id, LibraryMessage::DeleteFailed) {
            deleteFiles(download)
            repository.delete(download.id)
        }
    }

    /** Removes the entry from the history and leaves its files on disk. */
    fun onForget(id: String) = run(id, LibraryMessage::ForgetFailed) { repository.delete(it) }

    fun onMessageShown() {
        mutableState.update { it.copy(message = null) }
    }

    private fun onLibrary(items: List<Download>) {
        mutableState.update { current ->
            current.copy(
                loading = false,
                loadError = null,
                items = items,
                deleteCandidate = deleteId?.let { id -> items.firstOrNull { it.id == id } },
            )
        }
        if (mutableState.value.deleteCandidate == null) deleteId = null
    }

    /**
     * Deletes every path of [download]. A path that is still there afterwards fails the
     * deletion, so the record is never dropped while its files stay behind unnoticed.
     */
    private suspend fun deleteFiles(download: Download) {
        if (!storage.hasAllFilesAccess()) throw AppError.StorageNotGranted()
        val root = try {
            storage.root(download.kind).absoluteFile
        } catch (_: AppError) {
            null
        }
        for (path in download.paths) {
            val file = File(path).absoluteFile
            if (file == root || file.parentFile == null) throw AppError.StorageFailed("refusing to delete $path")
            storage.delete(file)
            if (withContext(io) { storage.exists(file) }) throw AppError.StorageFailed("cannot delete ${file.name}")
        }
    }

    /** Runs one action unless another one for the same entry is still in flight. */
    private fun run(id: String, failure: (AppError) -> LibraryMessage, block: suspend (String) -> Unit) {
        if (id in mutableState.value.busy) return
        mutableState.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            val message = try {
                block(id)
                null
            } catch (e: AppError) {
                failure(e)
            }
            mutableState.update { state ->
                state.copy(busy = state.busy - id, message = message ?: state.message)
            }
        }
    }

    private fun find(id: String): Download? = mutableState.value.items.firstOrNull { it.id == id }

    companion object {
        const val KEY_QUERY = "library_query"
        const val KEY_FILTER = "library_filter"
    }
}
