package ru.tinyops.android.bookrecon.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.update.ApkAsset
import ru.tinyops.android.bookrecon.core.update.AppVersion
import ru.tinyops.android.bookrecon.core.update.Release
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme

/**
 * Starts the background update check once per launch (when the user turned it on) and shows
 * the found release in a dialog. "Update" starts the download and opens the updates page.
 */
@Composable
fun UpdatePrompt(onOpenUpdates: () -> Unit) {
    val context = LocalContext.current
    val updater = remember(context) { context.appContainer.updates.updater }
    LaunchedEffect(updater) { updater.checkOnStart() }
    val release by updater.prompt.collectAsStateWithLifecycle()
    val offered = release ?: return
    UpdatePromptDialog(
        release = offered,
        onUpdate = {
            updater.acceptPrompt()
            onOpenUpdates()
        },
        onLater = updater::dismissPrompt,
        onSkip = { updater.skip(offered) },
    )
}

@Composable
private fun UpdatePromptDialog(release: Release, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.updates_dialog_title, release.version.toString())) },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    release.notes.ifEmpty { stringResource(R.string.updates_dialog_no_notes) },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text(stringResource(R.string.updates_dialog_update)) } },
        dismissButton = {
            Column {
                TextButton(onClick = onLater) { Text(stringResource(R.string.updates_dialog_later)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.updates_dialog_skip)) }
            }
        },
    )
}

@Preview
@Composable
private fun UpdatePromptDialogPreview() {
    BookReconTheme {
        Surface {
            UpdatePromptDialog(
                release = Release(
                    version = AppVersion(0, 10, 0),
                    notes = "• Проверка обновлений в настройках\n• Быстрее поиск на Flibusta",
                    pageUrl = "",
                    apk = ApkAsset("book-recon-0.10.0.apk", "", 37_087_202, null),
                ),
                onUpdate = {},
                onLater = {},
                onSkip = {},
            )
        }
    }
}
