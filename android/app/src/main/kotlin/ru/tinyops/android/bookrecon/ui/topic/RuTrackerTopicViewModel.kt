package ru.tinyops.android.bookrecon.ui.topic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.naming.cleanTopicTitle
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.core.port.TorrentEngine
import ru.tinyops.android.bookrecon.core.selection.FileSelection
import ru.tinyops.android.bookrecon.core.selection.TreeNode
import ru.tinyops.android.bookrecon.ui.book.BookMessage
import ru.tinyops.android.bookrecon.ui.search.VerificationRequest
import ru.tinyops.android.bookrecon.ui.search.verificationPurpose

/** Loading state of the topic's torrent. */
sealed interface TopicLoadState {
    data object Loading : TopicLoadState

    data class Loaded(val info: TorrentInfo) : TopicLoadState

    /** Fetching or parsing failed, or the torrent has no files of the topic's kind (NoMatchingFiles). */
    data class Failed(val error: AppError) : TopicLoadState
}

/**
 * State of the RuTracker topic screen.
 *
 * @property title topic title as found by the search.
 * @property selection offered files and the user's choice; null until the torrent is loaded.
 * @property collapsed paths of folders whose content is hidden.
 * @property tree rows of the file tree for a lazy list, built from [selection] and [collapsed].
 * @property folderName audiobook folder name; for e-books it is not shown.
 * @property enqueuing a download request is being sent to the queue.
 * @property rootMissing the folder for [kind] is not chosen, so nothing can be downloaded.
 * @property message one-off snackbar message; acknowledged with [RuTrackerTopicViewModel.onMessageShown].
 * @property pendingVerification a web check to open now; acknowledged with
 *   [RuTrackerTopicViewModel.onVerificationOpened].
 */
data class RuTrackerTopicUiState(
    val kind: MediaKind,
    val title: String,
    val load: TopicLoadState = TopicLoadState.Loading,
    val selection: FileSelection? = null,
    val collapsed: Set<String> = emptySet(),
    val tree: List<TreeNode> = emptyList(),
    val folderName: String = "",
    val enqueuing: Boolean = false,
    val rootMissing: Boolean = false,
    val message: BookMessage? = null,
    val pendingVerification: VerificationRequest? = null,
) {
    /** Size of the whole torrent, other kinds of files included; 0 until loaded. */
    val torrentSize: Long
        get() = (load as? TopicLoadState.Loaded)?.info?.totalSize ?: 0

    /** An audiobook needs a folder to go to. */
    val folderNameMissing: Boolean
        get() = kind == MediaKind.AUDIO && folderName.isBlank()

    val canDownload: Boolean
        get() = load is TopicLoadState.Loaded &&
            (selection?.selectedCount ?: 0) > 0 &&
            !folderNameMissing &&
            !enqueuing &&
            !rootMissing
}

/**
 * Fetches a topic's `.torrent`, offers its files of [kind] as a tree and puts the selected
 * ones into the download queue. The selection, collapsed folders and the folder name live in
 * [savedState]; the torrent itself is fetched again after process death.
 */
