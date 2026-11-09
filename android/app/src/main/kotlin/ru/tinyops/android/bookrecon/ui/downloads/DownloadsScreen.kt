package ru.tinyops.android.bookrecon.ui.downloads

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.download.downloadMessage
import ru.tinyops.android.bookrecon.ui.search.formatSize
import ru.tinyops.android.bookrecon.ui.search.label
import java.time.Instant
import java.time.ZoneId

@Composable
fun DownloadsScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context) {
        val app = context.appContainer
        viewModelFactory { initializer { DownloadsViewModel(app.download.downloadQueue) } }
    }
    val viewModel: DownloadsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    MessageSnackbar(state.message, snackbarHostState, viewModel::onMessageShown)

    DownloadsContent(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = DownloadsActions(
            onCancel = viewModel::onCancelRequest,
            onCancelConfirm = viewModel::onCancelConfirm,
            onCancelDismiss = viewModel::onCancelDismiss,
            onRetry = viewModel::onRetry,
            onStopSeeding = viewModel::onStopSeeding,
            onDismiss = viewModel::onDismiss,
            onOpenSettings = onOpenSettings,
        ),
    )
}

/** Callbacks of the downloads screen; every per-download callback takes the download ID. */
class DownloadsActions(
    val onCancel: (String) -> Unit = {},
    val onCancelConfirm: () -> Unit = {},
    val onCancelDismiss: () -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onStopSeeding: (String) -> Unit = {},
    val onDismiss: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
private fun MessageSnackbar(message: DownloadsMessage?, hostState: SnackbarHostState, onShown: () -> Unit) {
    if (message == null) return
    val text = stringResource(
        when (message.action) {
            DownloadAction.CANCEL -> R.string.downloads_action_failed_cancel
            DownloadAction.RETRY -> R.string.downloads_action_failed_retry
            DownloadAction.STOP_SEEDING -> R.string.downloads_action_failed_stop_seeding
            DownloadAction.DISMISS -> R.string.downloads_action_failed_dismiss
        },
        stringResource(message.error.downloadMessage()),
    )
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(message) {
        hostState.showSnackbar(message = text, withDismissAction = true, duration = SnackbarDuration.Long)
        currentOnShown()
    }
}

/**
 * Stateless body of the downloads screen, also used by previews. [now] and [zone] turn the
 * seeding deadline into "until HH:MM".
 */
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    snackbarHostState: SnackbarHostState,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Box(modifier = modifier.fillMaxSize()) {
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
                    stringResource(R.string.downloads_load_failed, stringResource(loadError.downloadMessage())),
                    textAlign = TextAlign.Center,
                )
            }
            state.isEmpty -> CenteredBody {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(stringResource(R.string.downloads_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.downloads_empty_text),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> DownloadsList(state, actions, now, zone)
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    state.cancelCandidate?.let { CancelDialog(it, actions) }
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
private fun DownloadsList(state: DownloadsUiState, actions: DownloadsActions, now: Instant, zone: ZoneId) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        section(R.string.downloads_section_active, state.active) { download ->
            ActiveItem(download, download.id in state.busy, actions)
        }
        section(R.string.downloads_section_queued, state.queued) { download ->
            QueuedItem(download, download.id in state.busy, actions)
        }
        section(R.string.downloads_section_seeding, state.seeding) { download ->
            SeedingItem(download, download.id in state.busy, actions, now, zone)
        }
        section(R.string.downloads_section_failed, state.failed) { download ->
            FailedItem(download, download.id in state.busy, actions)
        }
    }
}

private fun LazyListScope.section(
    @StringRes title: Int,
    downloads: List<Download>,
    content: @Composable (Download) -> Unit,
) {
    if (downloads.isEmpty()) return
    item(key = "header-$title", contentType = "header") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
    items(downloads, key = { it.id }, contentType = { it.status }) { content(it) }
}

