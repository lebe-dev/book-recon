package ru.tinyops.android.bookrecon.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.FlibustaBookDetails
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.download.downloadMessage
import ru.tinyops.android.bookrecon.ui.common.RootMissingNotice
import ru.tinyops.android.bookrecon.ui.common.Tag
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import ru.tinyops.android.bookrecon.ui.search.label
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme
import java.time.Instant

@Composable
fun FlibustaBookScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    verificationResults: Flow<Boolean>,
) {
    val context = LocalContext.current
    val factory = remember(context, bookId) {
        val app = context.appContainer
        viewModelFactory {
            initializer {
                FlibustaBookViewModel(
                    bookId = bookId,
                    flibusta = app.flibusta.flibustaSource,
                    settings = app.settings.settingsRepository,
                    downloads = app.database.downloadRepository,
                    queue = app.download.downloadQueue,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
    val viewModel: FlibustaBookViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(verificationResults) {
        verificationResults.collect(viewModel::onVerificationResult)
    }

    val pending = state.pendingVerification
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        viewModel.onVerificationOpened()
        onOpenVerification(pending.url, pending.purpose)
    }

    MessageSnackbar(
        message = state.message,
        hostState = snackbarHostState,
        onShown = viewModel::onMessageShown,
        onAction = { action ->
            when (action) {
                BookMessageAction.DOWNLOADS -> onOpenDownloads()
                BookMessageAction.SETTINGS -> onOpenSettings()
                BookMessageAction.RETRY -> viewModel.onDownload()
            }
        },
    )

    FlibustaBookContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onFormatSelected = viewModel::onFormatSelected,
        onDownload = viewModel::onDownload,
        onRetry = viewModel::onRetry,
        onOpenVerification = { url -> onOpenVerification(url, VerificationPurpose.CLOUDFLARE) },
        onOpenSettings = onOpenSettings,
    )
}

/** Shows [message] once in [hostState] and runs its action when the user taps it. */
@Composable
private fun MessageSnackbar(
    message: BookMessage?,
    hostState: SnackbarHostState,
    onShown: () -> Unit,
    onAction: (BookMessageAction) -> Unit,
) {
    if (message == null) return
    val text = when (message) {
        BookMessage.Added -> stringResource(R.string.book_added)
        is BookMessage.EnqueueFailed ->
            stringResource(R.string.book_enqueue_failed, stringResource(message.error.downloadMessage()))
    }
    val action = when (message) {
        BookMessage.Added -> BookMessageAction.DOWNLOADS
        is BookMessage.EnqueueFailed -> message.action
    }
    val actionLabel = stringResource(
        when (action) {
            BookMessageAction.DOWNLOADS -> R.string.book_action_open
            BookMessageAction.SETTINGS -> R.string.book_action_settings
            BookMessageAction.RETRY -> R.string.book_action_retry
        },
    )
    val currentOnShown by rememberUpdatedState(onShown)
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(message) {
        val result = hostState.showSnackbar(
            message = text,
            actionLabel = actionLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        currentOnShown()
        if (result == SnackbarResult.ActionPerformed) currentOnAction(action)
    }
}

/** Stateless body of the book screen, also used by previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlibustaBookContent(
    state: FlibustaBookUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onFormatSelected: (Format) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onOpenVerification: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.book_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val bodyModifier = Modifier.fillMaxSize().padding(padding)
        when (val details = state.details) {
            BookDetailsState.Loading -> LoadingBody(bodyModifier)
            is BookDetailsState.Failed -> FailedBody(details.error, onRetry, onOpenVerification, bodyModifier)
            is BookDetailsState.Loaded -> BookBody(details.book, state, onFormatSelected, onDownload, onOpenSettings, bodyModifier)
        }
    }
}

@Composable
private fun LoadingBody(modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.book_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailedBody(error: AppError, onRetry: () -> Unit, onOpenVerification: (String) -> Unit, modifier: Modifier) {
    val challenge = error as? AppError.CloudflareChallenge
    val text = if (challenge != null) {
        stringResource(R.string.book_load_cloudflare)
    } else {
        stringResource(R.string.book_load_failed, stringResource(error.downloadMessage()))
    }
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (challenge != null) Icons.Filled.VerifiedUser else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (challenge != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (challenge != null) {
                Button(onClick = { onOpenVerification(challenge.url) }) {
                    Text(stringResource(R.string.book_pass_verification))
                }
            }
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.book_retry))
            }
        }
    }
}

@Composable
private fun BookBody(
    book: FlibustaBookDetails,
    state: FlibustaBookUiState,
    onFormatSelected: (Format) -> Unit,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BookHeader(book)

        state.downloaded?.let { DownloadedMark(it) }

        if (book.formats.isEmpty()) {
            Text(
                stringResource(R.string.book_no_downloads),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.book_formats), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                book.formats.forEach { format ->
                    FilterChip(
                        selected = format == state.selectedFormat,
                        onClick = { onFormatSelected(format) },
                        label = { Text(format.label()) },
                    )
                }
            }
        }

        if (state.rootMissing) RootMissingNotice(MediaKind.BOOK, onOpenSettings)

        Button(onClick = onDownload, enabled = state.canDownload, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (state.enqueuing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(if (state.downloaded != null) R.string.book_download_again else R.string.book_download),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Cover-like tile, source, title and author. */
@Composable
private fun BookHeader(book: FlibustaBookDetails) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.width(104.dp).height(148.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(28.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(stringResource(Source.FLIBUSTA.label()))
            Text(book.title, style = MaterialTheme.typography.headlineSmall)
            if (book.author.isNotBlank()) {
                Text(book.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DownloadedMark(download: Download) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            val format = download.format
            Text(
                if (format != null) {
                    stringResource(R.string.book_downloaded_format, format.label())
                } else {
                    stringResource(R.string.book_downloaded)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun Format.label(): String = extension.uppercase()

// Previews of the main states, built from the same sample data as the fakes. Getters, not
// stored values: a stored top-level value is built when the screen loads, which would pull
// the fakes into the release build.

private val previewBook: FlibustaBookDetails get() = FakeData.flibustaBooks.first()

private val previewDownloaded get() = Download(
    id = "preview",
    kind = MediaKind.BOOK,
    source = Source.FLIBUSTA,
    sourceId = previewBook.id,
    title = previewBook.title,
    author = previewBook.author,
    format = Format.FB2,
    status = DownloadStatus.COMPLETED,
    createdAt = Instant.parse("2026-11-01T09:00:00Z"),
)

@Composable
private fun BookPreview(state: FlibustaBookUiState) {
    BookReconTheme {
        FlibustaBookContent(state, remember { SnackbarHostState() }, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun BookLoadingPreview() = BookPreview(FlibustaBookUiState())

@Preview(showBackground = true)
@Composable
private fun BookLoadedPreview() = BookPreview(
    FlibustaBookUiState(details = BookDetailsState.Loaded(previewBook), selectedFormat = Format.EPUB),
)

@Preview(showBackground = true)
@Composable
private fun BookDownloadedPreview() = BookPreview(
    FlibustaBookUiState(
        details = BookDetailsState.Loaded(previewBook),
        selectedFormat = Format.EPUB,
        downloaded = previewDownloaded,
    ),
)

@Preview(showBackground = true)
@Composable
private fun BookEnqueuingPreview() = BookPreview(
    FlibustaBookUiState(details = BookDetailsState.Loaded(previewBook), selectedFormat = Format.EPUB, enqueuing = true),
)

@Preview(showBackground = true)
@Composable
private fun BookNoDownloadsPreview() = BookPreview(
    FlibustaBookUiState(details = BookDetailsState.Loaded(previewBook.copy(formats = emptyList()))),
)

@Preview(showBackground = true)
@Composable
private fun BookNetworkErrorPreview() = BookPreview(
    FlibustaBookUiState(details = BookDetailsState.Failed(AppError.Network())),
)

@Preview(showBackground = true)
@Composable
private fun BookCloudflarePreview() = BookPreview(
    FlibustaBookUiState(details = BookDetailsState.Failed(AppError.CloudflareChallenge("https://flibusta.is/b/11111"))),
)