class RuTrackerTopicViewModel(
    private val topicId: String,
    private val kind: MediaKind,
    private val title: String,
    private val ruTracker: RuTrackerClient,
    private val torrents: TorrentEngine,
    private val settings: SettingsRepository,
    private val queue: DownloadQueue,
    private val savedState: SavedStateHandle,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        RuTrackerTopicUiState(
            kind = kind,
            title = title,
            collapsed = savedState.get<ArrayList<String>>(KEY_COLLAPSED)?.toSet().orEmpty(),
            folderName = savedState.get<String>(KEY_FOLDER) ?: cleanTopicTitle(title),
        ),
    )
    val state: StateFlow<RuTrackerTopicUiState> = mutableState.asStateFlow()

    private var torrentBytes: ByteArray? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settings.settings.collect { stored ->
                mutableState.update { it.copy(rootMissing = !stored.hasRoot(kind)) }
            }
        }
        load()
    }

    fun onToggleFile(index: Int) = changeSelection { it.toggleFile(index) }

    fun onToggleFolder(path: String) = changeSelection { it.toggleFolder(path) }

    fun onSelectAll() = changeSelection { it.selectAll() }

    fun onSelectNone() = changeSelection { it.selectNone() }

    /** Collapses an expanded folder or expands a collapsed one. */
    fun onToggleExpanded(path: String) {
        val current = mutableState.value
        val collapsed = if (path in current.collapsed) current.collapsed - path else current.collapsed + path
        savedState[KEY_COLLAPSED] = ArrayList(collapsed)
        mutableState.update { it.copy(collapsed = collapsed, tree = it.selection?.tree(collapsed).orEmpty()) }
    }

    fun onFolderNameChange(name: String) {
        savedState[KEY_FOLDER] = name
        mutableState.update { it.copy(folderName = name) }
    }

    fun onDownload() {
        val current = mutableState.value
        if (!current.canDownload) return
        val bytes = torrentBytes ?: return
        val selection = current.selection ?: return
        val request = DownloadRequest.TorrentSelection(
            topicId = topicId,
            kind = kind,
            title = title,
            torrentBytes = bytes,
            selectedIndexes = selection.selectedIndexes,
            targetName = if (kind == MediaKind.AUDIO) current.folderName.trim() else cleanTopicTitle(title),
        )
        mutableState.update { it.copy(enqueuing = true, message = null) }
        viewModelScope.launch {
            val message = try {
                queue.enqueue(request)
                BookMessage.Added
            } catch (e: AppError) {
                BookMessage.EnqueueFailed(e)
            }
            mutableState.update { it.copy(enqueuing = false, message = message) }
        }
    }

    fun onMessageShown() {
        mutableState.update { it.copy(message = null) }
    }

    /** Fetches the torrent again, for example after a network error. */
    fun onRetry() {
        load()
    }

    /** The screen has navigated to [RuTrackerTopicUiState.pendingVerification]. */
    fun onVerificationOpened() {
        mutableState.update { it.copy(pendingVerification = null) }
    }

    /** Result of the web check: a passed check fetches the torrent again, a failed one keeps the error. */
    fun onVerificationResult(passed: Boolean) {
        if (!passed) return
        load()
    }

    private fun changeSelection(change: (FileSelection) -> FileSelection) {
        val selection = mutableState.value.selection ?: return
        val next = change(selection)
        savedState[KEY_SELECTED] = next.selectedIndexes.toIntArray()
        mutableState.update { it.copy(selection = next, tree = next.tree(it.collapsed)) }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(load = TopicLoadState.Loading, pendingVerification = null) }
        loadJob = viewModelScope.launch {
            val loaded = try {
                fetch()
            } catch (e: AppError) {
                onLoadFailed(e)
                return@launch
            }
            torrentBytes = loaded.bytes
            mutableState.update {
                it.copy(
                    load = TopicLoadState.Loaded(loaded.info),
                    selection = loaded.selection,
                    tree = loaded.selection.tree(it.collapsed),
                )
            }
        }
    }

    private class LoadedTorrent(val bytes: ByteArray, val info: TorrentInfo, val selection: FileSelection)

    private suspend fun fetch(): LoadedTorrent {
        val bytes = ruTracker.fetchTorrent(topicId)
        val preferred = settings.settings.first().preferredFormat
        return withContext(parseDispatcher) {
            val info = torrents.parse(bytes)
            val offered = when (kind) {
                MediaKind.AUDIO -> FileSelection.forAudio(info)
                MediaKind.BOOK -> FileSelection.forBooks(info, preferred)
            }
            if (offered.isEmpty) throw AppError.NoMatchingFiles(kind)
            val saved = savedState.get<IntArray>(KEY_SELECTED)
            val selection = if (saved == null) offered else offered.withSelected(saved.toSet())
            LoadedTorrent(bytes, info, selection)
        }
    }

    private fun onLoadFailed(error: AppError) {
        val verification = when (error) {
            is AppError.CloudflareChallenge -> VerificationRequest(error.url, error.verificationPurpose())
            is AppError.RuTrackerCaptcha -> VerificationRequest(error.loginUrl, error.verificationPurpose())
            else -> null
        }
        mutableState.update { it.copy(load = TopicLoadState.Failed(error), pendingVerification = verification) }
    }

    private companion object {
        const val KEY_SELECTED = "topic_selected"
        const val KEY_COLLAPSED = "topic_collapsed"
        const val KEY_FOLDER = "topic_folder"
    }
}
