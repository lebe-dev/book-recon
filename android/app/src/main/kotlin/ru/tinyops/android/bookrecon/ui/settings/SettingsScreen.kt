package ru.tinyops.android.bookrecon.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ColorTheme
import ru.tinyops.android.bookrecon.core.domain.DarkMode
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.server.ServerState
import ru.tinyops.android.bookrecon.storage.files.RootCheck
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme
import android.provider.Settings as SystemSettings

@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    onOpenLog: () -> Unit,
    verificationResults: Flow<Boolean>,
) {
    val context = LocalContext.current
    val factory = remember(context) {
        val module = context.appContainer.settings
        val ruTracker = context.appContainer.ruTracker.ruTrackerClient
        viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = module.settingsRepository,
                    ruTracker = ruTracker,
                    hasAllFilesAccess = { module.storage.hasAllFilesAccess() },
                    checkRoot = module.storage::checkRoot,
                    pathOfTree = module::pathOfTree,
                )
            }
        }
    }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverFactory = remember(context) {
        val connection = context.appContainer.server.connection
        viewModelFactory { initializer { ServerViewModel(connection) } }
    }
    val serverViewModel: ServerViewModel = viewModel(factory = serverFactory)
    val serverState by serverViewModel.state.collectAsStateWithLifecycle()

    // Access may have been granted in the system settings while the screen was hidden; the
    // admin may have decided on the server request while the user was in Telegram.
    LifecycleResumeEffect(viewModel, serverViewModel) {
        viewModel.onResume()
        serverViewModel.onResume()
        onPauseOrDispose {}
    }

    LaunchedEffect(serverViewModel) {
        serverViewModel.links.collect { link -> openLink(context, link) }
    }

    LaunchedEffect(verificationResults) {
        verificationResults.collect(viewModel::onVerificationResult)
    }

    val pending = state.pendingVerification
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        viewModel.onVerificationOpened()
        onOpenVerification(pending.url, pending.purpose)
    }

    val pickAudioRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onRootPicked(MediaKind.AUDIO, uri?.toString())
    }
    val pickBooksRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onRootPicked(MediaKind.BOOK, uri?.toString())
    }

    SettingsSectionContent(
        section = section,
        onBack = onBack,
        state = state,
        actions = SettingsActions(
            onOpenAccessSettings = { openAllFilesAccessSettings(context) },
            onPickRoot = { kind ->
                val launcher = if (kind == MediaKind.AUDIO) pickAudioRoot else pickBooksRoot
                try {
                    launcher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.settings_root_picker_unavailable, Toast.LENGTH_LONG).show()
                }
            },
            onLoginChange = viewModel::onLoginChange,
            onPasswordChange = viewModel::onPasswordChange,
            onCheckLogin = viewModel::onCheckLogin,
            onForgetCredentials = viewModel::onForgetCredentials,
            onFlibustaUrlChange = viewModel::onFlibustaUrlChange,
            onSaveFlibustaUrl = viewModel::onSaveFlibustaUrl,
            onPreferredFormatChange = viewModel::onPreferredFormatChange,
            onWifiOnlyChange = viewModel::onWifiOnlyChange,
            onTorrentPeersThroughServerChange = viewModel::onTorrentPeersThroughServerChange,
            onMaxConcurrentChange = viewModel::onMaxConcurrentChange,
            onSeedHoursChange = viewModel::onSeedHoursChange,
            onNoSeedersRetryChange = viewModel::onNoSeedersRetryChange,
            onSendErrorReportsChange = viewModel::onSendErrorReportsChange,
            onOpenLog = onOpenLog,
            onColorThemeChange = viewModel::onColorThemeChange,
            onDarkModeChange = viewModel::onDarkModeChange,
        ),
        server = serverState,
        serverActions = ServerActions(
            onEnabledChange = serverViewModel::onEnabledChange,
            onUrlChange = serverViewModel::onUrlChange,
            onSaveUrl = serverViewModel::onSaveUrl,
            onConnect = serverViewModel::onConnect,
            onOpenTelegram = serverViewModel::onOpenTelegram,
            onDisconnect = serverViewModel::onDisconnect,
        ),
    )
}

/** Opens a t.me link in Telegram, or in a browser when Telegram is not installed. */
private fun openLink(context: Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.settings_server_no_app, Toast.LENGTH_LONG).show()
    }
}

/** Opens the system screen where the user grants all-files access to this app. */
private fun openAllFilesAccessSettings(context: Context) {
    val forApp = Intent(
        SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    )
    val general = Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    for (intent in listOf(forApp, general)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            continue
        }
    }
    Toast.makeText(context, R.string.settings_access_unavailable, Toast.LENGTH_LONG).show()
}

