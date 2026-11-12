package ru.tinyops.android.bookrecon.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.BuildConfig
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.log.LogEntry
import ru.tinyops.android.bookrecon.log.LogLevel
import ru.tinyops.android.bookrecon.log.format
import ru.tinyops.android.bookrecon.ui.settings.cardListItemColors

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context) {
        val app = context.appContainer
        viewModelFactory {
            initializer {
                LogViewModel(app.log.appLog, app.settings.settingsRepository, header = { deviceHeader() })
            }
        }
    }
    val viewModel: LogViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fileName = stringResource(R.string.log_file_name)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = viewModel.exportText()
            val saved = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            toast(context, if (saved) R.string.log_saved else R.string.log_save_failed)
        }
    }

    LogContent(
        state = state,
        onBack = onBack,
        onInfoChange = viewModel::onInfoChange,
        onDebugChange = viewModel::onDebugChange,
        onCopy = {
            copyToClipboard(context, viewModel.shownText())
            toast(context, R.string.log_copied)
        },
        onSave = { saveLauncher.launch(fileName) },
        onShare = {
            scope.launch {
                val text = viewModel.exportText()
                val intent = withContext(Dispatchers.IO) { shareIntent(context, text, fileName) }
                if (intent == null) toast(context, R.string.log_share_failed) else context.startActivity(intent)
            }
        },
        onClear = viewModel::onClear,
    )
}

/** Stateless body of the log screen, also used by previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogContent(
    state: LogUiState,
    onBack: () -> Unit,
    onInfoChange: (Boolean) -> Unit,
    onDebugChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.log_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LevelSwitch(R.string.log_level_info, R.string.log_level_info_text, state.infoEnabled, onInfoChange)
            LevelSwitch(R.string.log_level_debug, R.string.log_level_debug_text, state.debugEnabled, onDebugChange)
            if (!state.infoEnabled && !state.debugEnabled) {
                Text(
                    text = stringResource(R.string.log_both_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCopy, enabled = state.entries.isNotEmpty()) {
                    Text(stringResource(R.string.log_copy))
                }
                OutlinedButton(onClick = onSave) { Text(stringResource(R.string.log_save)) }
                OutlinedButton(onClick = onShare, enabled = state.entries.isNotEmpty()) {
                    Text(stringResource(R.string.log_share))
                }
                OutlinedButton(onClick = onClear, enabled = state.entries.isNotEmpty()) {
                    Text(stringResource(R.string.log_clear))
                }
            }
            HorizontalDivider()
            EntryList(state.entries, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LevelSwitch(title: Int, text: Int, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable { onChange(!enabled) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(text)) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun EntryList(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val zone = remember { ZoneId.systemDefault() }
    // New lines appear at the bottom; follow them unless the user scrolled up to read.
    LaunchedEffect(entries.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val following = last < 0 || last >= listState.layoutInfo.totalItemsCount - 2
        if (following) listState.scrollToItem(entries.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(entries) { entry ->
            Text(
                text = entry.format(zone),
                color = entry.level.color(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun LogLevel.color(): Color = when (this) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
}

private fun deviceHeader(): String =
    "Book Recon ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})\n" +
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.log_title), text))
}

private fun writeText(context: Context, uri: android.net.Uri, text: String): Boolean = try {
    val stream = context.contentResolver.openOutputStream(uri, "wt")
    stream?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Share title: app version and commit, e.g. `Book Recon v0.10.0 #4a551a3, логи:`. */
private fun shareTitle(): String = "Book Recon v${BuildConfig.VERSION_NAME} #${BuildConfig.GIT_HASH}, логи:"

/** Writes [text] to a file in the cache and wraps it into a chooser intent, null if the file cannot be written. */
private fun shareIntent(context: Context, text: String, fileName: String): Intent? = try {
    val dir = File(context.cacheDir, "shared-logs").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(text) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logfiles", file)
    val title = shareTitle()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Intent.createChooser(send, title)
} catch (_: IOException) {
    null
}
