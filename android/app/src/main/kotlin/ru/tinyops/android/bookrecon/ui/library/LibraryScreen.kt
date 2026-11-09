package ru.tinyops.android.bookrecon.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.download.downloadMessage
import ru.tinyops.android.bookrecon.ui.common.displayTitle
import ru.tinyops.android.bookrecon.ui.search.formatSize
import ru.tinyops.android.bookrecon.ui.search.label
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val TAG = "Library"

/** Authority of the FileProvider declared in the manifest. */
private const val FILES_AUTHORITY = "ru.tinyops.android.bookrecon.files"

@Composable
fun LibraryScreen(onOpenSearch: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context) {
        val app = context.appContainer
        viewModelFactory {
            initializer {
                LibraryViewModel(
                    repository = app.database.downloadRepository,
                    storage = app.settings.fileStorage,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
    val viewModel: LibraryViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Files may have been deleted with a file manager while the app was in the background.
    LifecycleResumeEffect(viewModel) {
        viewModel.onRefresh()
        onPauseOrDispose {}
    }

    val open = state.open
    LaunchedEffect(open) {
        if (open == null) return@LaunchedEffect
        if (viewFile(context, open)) viewModel.onOpenHandled() else viewModel.onOpenFailed()
    }

    MessageSnackbar(state.message, snackbarHostState, viewModel::onMessageShown)

    LibraryContent(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = LibraryActions(
            onQueryChange = viewModel::onQueryChange,
            onFilterChange = viewModel::onFilterChange,
            onOpen = viewModel::onOpen,
            onDelete = viewModel::onDeleteRequest,
            onDeleteConfirm = viewModel::onDeleteConfirm,
            onDeleteDismiss = viewModel::onDeleteDismiss,
            onForget = viewModel::onForget,
            onOpenSearch = onOpenSearch,
        ),
    )
}

/**
 * Opens [request] in another app through the FileProvider. Returns false when no app views
 * the file or the path is outside the provider's roots.
 */
private fun viewFile(context: Context, request: OpenRequest): Boolean {
    val intent = try {
        val uri = FileProvider.getUriForFile(context, FILES_AUTHORITY, File(request.path))
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, request.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Cannot share the file through the file provider", e)
        return false
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Log.i(TAG, "No app can view files of type ${request.mimeType}")
        false
    }
}

/** Callbacks of the library screen; every per-entry callback takes the download ID. */
class LibraryActions(
    val onQueryChange: (String) -> Unit = {},
    val onFilterChange: (LibraryFilter) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onDeleteConfirm: () -> Unit = {},
    val onDeleteDismiss: () -> Unit = {},
    val onForget: (String) -> Unit = {},
    val onOpenSearch: () -> Unit = {},
)

@Composable
private fun MessageSnackbar(message: LibraryMessage?, hostState: SnackbarHostState, onShown: () -> Unit) {
    if (message == null) return
    val text = when (message) {
        LibraryMessage.FileMissing -> stringResource(R.string.library_message_file_missing)
        LibraryMessage.NoAudioFiles -> stringResource(R.string.library_message_no_audio)
        LibraryMessage.NoApp -> stringResource(R.string.library_message_no_app)
        is LibraryMessage.DeleteFailed ->
            stringResource(R.string.library_message_delete_failed, stringResource(message.error.downloadMessage()))
        is LibraryMessage.ForgetFailed ->
            stringResource(R.string.library_message_forget_failed, stringResource(message.error.downloadMessage()))
    }
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(message) {
        hostState.showSnackbar(message = text, withDismissAction = true, duration = SnackbarDuration.Long)
        currentOnShown()
    }
}

/** Stateless body of the library screen, also used by previews. */
@Composable
fun LibraryContent(
    state: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(state, actions)
            Box(modifier = Modifier.weight(1f)) { Body(state, actions, zone) }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    state.deleteCandidate?.let { DeleteDialog(it, actions) }
}

@Composable
private fun SearchBar(state: LibraryUiState, actions: LibraryActions) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.library_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isEmpty()) return@OutlinedTextField
                IconButton(onClick = { actions.onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            LibraryFilter.entries.forEachIndexed { index, filter ->
                SegmentedButton(
                    selected = state.filter == filter,
                    onClick = { actions.onFilterChange(filter) },
                    shape = SegmentedButtonDefaults.itemShape(index, LibraryFilter.entries.size),
                ) {
                    Text(stringResource(filter.label()))
                }
            }
        }
    }
}

@Composable
private fun Body(state: LibraryUiState, actions: LibraryActions, zone: ZoneId) {
    val loadError = state.loadError
    when {
        state.loading -> CenteredBody { CircularProgressIndicator() }
        loadError != null -> CenteredBody {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.library_load_failed, stringResource(loadError.downloadMessage())),
                textAlign = TextAlign.Center,
            )
        }
        state.isLibraryEmpty -> EmptyBody(
            Icons.AutoMirrored.Filled.LibraryBooks,
            R.string.library_empty_title,
            R.string.library_empty_text,
            actions.onOpenSearch,
        )
        state.isEmpty -> EmptyBody(
            Icons.Filled.SearchOff,
            R.string.library_not_found_title,
            R.string.library_not_found_text,
            actions.onOpenSearch,
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = { it.id }) { download ->
                LibraryItem(download, download.id in state.busy, actions, zone)
            }
        }
    }
}

