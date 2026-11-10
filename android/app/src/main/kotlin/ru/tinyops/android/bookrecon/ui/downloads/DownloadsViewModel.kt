package ru.tinyops.android.bookrecon.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.port.DownloadQueue

/** What the user asked the queue to do with one download. */
enum class DownloadAction { CANCEL, RETRY, STOP_SEEDING, DISMISS }

/** One-off snackbar: [action] failed with [error]. The screen acknowledges it with [DownloadsViewModel.onMessageShown]. */
data class DownloadsMessage(val action: DownloadAction, val error: AppError)

/**
 * State of the downloads screen.
 *
 * @property loading the queue has not answered yet.
 * @property loadError reading the queue failed; the lists are empty then.
 * @property active running downloads.
 * @property queued downloads waiting for a slot, by queue position.
 * @property seeding finished torrents that are still shared.
 * @property failed failed downloads, newest first.
 * @property cancelled downloads the user cancelled, newest first; they can be started again.
 * @property cancelCandidate the download whose cancellation waits for confirmation.
 * @property busy IDs of downloads with an action in flight; their buttons are disabled.
 */
data class DownloadsUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val active: List<Download> = emptyList(),
    val queued: List<Download> = emptyList(),
    val seeding: List<Download> = emptyList(),
    val failed: List<Download> = emptyList(),
    val cancelled: List<Download> = emptyList(),
    val cancelCandidate: Download? = null,
    val busy: Set<String> = emptySet(),
    val message: DownloadsMessage? = null,
) {
    val isEmpty: Boolean
        get() = active.isEmpty() && queued.isEmpty() && seeding.isEmpty() && failed.isEmpty() && cancelled.isEmpty()
}

/** Shows the download queue with live progress and runs cancel, retry, stop seeding and dismiss. */
class DownloadsViewModel(private val queue: DownloadQueue) : ViewModel() {
    private val mutableState = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = mutableState.asStateFlow()

    /** Kept apart from the state so the dialog follows the download and closes once it is gone. */
    private var cancelId: String? = null

    init {
        viewModelScope.launch {
            queue.observe()
                .catch { e ->
                    if (e !is AppError) throw e
                    mutableState.update { it.copy(loading = false, loadError = e) }
                }
                .collect(::onQueue)
        }
    }

    /** Asks for confirmation before cancelling a queued or running download. */
    fun onCancelRequest(id: String) {
        val download = cancellable(mutableState.value, id) ?: return
        cancelId = download.id
        mutableState.update { it.copy(cancelCandidate = download) }
    }

    fun onCancelDismiss() {
        cancelId = null
        mutableState.update { it.copy(cancelCandidate = null) }
    }

    fun onCancelConfirm() {
        val download = mutableState.value.cancelCandidate ?: return
        onCancelDismiss()
        run(download.id, DownloadAction.CANCEL) { queue.cancel(it) }
    }

    fun onRetry(id: String) = run(id, DownloadAction.RETRY) { queue.retry(it) }

    fun onStopSeeding(id: String) = run(id, DownloadAction.STOP_SEEDING) { queue.stopSeeding(it) }

    fun onDismiss(id: String) = run(id, DownloadAction.DISMISS) { queue.dismiss(it) }

    fun onMessageShown() {
        mutableState.update { it.copy(message = null) }
    }

    private fun onQueue(downloads: List<Download>) {
        mutableState.update { current ->
            val next = current.copy(
                loading = false,
                loadError = null,
                active = downloads.filter { it.status == DownloadStatus.DOWNLOADING },
                queued = downloads.filter { it.status == DownloadStatus.QUEUED }
                    .sortedWith(compareBy<Download> { it.queuePosition ?: Int.MAX_VALUE }.thenBy { it.createdAt }),
                seeding = downloads.filter { it.status == DownloadStatus.SEEDING },
                failed = downloads.filter { it.status == DownloadStatus.FAILED }.sortedByDescending { it.createdAt },
                cancelled = downloads.filter { it.status == DownloadStatus.CANCELLED }.sortedByDescending { it.createdAt },
            )
            next.copy(cancelCandidate = cancelId?.let { cancellable(next, it) })
        }
        if (mutableState.value.cancelCandidate == null) cancelId = null
    }

    /** Runs one queue action unless another one for the same download is still in flight. */
    private fun run(id: String, action: DownloadAction, block: suspend (String) -> Unit) {
        if (id in mutableState.value.busy) return
        mutableState.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            val failure = try {
                block(id)
                null
            } catch (e: AppError) {
                DownloadsMessage(action, e)
            }
            mutableState.update { state ->
                state.copy(busy = state.busy - id, message = failure ?: state.message)
            }
        }
    }

    private fun cancellable(state: DownloadsUiState, id: String): Download? =
        state.active.firstOrNull { it.id == id } ?: state.queued.firstOrNull { it.id == id }
}