@Composable
private fun DownloadCard(download: Download, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        download.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.downloads_subtitle,
                            stringResource(download.kind.label()),
                            stringResource(download.source.label()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun ActiveItem(download: Download, busy: Boolean, actions: DownloadsActions) {
    val progress = download.progress
    DownloadCard(download, Icons.Filled.Download) {
        if (progress.total > 0) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(progressText(download), style = MaterialTheme.typography.bodySmall)
        ActionRow {
            TextButton(onClick = { actions.onCancel(download.id) }, enabled = !busy) {
                Text(stringResource(R.string.downloads_cancel))
            }
        }
    }
}

/** "180 МБ из 412 МБ · 2,3 МБ/с · 7 пиров"; Flibusta reports no progress, so only "Загружается…". */
@Composable
private fun progressText(download: Download): String {
    val progress = download.progress
    if (progress.total <= 0) return stringResource(R.string.downloads_progress_unknown)
    val done = formatSize(progress.completed)
    val all = formatSize(progress.total)
    val parts = mutableListOf(
        stringResource(
            R.string.downloads_progress,
            done.number,
            stringResource(done.unit.label),
            all.number,
            stringResource(all.unit.label),
        ),
    )
    if (progress.speedBytesPerSec > 0) {
        val speed = formatSize(progress.speedBytesPerSec)
        parts += stringResource(R.string.downloads_speed, speed.number, stringResource(speed.unit.label))
    }
    if (download.source == Source.RUTRACKER) {
        parts += pluralStringResource(R.plurals.downloads_peers, progress.peers, progress.peers)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun QueuedItem(download: Download, busy: Boolean, actions: DownloadsActions) {
    DownloadCard(download, Icons.Filled.Schedule) {
        download.queuePosition?.let {
            Text(stringResource(R.string.downloads_queue_position, it), style = MaterialTheme.typography.bodySmall)
        }
        ActionRow {
            TextButton(onClick = { actions.onCancel(download.id) }, enabled = !busy) {
                Text(stringResource(R.string.downloads_cancel))
            }
        }
    }
}

@Composable
private fun SeedingItem(download: Download, busy: Boolean, actions: DownloadsActions, now: Instant, zone: ZoneId) {
    DownloadCard(download, Icons.Filled.CloudUpload) {
        download.seedUntil?.let { until ->
            val text = when (val end = seedUntil(until, now, zone)) {
                is SeedUntil.Today -> stringResource(R.string.downloads_seeding_today, end.time)
                is SeedUntil.Tomorrow -> stringResource(R.string.downloads_seeding_tomorrow, end.time)
                is SeedUntil.Later -> stringResource(R.string.downloads_seeding_later, end.date, end.time)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(R.string.downloads_seeding_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            TextButton(onClick = { actions.onStopSeeding(download.id) }, enabled = !busy) {
                Text(stringResource(R.string.downloads_stop_seeding))
            }
        }
    }
}

@Composable
private fun FailedItem(download: Download, busy: Boolean, actions: DownloadsActions) {
    val error = download.error ?: AppError.Unknown()
    DownloadCard(download, Icons.Filled.ErrorOutline) {
        Text(
            stringResource(R.string.downloads_error, stringResource(error.downloadMessage())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        ActionRow {
            TextButton(onClick = { actions.onDismiss(download.id) }, enabled = !busy) {
                Text(stringResource(R.string.downloads_dismiss))
            }
            if (error.fixableInSettings()) {
                TextButton(onClick = actions.onOpenSettings) {
                    Text(stringResource(R.string.downloads_settings))
                }
            }
            TextButton(onClick = { actions.onRetry(download.id) }, enabled = !busy) {
                Text(stringResource(R.string.downloads_retry))
            }
        }
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { content() }
}

@Composable
private fun CancelDialog(download: Download, actions: DownloadsActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelDismiss,
        title = { Text(stringResource(R.string.downloads_cancel_title)) },
        text = { Text(stringResource(R.string.downloads_cancel_text, download.displayTitle())) },
        confirmButton = {
            TextButton(onClick = actions.onCancelConfirm) {
                Text(stringResource(R.string.downloads_cancel_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelDismiss) {
                Text(stringResource(R.string.downloads_cancel_keep))
            }
        },
    )
}

/** The audiobook folder name or the book title, prefixed with the author when known. */
private fun Download.displayTitle(): String {
    val name = targetName?.takeIf { kind == MediaKind.AUDIO && it.isNotBlank() } ?: title
    if (author.isBlank()) return name
    return "$author — $name"
}

@StringRes
private fun MediaKind.label(): Int = when (this) {
    MediaKind.BOOK -> R.string.downloads_kind_book
    MediaKind.AUDIO -> R.string.downloads_kind_audio
}

// Previews of every state, on hand-made records.

private val previewNow: Instant = Instant.parse("2026-11-09T10:00:00Z")
private val previewZone: ZoneId = ZoneId.of("Europe/Moscow")

private fun previewDownload(
    id: String,
    status: DownloadStatus,
    kind: MediaKind = MediaKind.AUDIO,
    progress: DownloadProgress = DownloadProgress(),
    position: Int? = null,
    error: AppError? = null,
    seedUntil: Instant? = null,
) = Download(
    id = id,
    kind = kind,
    source = if (kind == MediaKind.AUDIO) Source.RUTRACKER else Source.FLIBUSTA,
    sourceId = id,
    title = if (kind == MediaKind.AUDIO) "Стругацкие - Пикник на обочине (Читает Кузнецов)" else "Мастер и Маргарита",
    author = if (kind == MediaKind.AUDIO) "" else "Михаил Булгаков",
    format = if (kind == MediaKind.BOOK) Format.EPUB else null,
    targetName = if (kind == MediaKind.AUDIO) "Стругацкие - Пикник на обочине" else null,
    status = status,
    progress = progress,
    queuePosition = position,
    error = error,
    createdAt = previewNow,
    seedUntil = seedUntil,
)

private val previewFull = DownloadsUiState(
    loading = false,
    active = listOf(
        previewDownload(
            "a1",
            DownloadStatus.DOWNLOADING,
            progress = DownloadProgress(180L * 1024 * 1024, 412L * 1024 * 1024, peers = 7, speedBytesPerSec = 2_400_000),
        ),
        previewDownload("a2", DownloadStatus.DOWNLOADING, kind = MediaKind.BOOK),
    ),
    queued = listOf(
        previewDownload("q1", DownloadStatus.QUEUED, kind = MediaKind.BOOK, position = 1),
        previewDownload("q2", DownloadStatus.QUEUED, position = 2),
    ),
    seeding = listOf(
        previewDownload("s1", DownloadStatus.SEEDING, seedUntil = Instant.parse("2026-11-09T15:30:00Z")),
    ),
    failed = listOf(
        previewDownload("f1", DownloadStatus.FAILED, error = AppError.NoSeeders()),
        previewDownload("f2", DownloadStatus.FAILED, error = AppError.Stalled()),
        previewDownload("f3", DownloadStatus.FAILED, kind = MediaKind.BOOK, error = AppError.StorageNotGranted()),
    ),
)

@Composable
private fun DownloadsPreview(state: DownloadsUiState) {
    MaterialTheme {
        DownloadsContent(state, remember { SnackbarHostState() }, DownloadsActions(), now = previewNow, zone = previewZone)
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsLoadingPreview() = DownloadsPreview(DownloadsUiState())

@Preview(showBackground = true)
@Composable
private fun DownloadsEmptyPreview() = DownloadsPreview(DownloadsUiState(loading = false))

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun DownloadsAllSectionsPreview() = DownloadsPreview(previewFull)

@Preview(showBackground = true)
@Composable
private fun DownloadsActivePreview() = DownloadsPreview(
    DownloadsUiState(loading = false, active = previewFull.active, queued = previewFull.queued),
)

@Preview(showBackground = true)
@Composable
private fun DownloadsSeedingPreview() = DownloadsPreview(
    DownloadsUiState(
        loading = false,
        seeding = previewFull.seeding + previewDownload(
            "s2",
            DownloadStatus.SEEDING,
            seedUntil = Instant.parse("2026-11-09T22:30:00Z"),
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun DownloadsFailedPreview() = DownloadsPreview(
    DownloadsUiState(loading = false, failed = previewFull.failed, busy = setOf("f2")),
)

@Preview(showBackground = true)
@Composable
private fun DownloadsCancelDialogPreview() = DownloadsPreview(
    DownloadsUiState(loading = false, active = previewFull.active, cancelCandidate = previewFull.active.first()),
)

@Preview(showBackground = true)
@Composable
private fun DownloadsLoadErrorPreview() = DownloadsPreview(
    DownloadsUiState(loading = false, loadError = AppError.StorageFailed()),
)
