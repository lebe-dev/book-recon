package ru.tinyops.android.bookrecon.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.server.ServerProblem
import ru.tinyops.android.bookrecon.server.ServerState
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme
import java.time.Instant

/** What the user can do in the server block. */
data class ServerActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onUrlChange: (String) -> Unit = {},
    val onSaveUrl: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onOpenTelegram: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
)

@Composable
internal fun ServerBlock(state: ServerUiState, actions: ServerActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    val save = {
        keyboard?.hide()
        actions.onSaveUrl()
    }
    val connection = state.connection
    val busy = connection == ServerState.Connecting

    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable(enabled = !busy) { actions.onEnabledChange(!state.enabled) },
        headlineContent = { Text(stringResource(R.string.settings_server_enable)) },
        supportingContent = { Text(stringResource(R.string.settings_server_text)) },
        trailingContent = { Switch(checked = state.enabled, onCheckedChange = actions.onEnabledChange, enabled = !busy) },
    )
    Text(
        text = stringResource(R.string.settings_server_next_step),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (!state.enabled) return

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.url,
                onValueChange = actions.onUrlChange,
                label = { Text(stringResource(R.string.settings_server_url)) },
                singleLine = true,
                enabled = !busy,
                isError = state.urlError,
                supportingText = {
                    Text(stringResource(if (state.urlError) R.string.settings_server_url_error else R.string.settings_server_url_hint))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = save, enabled = state.urlChanged && !busy, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_save))
            }
        }
        if (connection != ServerState.NotConfigured && !state.urlChanged) {
            ServerStatus(connection)
            ServerButtons(connection, actions)
        }
    }
}

@Composable
private fun ServerStatus(connection: ServerState) {
    when (connection) {
        ServerState.Off, ServerState.NotConfigured -> Unit
        ServerState.Connecting, is ServerState.AwaitingTelegram, ServerState.Pending -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(connection.message()), style = MaterialTheme.typography.bodyMedium)
        }
        is ServerState.Approved -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            val username = connection.telegramUsername
            Text(
                text = if (username.isNullOrEmpty()) {
                    stringResource(R.string.settings_server_status_approved)
                } else {
                    stringResource(R.string.settings_server_status_approved_as, username)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ServerState.Denied, is ServerState.Failed -> Text(
            text = stringResource(connection.message()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Text(stringResource(connection.message()), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ServerButtons(connection: ServerState, actions: ServerActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (connection) {
            ServerState.Off, ServerState.NotConfigured, ServerState.Connecting -> Unit
            ServerState.Disconnected -> Button(onClick = actions.onConnect) {
                Text(stringResource(R.string.settings_server_connect))
            }
            is ServerState.Failed -> Button(onClick = actions.onConnect) {
                Text(stringResource(R.string.settings_server_retry))
            }
            is ServerState.AwaitingTelegram -> {
                Button(onClick = actions.onOpenTelegram) { Text(stringResource(R.string.settings_server_open_telegram)) }
                TextButton(onClick = actions.onDisconnect) { Text(stringResource(R.string.settings_server_cancel)) }
            }
            ServerState.Pending -> TextButton(onClick = actions.onDisconnect) {
                Text(stringResource(R.string.settings_server_cancel))
            }
            is ServerState.Approved, ServerState.Denied -> TextButton(onClick = actions.onDisconnect) {
                Text(stringResource(R.string.settings_server_disconnect))
            }
        }
    }
}

@StringRes
private fun ServerState.message(): Int = when (this) {
    ServerState.Off, ServerState.NotConfigured, ServerState.Disconnected -> R.string.settings_server_status_disconnected
    ServerState.Connecting -> R.string.settings_server_status_connecting
    is ServerState.AwaitingTelegram -> R.string.settings_server_status_awaiting
    ServerState.Pending -> R.string.settings_server_status_pending
    is ServerState.Approved -> R.string.settings_server_status_approved
    ServerState.Denied -> R.string.settings_server_status_denied
    is ServerState.Failed -> when (problem) {
        ServerProblem.UNREACHABLE -> R.string.settings_server_problem_unreachable
        ServerProblem.NOT_BOOK_RECON -> R.string.settings_server_problem_not_book_recon
        ServerProblem.SERVER_ERROR -> R.string.settings_server_problem_server_error
    }
}

// Previews of the main states.

@Composable
private fun ServerPreview(connection: ServerState) {
    BookReconTheme {
        Surface {
            ServerBlock(ServerUiState(url = "https://books.example.com", storedUrl = "https://books.example.com", connection = connection), ServerActions())
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerDisconnectedPreview() = ServerPreview(ServerState.Disconnected)

@Preview(showBackground = true)
@Composable
private fun ServerOffPreview() = ServerPreview(ServerState.Off)

@Preview(showBackground = true)
@Composable
private fun ServerPendingPreview() = ServerPreview(ServerState.Pending)

@Preview(showBackground = true)
@Composable
private fun ServerAwaitingPreview() = ServerPreview(ServerState.AwaitingTelegram("https://t.me/bot?start=link_1", Instant.EPOCH))

@Preview(showBackground = true)
@Composable
private fun ServerApprovedPreview() = ServerPreview(ServerState.Approved(setOf("rutracker.org"), "reader"))

@Preview(showBackground = true)
@Composable
private fun ServerFailedPreview() = ServerPreview(ServerState.Failed(ServerProblem.NOT_BOOK_RECON))
