package ru.tinyops.android.bookrecon.ui.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
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
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.naming.cleanTopicTitle
import ru.tinyops.android.bookrecon.core.selection.FileSelection
import ru.tinyops.android.bookrecon.core.selection.FolderState
import ru.tinyops.android.bookrecon.core.selection.TreeNode
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.download.downloadMessage
import ru.tinyops.android.bookrecon.ui.book.BookMessage
import ru.tinyops.android.bookrecon.ui.book.BookMessageAction
import ru.tinyops.android.bookrecon.ui.nav.TopicKind
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import ru.tinyops.android.bookrecon.ui.search.formatSize
import ru.tinyops.android.bookrecon.ui.search.leadsToSettings

@Composable
fun RuTrackerTopicScreen(
    topicId: String,
    kind: TopicKind,
    title: String,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    verificationResults: Flow<Boolean>,
) {
    val context = LocalContext.current
    val factory = remember(context, topicId, kind, title) {
        val app = context.appContainer
        viewModelFactory {
            initializer {
                RuTrackerTopicViewModel(
                    topicId = topicId,
                    kind = kind.toMediaKind(),
                    title = title,
                    ruTracker = app.ruTracker.ruTrackerClient,
                    torrents = app.torrent.torrentEngine,
                    settings = app.settings.settingsRepository,
                    queue = app.download.downloadQueue,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
    val viewModel: RuTrackerTopicViewModel = viewModel(factory = factory)
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

    // A queued download is followed on the downloads screen right away, not via a snackbar.
    LaunchedEffect(state.message) {
        if (state.message != BookMessage.Added) return@LaunchedEffect
        viewModel.onMessageShown()
        onOpenDownloads()
    }

    MessageSnackbar(
        message = state.message.takeIf { it != BookMessage.Added },
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

    val actions = remember(viewModel, onBack, onOpenVerification, onOpenSettings) {
        TopicActions(
            onBack = onBack,
            onToggleFile = viewModel::onToggleFile,
            onToggleFolder = viewModel::onToggleFolder,
            onToggleExpanded = viewModel::onToggleExpanded,
            onSelectAll = viewModel::onSelectAll,
            onSelectNone = viewModel::onSelectNone,
            onFolderNameChange = viewModel::onFolderNameChange,
            onDownload = viewModel::onDownload,
            onRetry = viewModel::onRetry,
            onOpenVerification = onOpenVerification,
            onOpenSettings = onOpenSettings,
        )
    }
    RuTrackerTopicContent(state = state, snackbarHostState = snackbarHostState, actions = actions)
}

/** What the stateless topic screen can ask for. */
class TopicActions(
    val onBack: () -> Unit = {},
    val onToggleFile: (index: Int) -> Unit = {},
    val onToggleFolder: (path: String) -> Unit = {},
    val onToggleExpanded: (path: String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onSelectNone: () -> Unit = {},
    val onFolderNameChange: (String) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit = { _, _ -> },
    val onOpenSettings: () -> Unit = {},
)

private fun TopicKind.toMediaKind(): MediaKind = when (this) {
    TopicKind.BOOK -> MediaKind.BOOK
    TopicKind.AUDIO -> MediaKind.AUDIO
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
        BookMessage.Added -> stringResource(R.string.topic_added)
        is BookMessage.EnqueueFailed ->
            stringResource(R.string.topic_enqueue_failed, stringResource(message.error.downloadMessage()))
    }
    val action = when (message) {
        BookMessage.Added -> BookMessageAction.DOWNLOADS
        is BookMessage.EnqueueFailed -> message.action
    }
    val actionLabel = stringResource(
        when (action) {
            BookMessageAction.DOWNLOADS -> R.string.topic_action_open
            BookMessageAction.SETTINGS -> R.string.topic_action_settings
            BookMessageAction.RETRY -> R.string.topic_action_retry
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

/** Stateless body of the topic screen, also used by previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuTrackerTopicContent(
    state: RuTrackerTopicUiState,
    snackbarHostState: SnackbarHostState,
    actions: TopicActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.kind == MediaKind.AUDIO) R.string.topic_title_audio else R.string.topic_title_book))
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.topic_back))
                    }
                },
            )
        },
        bottomBar = {
            if (state.load is TopicLoadState.Loaded) DownloadBar(state, actions.onDownload)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val bodyModifier = Modifier.fillMaxSize().padding(padding)
        when (val load = state.load) {
            TopicLoadState.Loading -> LoadingBody(state.title, bodyModifier)
            is TopicLoadState.Failed -> FailedBody(load.error, actions, bodyModifier)
            is TopicLoadState.Loaded -> TreeBody(state, actions, bodyModifier)
        }
    }
}

@Composable
private fun LoadingBody(title: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        CircularProgressIndicator()
        Text(stringResource(R.string.topic_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailedBody(error: AppError, actions: TopicActions, modifier: Modifier) {
    val text = when {
        error is AppError.CloudflareChallenge -> stringResource(R.string.topic_load_cloudflare)
        error is AppError.RuTrackerCaptcha -> stringResource(R.string.topic_load_captcha)
        error is AppError.RuTrackerAuthRequired -> stringResource(R.string.topic_load_auth)
        error is AppError.RuTrackerLoginFailed -> stringResource(R.string.topic_load_login_failed)
        error is AppError.NoMatchingFiles && error.kind == MediaKind.AUDIO -> stringResource(R.string.topic_no_audio)
        error is AppError.NoMatchingFiles -> stringResource(R.string.topic_no_books)
        else -> stringResource(R.string.topic_load_failed, stringResource(error.downloadMessage()))
    }
    val verification = error is AppError.CloudflareChallenge || error is AppError.RuTrackerCaptcha
    val icon = when {
        verification -> Icons.Filled.VerifiedUser
        error is AppError.NoMatchingFiles -> Icons.Filled.FolderOff
        else -> Icons.Filled.ErrorOutline
    }
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (verification) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (error is AppError.NoMatchingFiles) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FailedPrimaryAction(error, actions)
            OutlinedButton(onClick = actions.onRetry) {
                Text(stringResource(R.string.topic_retry))
            }
        }
    }
}

/** The button that fixes the cause: a web check, a manual login or settings; none for other errors. */
@Composable
private fun FailedPrimaryAction(error: AppError, actions: TopicActions) {
    when {
        error is AppError.CloudflareChallenge ->
            Button(onClick = { actions.onOpenVerification(error.url, VerificationPurpose.CLOUDFLARE) }) {
                Text(stringResource(R.string.topic_pass_verification))
            }
        error is AppError.RuTrackerCaptcha ->
            Button(onClick = { actions.onOpenVerification(error.loginUrl, VerificationPurpose.RUTRACKER_LOGIN) }) {
                Text(stringResource(R.string.topic_log_in))
            }
        error.leadsToSettings() ->
            Button(onClick = actions.onOpenSettings) {
                Text(stringResource(R.string.topic_open_settings))
            }
    }
}

@Composable
private fun TreeBody(state: RuTrackerTopicUiState, actions: TopicActions, modifier: Modifier) {
    val selection = state.selection ?: return
    LazyColumn(modifier = modifier) {
        item(key = "header", contentType = "header") {
            TopicHeader(state, selection, actions)
        }
        items(state.tree, key = { it.key }, contentType = { if (it is TreeNode.Folder) "folder" else "file" }) { node ->
            when (node) {
                is TreeNode.Folder -> FolderRow(node, actions.onToggleFolder, actions.onToggleExpanded)
                is TreeNode.File -> FileRow(node, actions.onToggleFile)
            }
        }
    }
}

@Composable
private fun TopicHeader(state: RuTrackerTopicUiState, selection: FileSelection, actions: TopicActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(state.title, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.topic_torrent_size, sizeText(state.torrentSize)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.kind == MediaKind.AUDIO) {
            OutlinedTextField(
                value = state.folderName,
                onValueChange = actions.onFolderNameChange,
                label = { Text(stringResource(R.string.topic_folder_label)) },
                supportingText = {
                    Text(stringResource(if (state.folderNameMissing) R.string.topic_folder_missing else R.string.topic_folder_hint))
                },
                isError = state.folderNameMissing,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.topic_selected_summary,
                    selection.selectedCount,
                    selection.files.size,
                    sizeText(selection.selectedSize),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onSelectAll, enabled = selection.selectedCount < selection.files.size) {
                Text(stringResource(R.string.topic_select_all))
            }
            TextButton(onClick = actions.onSelectNone, enabled = selection.selectedCount > 0) {
                Text(stringResource(R.string.topic_select_none))
            }
        }
        HorizontalDivider()
    }
}

private val indentStep = 20.dp
private val expanderSize = 40.dp

@Composable
private fun FolderRow(node: TreeNode.Folder, onToggleFolder: (String) -> Unit, onToggleExpanded: (String) -> Unit) {
    val path = node.state.path
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpanded(path) }
            .padding(start = 8.dp + indentStep * node.depth, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (node.expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = stringResource(if (node.expanded) R.string.topic_collapse else R.string.topic_expand),
            modifier = Modifier.size(expanderSize).padding(8.dp),
        )
        TriStateCheckbox(state = node.state.check.toToggleableState(), onClick = { onToggleFolder(path) })
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(sizeText(node.state.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileRow(node: TreeNode.File, onToggleFile: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = node.selected, role = Role.Checkbox, onValueChange = { onToggleFile(node.file.index) })
            .padding(start = 8.dp + indentStep * node.depth + expanderSize, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = node.selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(sizeText(node.file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadBar(state: RuTrackerTopicUiState, onDownload: () -> Unit) {
    val selection = state.selection ?: return
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onDownload,
            enabled = state.canDownload,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.enqueuing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            val count = selection.selectedCount
            Text(
                if (count == 0) {
                    stringResource(R.string.topic_download_nothing)
                } else {
                    pluralStringResource(R.plurals.topic_download, count, count, sizeText(selection.selectedSize))
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** [bytes] as "1,2 ГБ". */
@Composable
private fun sizeText(bytes: Long): String {
    val formatted = formatSize(bytes)
    return stringResource(R.string.search_size, formatted.number, stringResource(formatted.unit.label))
}

private fun FolderState.Check.toToggleableState(): ToggleableState = when (this) {
    FolderState.Check.ALL -> ToggleableState.On
    FolderState.Check.PARTIAL -> ToggleableState.Indeterminate
    FolderState.Check.NONE -> ToggleableState.Off
}

// Previews of the main states, built from the same sample data as the fakes.

private fun previewState(
    topicId: String,
    kind: MediaKind,
    collapsed: Set<String> = emptySet(),
    change: (FileSelection) -> FileSelection = { it },
): RuTrackerTopicUiState {
    val info = FakeData.torrentInfo(topicId)
    val title = FakeData.topics.first { it.topicId == topicId }.title
    val offered = if (kind == MediaKind.AUDIO) FileSelection.forAudio(info) else FileSelection.forBooks(info)
    val selection = change(offered)
    return RuTrackerTopicUiState(
        kind = kind,
        title = title,
        load = TopicLoadState.Loaded(info),
        selection = selection,
        collapsed = collapsed,
        tree = selection.tree(collapsed),
        folderName = cleanTopicTitle(title),
    )
}

@Composable
private fun TopicPreview(state: RuTrackerTopicUiState) {
    MaterialTheme {
        RuTrackerTopicContent(state, remember { SnackbarHostState() }, TopicActions())
    }
}

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun TopicAudioPreview() = TopicPreview(
    previewState(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO) { it.toggleFile(0) },
)

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun TopicAudioCollapsedPreview() = TopicPreview(
    previewState(FakeData.AUDIO_TOPIC_CD, MediaKind.AUDIO, collapsed = setOf("Булгаков - Мастер и Маргарита/CD2")),
)

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun TopicBooksPreview() = TopicPreview(previewState(FakeData.BOOK_TOPIC_COLLECTION, MediaKind.BOOK))

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun TopicEmptySelectionPreview() = TopicPreview(
    previewState(FakeData.AUDIO_TOPIC_FLAT, MediaKind.AUDIO) { it.selectNone() },
)

@Preview(showBackground = true)
@Composable
private fun TopicLoadingPreview() = TopicPreview(
    RuTrackerTopicUiState(kind = MediaKind.AUDIO, title = FakeData.topics.first().title),
)

@Preview(showBackground = true)
@Composable
private fun TopicNoMatchingFilesPreview() = TopicPreview(
    RuTrackerTopicUiState(
        kind = MediaKind.AUDIO,
        title = FakeData.topics.first().title,
        load = TopicLoadState.Failed(AppError.NoMatchingFiles(MediaKind.AUDIO)),
    ),
)

@Preview(showBackground = true)
@Composable
private fun TopicAuthRequiredPreview() = TopicPreview(
    RuTrackerTopicUiState(
        kind = MediaKind.BOOK,
        title = FakeData.topics.last().title,
        load = TopicLoadState.Failed(AppError.RuTrackerAuthRequired()),
    ),
)

@Preview(showBackground = true)
@Composable
private fun TopicCaptchaPreview() = TopicPreview(
    RuTrackerTopicUiState(
        kind = MediaKind.AUDIO,
        title = FakeData.topics.first().title,
        load = TopicLoadState.Failed(AppError.RuTrackerCaptcha("https://rutracker.org/forum/login.php")),
    ),
)
