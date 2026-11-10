package ru.tinyops.android.bookrecon.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.tinyops.android.bookrecon.server.ServerConnection
import ru.tinyops.android.bookrecon.server.ServerState

/**
 * State of the Book Recon server block on the settings screen.
 *
 * @property url text of the URL field, saved by [ServerViewModel.onSaveUrl].
 * @property storedUrl the saved URL.
 */
data class ServerUiState(
    val url: String = "",
    val storedUrl: String = "",
    val urlError: Boolean = false,
    val connection: ServerState = ServerState.Off,
) {
    val urlChanged: Boolean
        get() = url.trim().trimEnd('/') != storedUrl

    /** The user turned the server on. */
    val enabled: Boolean
        get() = connection != ServerState.Off
}

/**
 * Server block logic. The server is off until the user turns it on; turning it on with a URL,
 * or saving a URL while it is on, asks for access right away and opens Telegram.
 */
class ServerViewModel(private val connection: ServerConnection) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = mutableState.asStateFlow()

    private val linkChannel = Channel<String>(Channel.BUFFERED)

    /** Telegram links to open, one per request. */
    val links: Flow<String> = linkChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            var first = true
            connection.url.collect { stored ->
                mutableState.update { it.copy(storedUrl = stored, url = if (first || !it.urlChanged) stored else it.url) }
                first = false
            }
        }
        viewModelScope.launch {
            connection.state.collect { server -> mutableState.update { it.copy(connection = server) } }
        }
    }

    /** The screen is shown again, possibly after Telegram: ask for the decision. */
    fun onResume() {
        when (connection.state.value) {
            ServerState.Off, ServerState.NotConfigured, ServerState.Disconnected, ServerState.Connecting -> return
            else -> viewModelScope.launch { connection.refresh() }
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            connection.setEnabled(enabled)
            if (enabled) connectNow()
        }
    }

    fun onUrlChange(url: String) {
        mutableState.update { it.copy(url = url, urlError = false) }
    }

    /** Saves the URL; an empty field removes the server. */
    fun onSaveUrl() {
        val url = mutableState.value.url.trim().trimEnd('/')
        if (url.isNotEmpty() && !isValidBaseUrl(url)) {
            mutableState.update { it.copy(urlError = true) }
            return
        }
        mutableState.update { it.copy(url = url, urlError = false) }
        viewModelScope.launch {
            connection.setUrl(url)
            connectNow()
        }
    }

    /** "Connect" and "Retry" buttons. */
    fun onConnect() {
        viewModelScope.launch {
            connection.connect()?.let { linkChannel.send(it) }
        }
    }

    /** Asks for access when the server is on, has a URL and is not linked yet. */
    private suspend fun connectNow() {
        if (connection.state.value !is ServerState.Disconnected) return
        connection.connect()?.let { linkChannel.send(it) }
    }

    fun onOpenTelegram() {
        val awaiting = connection.state.value as? ServerState.AwaitingTelegram ?: return
        linkChannel.trySend(awaiting.linkUrl)
    }

    fun onDisconnect() {
        viewModelScope.launch { connection.disconnect() }
    }

    private companion object {
        fun isValidBaseUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.host.isNotEmpty() && parsed.query == null && parsed.fragment == null
        }
    }
}