/** Everything the user can do on the settings screen. */
data class SettingsActions(
    val onOpenAccessSettings: () -> Unit = {},
    val onPickRoot: (MediaKind) -> Unit = {},
    val onLoginChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onCheckLogin: () -> Unit = {},
    val onForgetCredentials: () -> Unit = {},
    val onFlibustaUrlChange: (String) -> Unit = {},
    val onSaveFlibustaUrl: () -> Unit = {},
    val onPreferredFormatChange: (Format) -> Unit = {},
    val onWifiOnlyChange: (Boolean) -> Unit = {},
    val onTorrentPeersThroughServerChange: (Boolean) -> Unit = {},
    val onMaxConcurrentChange: (Int) -> Unit = {},
    val onSeedHoursChange: (Int) -> Unit = {},
    val onNoSeedersRetryChange: (Int) -> Unit = {},
    val onSendErrorReportsChange: (Boolean) -> Unit = {},
    val onOpenLog: () -> Unit = {},
    val onColorThemeChange: (ColorTheme) -> Unit = {},
    val onDarkModeChange: (DarkMode) -> Unit = {},
)

/** Stateless body of one settings subpage, also used by previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionContent(
    section: SettingsSection,
    onBack: () -> Unit,
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    server: ServerUiState = ServerUiState(),
    serverActions: ServerActions = ServerActions(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            when (section) {
                SettingsSection.APPEARANCE -> Section(null) {
                    AppearanceBlock(state.settings, actions)
                }
                SettingsSection.STORAGE -> Section(null) {
                    AccessBlock(state.hasAllFilesAccess, actions.onOpenAccessSettings)
                    RootItem(MediaKind.AUDIO, state, actions.onPickRoot)
                    RootItem(MediaKind.BOOK, state, actions.onPickRoot)
                    state.rootProblem?.let { problem ->
                        ErrorLine(stringResource(problem.issue.message()))
                    }
                }
                SettingsSection.SERVER -> Section(null) {
                    ServerBlock(server, serverActions)
                    if (server.enabled) {
                        ListItem(
                            colors = cardListItemColors(),
                            modifier = Modifier.clickable {
                                actions.onTorrentPeersThroughServerChange(!state.settings.torrentPeersThroughServer)
                            },
                            headlineContent = { Text(stringResource(R.string.settings_server_torrent_peers)) },
                            supportingContent = { Text(stringResource(R.string.settings_server_torrent_peers_text)) },
                            trailingContent = {
                                Switch(
                                    checked = state.settings.torrentPeersThroughServer,
                                    onCheckedChange = actions.onTorrentPeersThroughServerChange,
                                )
                            },
                        )
                    }
                }
                SettingsSection.SOURCES -> {
                    Section(R.string.settings_section_rutracker) {
                        if (server.connection is ServerState.Approved) {
                            Text(
                                text = stringResource(R.string.settings_rutracker_server),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        RuTrackerBlock(state, actions)
                    }
                    Section(R.string.settings_section_flibusta) {
                        FlibustaBlock(state, actions)
                    }
                }
                SettingsSection.DOWNLOADS -> {
                    Section(R.string.settings_section_downloads) {
                        DownloadsBlock(state.settings, actions)
                        NoSeedersRetryBlock(state.settings.noSeedersRetryMinutes, actions.onNoSeedersRetryChange)
                    }
                    Section(R.string.settings_section_seeding) {
                        SeedingBlock(state.settings.seedHours, actions.onSeedHoursChange)
                    }
                }
                SettingsSection.DIAGNOSTICS -> {
                    Section(R.string.settings_section_reports) {
                        ErrorReportsBlock(state.settings.sendErrorReports, actions.onSendErrorReportsChange)
                    }
                    Section(R.string.settings_section_log) {
                        LogLinkBlock(actions.onOpenLog)
                    }
                }
            }
        }
    }
}

/** A titled group of settings on a rounded container card. */
@Composable
private fun Section(@StringRes title: Int?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 8.dp),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = if (title == null) 8.dp else 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** List item colors for rows on a [Section] card, which has its own background. */
@Composable
internal fun cardListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun ErrorLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun AccessBlock(granted: Boolean, onOpenAccessSettings: () -> Unit) {
    if (granted) {
        ListItem(
            colors = cardListItemColors(),
            headlineContent = { Text(stringResource(R.string.settings_access_granted)) },
            leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        )
        return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_access_missing_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = stringResource(R.string.settings_access_missing_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onOpenAccessSettings) {
                Text(stringResource(R.string.settings_access_open))
            }
        }
    }
}

