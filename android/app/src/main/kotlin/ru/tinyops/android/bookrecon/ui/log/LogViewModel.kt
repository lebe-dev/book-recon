package ru.tinyops.android.bookrecon.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.core.port.SettingsRepository
import ru.tinyops.android.bookrecon.log.AppLog
import ru.tinyops.android.bookrecon.log.LogEntry
import ru.tinyops.android.bookrecon.log.format

/**
 * State of the app log screen.
 *
 * @property infoEnabled INFO level is recorded (info, warnings, errors).
 * @property debugEnabled DEBUG level is recorded.
 * @property entries the newest entries, oldest first.
 */
data class LogUiState(
    val infoEnabled: Boolean = true,
    val debugEnabled: Boolean = false,
    val entries: List<LogEntry> = emptyList(),
)

/**
 * Logic of the app log screen: the two level switches live in the settings, entries come
 * from [appLog]. [header] describes the app and the device and starts every exported text.
 */
class LogViewModel(
    private val appLog: AppLog,
    private val settings: SettingsRepository,
    private val header: () -> String = { "" },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    val state: StateFlow<LogUiState> = combine(settings.settings, appLog.entries) { stored, entries ->
        LogUiState(stored.logInfo, stored.logDebug, entries)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LogUiState())

    fun onInfoChange(enabled: Boolean) {
        viewModelScope.launch { settings.update { it.copy(logInfo = enabled) } }
    }

    fun onDebugChange(enabled: Boolean) {
        viewModelScope.launch { settings.update { it.copy(logDebug = enabled) } }
    }

    fun onClear() = appLog.clear()

    /** The entries on screen as text, for the clipboard. */
    fun shownText(): String = state.value.entries.joinToString("\n") { it.format(zone) }

    /** The whole stored log with a [header], for a file. Reads the disk, so it is suspending. */
    suspend fun exportText(): String = withContext(Dispatchers.IO) { "${header()}\n\n${appLog.text()}" }
}
