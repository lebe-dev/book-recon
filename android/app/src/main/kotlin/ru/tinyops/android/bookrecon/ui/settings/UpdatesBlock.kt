package ru.tinyops.android.bookrecon.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.update.ApkAsset
import ru.tinyops.android.bookrecon.core.update.AppVersion
import ru.tinyops.android.bookrecon.core.update.Release
import ru.tinyops.android.bookrecon.di.UpdateModule
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme
import ru.tinyops.android.bookrecon.update.UpdateFailure
import ru.tinyops.android.bookrecon.update.UpdateState
import ru.tinyops.android.bookrecon.update.release

/** State of the updates page: where the updater is and the installed version. */
data class UpdatesUiState(
    val state: UpdateState = UpdateState.Idle,
    val current: AppVersion = AppVersion(0, 0, 0),
)

/** Everything the user can do on the updates page. */
data class UpdateActions(
    val onCheck: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAllowInstall: () -> Unit = {},
    val onConfirmInstall: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onAutoCheckChange: (Boolean) -> Unit = {},
)

/** The check result and its one action, on its own card. */
@Composable
internal fun UpdateStatusBlock(updates: UpdatesUiState, actions: UpdateActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.updates_current, updates.current.toString()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val state = updates.state) {
            UpdateState.Idle -> Button(onClick = actions.onCheck) { Text(stringResource(R.string.updates_check)) }
            UpdateState.Checking -> Busy(R.string.updates_checking)
            is UpdateState.UpToDate -> {
                Text(stringResource(R.string.updates_up_to_date), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = actions.onCheck) { Text(stringResource(R.string.updates_check_again)) }
            }
            is UpdateState.Available -> ReleaseHeader(state.release) {
                Button(onClick = actions.onInstall) { Text(stringResource(R.string.updates_install)) }
            }
            is UpdateState.Downloading -> ReleaseHeader(state.release) { DownloadProgress(state, actions.onCancel) }
            is UpdateState.NeedsPermission -> ReleaseHeader(state.release) {
                Text(stringResource(R.string.updates_needs_permission), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions.onAllowInstall) { Text(stringResource(R.string.updates_allow)) }
            }
            is UpdateState.Installing -> ReleaseHeader(state.release) {
                Busy(R.string.updates_installing)
                OutlinedButton(onClick = actions.onConfirmInstall) { Text(stringResource(R.string.updates_open_installer)) }
            }
            is UpdateState.Failed -> FailedBlock(state, actions)
        }
    }
}

/** The start-check switch and the link to all releases. */
@Composable
internal fun UpdateOptionsBlock(autoCheck: Boolean, actions: UpdateActions) {
    Column {
        ListItem(
            colors = cardListItemColors(),
            modifier = Modifier.clickable { actions.onAutoCheckChange(!autoCheck) },
            headlineContent = { Text(stringResource(R.string.updates_auto)) },
            supportingContent = { Text(stringResource(R.string.updates_auto_text)) },
            trailingContent = { Switch(checked = autoCheck, onCheckedChange = actions.onAutoCheckChange) },
        )
        LinkRow(R.string.updates_all_releases) { actions.onOpenUrl(UpdateModule.RELEASES_URL) }
    }
}

/** Release notes of an offered version, on their own card; nothing while there is none. */
@Composable
internal fun ReleaseNotesBlock(state: UpdateState, onOpenUrl: (String) -> Unit) {
    val release = state.release ?: return
    Column {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.updates_notes), style = MaterialTheme.typography.titleSmall)
            Text(
                release.notes.ifEmpty { stringResource(R.string.updates_dialog_no_notes) },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (release.pageUrl.isNotEmpty()) LinkRow(R.string.updates_release_page) { onOpenUrl(release.pageUrl) }
    }
}

@Composable
private fun ReleaseHeader(release: Release, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.updates_available, release.version.toString()), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.updates_size, megabytes(release.apk.size)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun DownloadProgress(state: UpdateState.Downloading, onCancel: () -> Unit) {
    val total = state.release.apk.size.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { (state.downloaded.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.updates_downloading, megabytes(state.downloaded), megabytes(total)),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.updates_cancel)) }
    }
}

@Composable
private fun FailedBlock(state: UpdateState.Failed, actions: UpdateActions) {
    val release = state.release
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (release != null) {
            Text(stringResource(R.string.updates_available, release.version.toString()), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(state.failure.message()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        val retry = if (release == null) actions.onCheck else actions.onInstall
        Button(onClick = retry) { Text(stringResource(R.string.updates_retry)) }
    }
}

@Composable
private fun Busy(@StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkRow(@StringRes text: Int, onClick: () -> Unit) {
    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(text), color = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
    )
}

@Composable
private fun megabytes(bytes: Long): String = stringResource(R.string.updates_megabytes, bytes / BYTES_IN_MEGABYTE)

private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0

@StringRes
private fun UpdateFailure.message(): Int = when (this) {
    UpdateFailure.NETWORK -> R.string.updates_error_network
    UpdateFailure.TIMEOUT -> R.string.updates_error_timeout
    UpdateFailure.SERVICE_DOWN -> R.string.updates_error_service_down
    UpdateFailure.NO_RELEASES -> R.string.updates_error_no_releases
    UpdateFailure.BAD_ANSWER -> R.string.updates_error_bad_answer
    UpdateFailure.CORRUPTED -> R.string.updates_error_corrupted
    UpdateFailure.STORAGE -> R.string.updates_error_storage
    UpdateFailure.SIGNATURE -> R.string.updates_error_signature
    UpdateFailure.INSTALL_FAILED -> R.string.updates_error_install
    UpdateFailure.UNKNOWN -> R.string.updates_error_unknown
}

private val PREVIEW_RELEASE = Release(
    version = AppVersion(0, 10, 0),
    notes = "• Проверка обновлений в настройках\n• Быстрее поиск на Flibusta",
    pageUrl = "https://github.com/lebe-dev/book-recon/releases/tag/v0.10.0",
    apk = ApkAsset("book-recon-0.10.0.apk", "", 37_087_202, null),
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun UpdatesDownloadingPreview() {
    BookReconTheme {
        Surface {
            SettingsSectionContent(
                section = SettingsSection.UPDATES,
                onBack = {},
                state = SettingsUiState(loaded = true),
                actions = SettingsActions(),
                updates = UpdatesUiState(UpdateState.Downloading(PREVIEW_RELEASE, 12_000_000), AppVersion(0, 9, 1)),
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun UpdatesFailedPreview() {
    BookReconTheme {
        Surface {
            SettingsSectionContent(
                section = SettingsSection.UPDATES,
                onBack = {},
                state = SettingsUiState(loaded = true),
                actions = SettingsActions(),
                updates = UpdatesUiState(UpdateState.Failed(UpdateFailure.NETWORK, null), AppVersion(0, 9, 1)),
            )
        }
    }
}