@Composable
private fun RootItem(kind: MediaKind, state: SettingsUiState, onPickRoot: (MediaKind) -> Unit) {
    val path = state.settings.rootFor(kind)
    val warning = when (state.rootStatus[kind]) {
        RootCheck.MISSING -> R.string.settings_root_missing
        RootCheck.NOT_WRITABLE -> R.string.settings_root_not_writable
        RootCheck.NO_ACCESS -> R.string.settings_root_no_access
        RootCheck.OK, null -> null
    }
    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable { onPickRoot(kind) },
        headlineContent = {
            Text(stringResource(if (kind == MediaKind.AUDIO) R.string.settings_root_audio else R.string.settings_root_books))
        },
        supportingContent = {
            Column {
                Text(path ?: stringResource(R.string.settings_root_not_chosen))
                if (path != null && warning != null) {
                    Text(stringResource(warning), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
    )
}

@Composable
private fun RuTrackerBlock(state: SettingsUiState, actions: SettingsActions) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val running = state.loginCheck == LoginCheck.Running
    val check = {
        keyboard?.hide()
        actions.onCheckLogin()
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.login,
            onValueChange = actions.onLoginChange,
            label = { Text(stringResource(R.string.settings_rutracker_login)) },
            singleLine = true,
            enabled = !running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = actions.onPasswordChange,
            label = { Text(stringResource(R.string.settings_rutracker_password)) },
            singleLine = true,
            enabled = !running,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { check() }),
            supportingText = {
                Text(
                    stringResource(
                        if (state.passwordStored) R.string.settings_rutracker_password_stored else R.string.settings_rutracker_hint,
                    ),
                )
            },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (showPassword) R.string.settings_rutracker_hide_password else R.string.settings_rutracker_show_password,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = check, enabled = !running) {
                Text(stringResource(R.string.settings_rutracker_check))
            }
            val canForget = state.login.isNotEmpty() || state.passwordStored || state.password.isNotEmpty()
            TextButton(onClick = actions.onForgetCredentials, enabled = !running && canForget) {
                Text(stringResource(R.string.settings_rutracker_forget))
            }
        }
        LoginStatus(state.loginCheck)
    }
}

@Composable
private fun LoginStatus(check: LoginCheck) {
    when (check) {
        LoginCheck.Idle -> Unit
        LoginCheck.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_rutracker_checking), style = MaterialTheme.typography.bodyMedium)
        }
        LoginCheck.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_rutracker_success), style = MaterialTheme.typography.bodyMedium)
        }
        is LoginCheck.Failed -> Text(
            text = stringResource(check.error.loginMessage()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FlibustaBlock(state: SettingsUiState, actions: SettingsActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    val save = {
        keyboard?.hide()
        actions.onSaveFlibustaUrl()
    }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.flibustaUrl,
            onValueChange = actions.onFlibustaUrlChange,
            label = { Text(stringResource(R.string.settings_flibusta_url)) },
            singleLine = true,
            isError = state.flibustaUrlError,
            supportingText = {
                Text(stringResource(if (state.flibustaUrlError) R.string.settings_flibusta_url_error else R.string.settings_flibusta_url_hint))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = save, enabled = state.flibustaUrlChanged, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun DownloadsBlock(settings: Settings, actions: SettingsActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_preferred_format), style = MaterialTheme.typography.bodyLarge)
        ChoiceRow(
            options = Format.entries,
            selected = settings.preferredFormat,
            label = { it.extension.uppercase() },
            onSelect = actions.onPreferredFormatChange,
        )
    }
    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable { actions.onWifiOnlyChange(!settings.wifiOnly) },
        headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
        supportingContent = { Text(stringResource(R.string.settings_wifi_only_text)) },
        trailingContent = { Switch(checked = settings.wifiOnly, onCheckedChange = actions.onWifiOnlyChange) },
    )
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_max_concurrent), style = MaterialTheme.typography.bodyLarge)
        ChoiceRow(
            options = (1..Settings.MAX_CONCURRENT_LIMIT).toList(),
            selected = settings.maxConcurrent,
            label = { it.toString() },
            onSelect = actions.onMaxConcurrentChange,
        )
    }
}