@Composable
private fun CenteredBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun EmptyBody(icon: ImageVector, @StringRes title: Int, @StringRes text: Int, onOpenSearch: () -> Unit) {
    CenteredBody {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(text), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onOpenSearch) { Text(stringResource(R.string.library_open_search)) }
    }
}

@Composable
private fun LibraryItem(download: Download, busy: Boolean, actions: LibraryActions, zone: ZoneId) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (download.kind == MediaKind.AUDIO) Icons.Filled.Headphones else Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        download.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        details(download, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (download.fileMissing) {
                        Text(
                            stringResource(R.string.library_file_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!download.fileMissing) {
                    OutlinedButton(onClick = { actions.onOpen(download.id) }, enabled = !busy) {
                        Text(stringResource(R.string.library_open))
                    }
                }
                MoreMenu(download, busy, actions)
            }
        }
    }
}

@Composable
private fun MoreMenu(download: Download, busy: Boolean, actions: LibraryActions) {
    var expanded by rememberSaveable(download.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!download.fileMissing) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_delete)) },
                    onClick = {
                        expanded = false
                        actions.onDelete(download.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_forget)) },
                onClick = {
                    expanded = false
                    actions.onForget(download.id)
                },
            )
        }
    }
}

/** "Книга · Flibusta · EPUB · 1,2 МБ · 09.11.2026"; unknown parts are left out. */
@Composable
private fun details(download: Download, zone: ZoneId): String {
    val parts = mutableListOf(stringResource(download.kind.label()), stringResource(download.source.label()))
    displayFormat(download)?.let { parts += it }
    if (download.size > 0) {
        val size = formatSize(download.size)
        parts += "${size.number} ${stringResource(size.unit.label)}"
    }
    parts += libraryDate(download.completedAt ?: download.createdAt, zone)
    return parts.joinToString(" · ")
}

