package ru.tinyops.android.bookrecon.ui.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadRequest
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.port.DownloadQueue
import ru.tinyops.android.bookrecon.core.port.DownloadRepository
import ru.tinyops.android.bookrecon.core.port.FlibustaSource
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import ru.tinyops.android.bookrecon.ui.search.VerificationRequest

/** Loading state of the book page. */
sealed interface BookDetailsState {
    data object Loading : BookDetailsState

    data class Loaded(val book: FlibustaBookDetails) : BookDetailsState

    data class Failed(val error: AppError) : BookDetailsState
}

/** What the snackbar action of a [BookMessage] does. */
enum class BookMessageAction { DOWNLOADS, SETTINGS, RETRY }

/** One-off message shown in a snackbar; the screen acknowledges it with [FlibustaBookViewModel.onMessageShown]. */
sealed interface BookMessage {
    /** The book went to the download queue. */
    data object Added : BookMessage

    /** The queue refused the book. */
    data class EnqueueFailed(val error: AppError) : BookMessage {
        val action: BookMessageAction
            get() = when (error.code) {
                ErrorCode.STORAGE_NOT_GRANTED, ErrorCode.ROOT_NOT_CONFIGURED -> BookMessageAction.SETTINGS
                ErrorCode.ALREADY_DOWNLOADING -> BookMessageAction.DOWNLOADS
                else -> BookMessageAction.RETRY
            }
    }
}

/**
 * State of the Flibusta book screen.
 *
 * @property selectedFormat the format to download; null while loading or when the book offers
 *   no downloads.
 * @property downloaded the newest completed download of this book whose file is still on disk.
 * @property enqueuing a download request is being sent to the queue.
 * @property pendingVerification a Cloudflare check to open now; the screen acknowledges it with
 *   [FlibustaBookViewModel.onVerificationOpened].
 */
data class FlibustaBookUiState(
    val details: BookDetailsState = BookDetailsState.Loading,
    val selectedFormat: Format? = null,
    val downloaded: Download? = null,
    val enqueuing: Boolean = false,
    val message: BookMessage? = null,
    val pendingVerification: VerificationRequest? = null,
) {
    val canDownload: Boolean
        get() = details is BookDetailsState.Loaded && selectedFormat != null && !enqueuing
}

/**
 * Loads a Flibusta book page and puts the book into the download queue. The chosen format
 * lives in [savedState], so it survives rotation and process death.
 */
class FlibustaBookViewModel(
    private val bookId: String,
    private val flibusta: FlibustaSource,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val queue: DownloadQueue,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FlibustaBookUiState())
    val state: StateFlow<FlibustaBookUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onFormatSelected(format: Format) {
        val book = (mutableState.value.details as? BookDetailsState.Loaded)?.book ?: return
        if (format !in book.formats) return
        savedState[KEY_FORMAT] = format.name
        mutableState.update { it.copy(selectedFormat = format) }
    }

    fun onDownload() {
        val current = mutableState.value
        if (!current.canDownload) return
        val book = (current.details as BookDetailsState.Loaded).book
        val format = current.selectedFormat ?: return
        mutableState.update { it.copy(enqueuing = true, message = null) }
        viewModelScope.launch {
            val message = try {
                queue.enqueue(book.toRequest(format))
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

    /** Loads the book page again, for example after a network error. */
    fun onRetry() {
        load()
    }

    /** The screen has navigated to [FlibustaBookUiState.pendingVerification]. */
    fun onVerificationOpened() {
        mutableState.update { it.copy(pendingVerification = null) }
    }

    /** Result of the web check: a passed check loads the page again, a failed one keeps the error. */
    fun onVerificationResult(passed: Boolean) {
        if (!passed) return
        load()
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(details = BookDetailsState.Loading, pendingVerification = null) }
        loadJob = viewModelScope.launch {
            val book = try {
                flibusta.details(bookId)
            } catch (e: AppError) {
                onLoadFailed(e)
                return@launch
            }
            val format = initialFormat(book)
            val downloaded = findDownloaded()
            mutableState.update {
                it.copy(details = BookDetailsState.Loaded(book), selectedFormat = format, downloaded = downloaded)
            }
        }
    }

    private fun onLoadFailed(error: AppError) {
        val verification = (error as? AppError.CloudflareChallenge)
            ?.let { VerificationRequest(it.url, VerificationPurpose.CLOUDFLARE) }
        mutableState.update { it.copy(details = BookDetailsState.Failed(error), pendingVerification = verification) }
    }

    /** The format chosen before process death, else the preferred one, else the first offered. */
    private suspend fun initialFormat(book: FlibustaBookDetails): Format? {
        val saved = savedState.get<String>(KEY_FORMAT)?.let { name -> Format.entries.firstOrNull { it.name == name } }
        if (saved != null && saved in book.formats) return saved
        val preferred = settings.settings.first().preferredFormat
        if (preferred in book.formats) return preferred
        return book.formats.firstOrNull()
    }

    /** A failed lookup only hides the mark; it never blocks the download. */
    private suspend fun findDownloaded(): Download? = try {
        downloads.findCompleted(Source.FLIBUSTA, bookId)?.takeUnless { it.fileMissing }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEY_FORMAT = "book_format"
    }
}

/** The queue request for [format] of this book. */
fun FlibustaBookDetails.toRequest(format: Format): DownloadRequest.FlibustaBook =
    DownloadRequest.FlibustaBook(bookId = id, title = title, author = author, format = format)