@Composable
private fun NoSeedersRetryBlock(minutes: Int, onChange: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_no_seeders_retry), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_no_seeders_retry_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val options = SettingsViewModel.NO_SEEDERS_RETRY_OPTIONS.let { if (minutes in it) it else (it + minutes).sorted() }
        val off = stringResource(R.string.settings_no_seeders_retry_off)
        val labels = options.associateWith { stringResource(R.string.settings_no_seeders_retry_minutes, it) }
        ChoiceRow(
            options = options,
            selected = minutes,
            label = { if (it == 0) off else labels.getValue(it) },
            onSelect = onChange,
        )
    }
}

@Composable
private fun SeedingBlock(seedHours: Int, onSeedHoursChange: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.settings_seeding_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A stored value outside the choices (set by an older version) still shows as selected.
        val options = SettingsViewModel.SEED_HOURS_OPTIONS.let { if (seedHours in it) it else (it + seedHours).sorted() }
        val off = stringResource(R.string.settings_seeding_off)
        val labels = options.associateWith { stringResource(R.string.settings_seeding_hours, it) }
        ChoiceRow(
            options = options,
            selected = seedHours,
            label = { if (it == 0) off else labels.getValue(it) },
            onSelect = onSeedHoursChange,
        )
    }
}

@Composable
private fun ErrorReportsBlock(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable { onChange(!enabled) },
        headlineContent = { Text(stringResource(R.string.settings_reports_send)) },
        supportingContent = { Text(stringResource(R.string.settings_reports_text)) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun LogLinkBlock(onOpen: () -> Unit) {
    ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(stringResource(R.string.settings_log_open)) },
        supportingContent = { Text(stringResource(R.string.settings_log_open_text)) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

@StringRes
private fun RootIssue.message(): Int = when (this) {
    RootIssue.UNSUPPORTED -> R.string.settings_root_issue_unsupported
    RootIssue.NO_ACCESS -> R.string.settings_root_issue_no_access
    RootIssue.MISSING -> R.string.settings_root_issue_missing
    RootIssue.NOT_WRITABLE -> R.string.settings_root_issue_not_writable
}

/** Russian text for a failed login check. */
@StringRes
internal fun AppError.loginMessage(): Int = when (code) {
    ErrorCode.RUTRACKER_AUTH_REQUIRED -> R.string.settings_login_error_auth_required
    ErrorCode.RUTRACKER_LOGIN_FAILED -> R.string.settings_login_error_failed
    ErrorCode.RUTRACKER_CAPTCHA -> R.string.settings_login_error_captcha
    ErrorCode.CLOUDFLARE_CHALLENGE -> R.string.settings_login_error_cloudflare
    ErrorCode.NETWORK -> R.string.settings_login_error_network
    ErrorCode.TIMEOUT -> R.string.settings_login_error_timeout
    ErrorCode.STORAGE_FAILED -> R.string.settings_login_error_storage
    else -> R.string.settings_login_error_other
}

// Previews of the main states.

@Composable
private fun SettingsPreview(state: SettingsUiState, section: SettingsSection) {
    BookReconTheme(colorTheme = state.settings.colorTheme) {
        Surface {
            SettingsSectionContent(section, onBack = {}, state = state, actions = SettingsActions())
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SettingsFirstRunPreview() = SettingsPreview(SettingsUiState(loaded = true, flibustaUrl = Settings.DEFAULT_FLIBUSTA_BASE_URL), SettingsSection.SOURCES)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SettingsConfiguredPreview() = SettingsPreview(
    section = SettingsSection.STORAGE,
    state = SettingsUiState(
        settings = Settings(
            audioRoot = "/storage/emulated/0/Audiobooks",
            booksRoot = "/storage/1234-ABCD/Книги",
            rutrackerLogin = "reader",
            wifiOnly = true,
            seedHours = 3,
            colorTheme = ColorTheme.PISTACHIO,
        ),
        loaded = true,
        hasAllFilesAccess = true,
        login = "reader",
        passwordStored = true,
        loginCheck = LoginCheck.Success,
        flibustaUrl = Settings.DEFAULT_FLIBUSTA_BASE_URL,
        rootStatus = mapOf(MediaKind.AUDIO to RootCheck.OK, MediaKind.BOOK to RootCheck.MISSING),
    ),
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SettingsErrorsPreview() = SettingsPreview(
    section = SettingsSection.SOURCES,
    state = SettingsUiState(
        loaded = true,
        hasAllFilesAccess = true,
        login = "reader",
        password = "secret",
        loginCheck = LoginCheck.Failed(AppError.RuTrackerLoginFailed()),
        flibustaUrl = "flibusta",
        flibustaUrlError = true,
        rootProblem = RootProblem(MediaKind.BOOK, RootIssue.UNSUPPORTED),
    ),
)