@Composable
private fun DeleteDialog(download: Download, actions: LibraryActions) {
    val text = when (download.kind) {
        MediaKind.BOOK -> R.string.library_delete_text_book
        MediaKind.AUDIO -> R.string.library_delete_text_audio
    }
    AlertDialog(
        onDismissRequest = actions.onDeleteDismiss,
        title = { Text(stringResource(R.string.library_delete_title)) },
        text = { Text(stringResource(text, download.displayTitle())) },
        confirmButton = {
            TextButton(onClick = actions.onDeleteConfirm) {
                Text(stringResource(R.string.library_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDeleteDismiss) {
                Text(stringResource(R.string.library_delete_keep))
            }
        },
    )
}

@StringRes
private fun MediaKind.label(): Int = when (this) {
    MediaKind.BOOK -> R.string.library_kind_book
    MediaKind.AUDIO -> R.string.library_kind_audio
}

@StringRes
private fun LibraryFilter.label(): Int = when (this) {
    LibraryFilter.ALL -> R.string.library_filter_all
    LibraryFilter.BOOKS -> R.string.library_filter_books
    LibraryFilter.AUDIO -> R.string.library_filter_audio
}

// Previews of every state, on hand-made records.

private val previewZone: ZoneId = ZoneId.of("Europe/Moscow")
private val previewNow: Instant = Instant.parse("2026-11-09T10:00:00Z")

private val previewItems = listOf(
    Download(
        id = "b1",
        kind = MediaKind.BOOK,
        source = Source.FLIBUSTA,
        sourceId = "11111",
        title = "Мастер и Маргарита",
        author = "Михаил Булгаков",
        format = Format.EPUB,
        status = DownloadStatus.COMPLETED,
        paths = listOf("/storage/emulated/0/Books/Мастер и Маргарита.epub"),
        size = 1_234_567,
        createdAt = previewNow,
        completedAt = previewNow,
    ),
    Download(
        id = "a1",
        kind = MediaKind.AUDIO,
        source = Source.RUTRACKER,
        sourceId = "222",
        title = "Стругацкие - Пикник на обочине [Читает Кузнецов, 2020, MP3]",
        author = "",
        targetName = "Стругацкие - Пикник на обочине",
        status = DownloadStatus.COMPLETED,
        files = listOf(TorrentFile(0, "Пикник/01.mp3", 40_000_000), TorrentFile(1, "Пикник/02.mp3", 40_000_000)),
        paths = listOf("/storage/emulated/0/Audiobooks/Стругацкие - Пикник на обочине"),
        size = 432_000_000,
        createdAt = previewNow.minusSeconds(86_400),
        completedAt = previewNow.minusSeconds(86_000),
    ),
    Download(
        id = "b2",
        kind = MediaKind.BOOK,
        source = Source.FLIBUSTA,
        sourceId = "55555",
        title = "Мы",
        author = "Евгений Замятин",
        format = Format.PDF,
        status = DownloadStatus.COMPLETED,
        paths = listOf("/storage/emulated/0/Books/Мы.pdf"),
        size = 3_456_789,
        createdAt = previewNow.minusSeconds(7 * 86_400),
        completedAt = previewNow.minusSeconds(7 * 86_400),
        fileMissing = true,
    ),
)

@Composable
private fun LibraryPreview(state: LibraryUiState) {
    MaterialTheme {
        LibraryContent(state, remember { SnackbarHostState() }, LibraryActions(), zone = previewZone)
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryLoadingPreview() = LibraryPreview(LibraryUiState())

@Preview(showBackground = true)
@Composable
private fun LibraryItemsPreview() = LibraryPreview(LibraryUiState(loading = false, items = previewItems))

@Preview(showBackground = true)
@Composable
private fun LibraryEmptyPreview() = LibraryPreview(LibraryUiState(loading = false))

@Preview(showBackground = true)
@Composable
private fun LibraryNotFoundPreview() = LibraryPreview(
    LibraryUiState(query = "Толстой", filter = LibraryFilter.AUDIO, loading = false),
)

@Preview(showBackground = true)
@Composable
private fun LibraryDeleteDialogPreview() = LibraryPreview(
    LibraryUiState(loading = false, items = previewItems, deleteCandidate = previewItems[1]),
)

@Preview(showBackground = true)
@Composable
private fun LibraryLoadErrorPreview() = LibraryPreview(
    LibraryUiState(loading = false, loadError = AppError.StorageFailed()),
)
