package ru.tinyops.android.bookrecon.server

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Connection of the app to its Book Recon server, as the settings screen shows it. */
sealed interface ServerState {
    /** The user has not turned the server on. */
    data object Off : ServerState

    /** No server URL. */
    data object NotConfigured : ServerState

    /** A URL is set, the installation is not linked to Telegram. */
    data object Disconnected : ServerState

    data object Connecting : ServerState

    /** The user has to open [linkUrl] in Telegram to link the installation. */
    data class AwaitingTelegram(val linkUrl: String, val expiresAt: Instant) : ServerState

    /** Linked; the admin has not decided yet. */
    data object Pending : ServerState

    /** The tunnel is on for [hosts]. */
    data class Approved(val hosts: Set<String>, val telegramUsername: String?) : ServerState

    data object Denied : ServerState

    data class Failed(val problem: ServerProblem) : ServerState
}

enum class ServerProblem { UNREACHABLE, NOT_BOOK_RECON, SERVER_ERROR }

/** Where the tunnel goes and which hosts it serves; present only while approved. */
data class TunnelRoute(val baseUrl: String, val token: String, val hosts: Set<String>) {
    /** True for one of [hosts] or a subdomain of one. */
    fun serves(host: String): Boolean = hostMatches(host, hosts)
}

/** True when [host] is one of [hosts] or a subdomain of one, ignoring case. */
fun hostMatches(host: String, hosts: Set<String>): Boolean {
    val h = host.lowercase().trimEnd('.')
    return hosts.any { h == it || h.endsWith(".$it") }
}

/**
 * Registers the installation on the server, links it to Telegram and follows the admin's
 * decision. Calls are serialized; while a decision is awaited the status is polled, often at
 * first and then rarely. The approved host list is cached so the tunnel works right after
 * start, before the server answers.
 */
class ServerConnection(
    private val api: ServerApi,
    private val store: ServerStore,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val fastPoll: Duration = 3.seconds,
    private val slowPoll: Duration = 60.seconds,
    private val fastPollWindow: Duration = 5.minutes,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var stored = StoredServer()
    private var poller: Job? = null

    private val mutableState = MutableStateFlow<ServerState>(ServerState.Off)
    val state: StateFlow<ServerState> = mutableState.asStateFlow()

    private val mutableUrl = MutableStateFlow("")
    val url: StateFlow<String> = mutableUrl.asStateFlow()

    private val mutableRoute = MutableStateFlow<TunnelRoute?>(null)
    val route: StateFlow<TunnelRoute?> = mutableRoute.asStateFlow()

    /** Loads the stored server and checks it; called once at app start. */
    suspend fun start() {
        mutex.withLock {
            stored = store.load()
            mutableUrl.value = stored.url
            val hosts = stored.approvedHosts
            when {
                !stored.enabled -> publish(ServerState.Off)
                stored.url.isEmpty() -> publish(ServerState.NotConfigured)
                stored.token == null -> publish(ServerState.Disconnected)
                hosts != null -> publish(ServerState.Approved(hosts, stored.telegramUsername))
                else -> publish(ServerState.Connecting)
            }
        }
        if (stored.enabled && stored.token != null) refresh()
    }

    /**
     * Turns the server on or off. Off removes the installation from the server, so the tunnel
     * stops and turning it on again asks the admin anew; the URL is kept.
     */
    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        if (enabled == stored.enabled) return@withLock
        if (!enabled) forgetRegistration()
        save(StoredServer(url = stored.url, enabled = enabled))
        publish(idleState())
    }

    /** Sets the server URL; a different URL forgets the old registration. */
    suspend fun setUrl(url: String) = mutex.withLock {
        val normalized = url.trim().trimEnd('/')
        if (normalized == stored.url) return@withLock
        forgetRegistration()
        save(StoredServer(url = normalized, enabled = stored.enabled))
        mutableUrl.value = normalized
        publish(idleState())
    }

    /**
     * Registers if needed and asks for access. Returns the Telegram link the user has to open,
     * or null when no link is needed (already decided, or the call failed).
     */
    suspend fun connect(): String? = mutex.withLock {
        if (!stored.enabled || stored.url.isEmpty()) return@withLock null
        publish(ServerState.Connecting)
        try {
            val token = stored.token ?: register()
            val status = try {
                api.status(stored.url, token)
            } catch (_: ServerError.Unauthorized) {
                // The server forgot this installation: register again.
                save(stored.copy(token = null, approvedHosts = null))
                api.status(stored.url, register())
            }
            if (status.status != ClientStatus.UNLINKED) {
                apply(status)
                return@withLock null
            }
            val link = api.link(stored.url, stored.token!!)
            publish(ServerState.AwaitingTelegram(link.url, parseExpiry(link.expiresAt)))
            link.url
        } catch (e: ServerError) {
            log("Connecting to the server failed: ${e.message}")
            publish(ServerState.Failed(e.problem()))
            null
        }
    }

    /** Asks the server for the current decision. */
    suspend fun refresh() = mutex.withLock {
        val token = stored.token
        if (!stored.enabled || token == null) return@withLock publish(idleState())
        try {
            apply(api.status(stored.url, token))
        } catch (_: ServerError.Unauthorized) {
            log("Server no longer knows this installation")
            save(stored.copy(token = null, approvedHosts = null, telegramUsername = null))
            publish(ServerState.Disconnected)
        } catch (e: ServerError) {
            log("Server status check failed: ${e.message}")
            // A server that is down for a moment must not turn an approved tunnel off.
            if (mutableState.value !is ServerState.Approved) publish(ServerState.Failed(e.problem()))
        }
    }

    /** Removes the installation from the server and forgets it here. */
    suspend fun disconnect() = mutex.withLock {
        forgetRegistration()
        save(StoredServer(url = stored.url, enabled = stored.enabled))
        publish(idleState())
    }

    /** State without a registration in progress or decided. */
    private fun idleState(): ServerState = when {
        !stored.enabled -> ServerState.Off
        stored.url.isEmpty() -> ServerState.NotConfigured
        else -> ServerState.Disconnected
    }

    private suspend fun register(): String {
        api.ping(stored.url)
        val token = api.register(stored.url, deviceName)
        save(stored.copy(token = token, approvedHosts = null, telegramUsername = null))
        log("Registered on the server")
        return token
    }

    private suspend fun apply(status: StatusResponse) {
        when (status.status) {
            ClientStatus.APPROVED -> {
                val hosts = status.tunnelHosts.map { it.lowercase() }.toSet()
                save(stored.copy(approvedHosts = hosts, telegramUsername = status.telegramUsername))
                publish(ServerState.Approved(hosts, status.telegramUsername))
            }
            ClientStatus.PENDING -> {
                save(stored.copy(approvedHosts = null, telegramUsername = status.telegramUsername))
                publish(ServerState.Pending)
            }
            ClientStatus.DENIED -> {
                save(stored.copy(approvedHosts = null))
                publish(ServerState.Denied)
            }
            ClientStatus.UNLINKED -> {
                save(stored.copy(approvedHosts = null, telegramUsername = null))
                val awaiting = mutableState.value as? ServerState.AwaitingTelegram
                publish(if (awaiting != null && now() < awaiting.expiresAt) awaiting else ServerState.Disconnected)
            }
        }
    }

    private suspend fun forgetRegistration() {
        val token = stored.token ?: return
        try {
            api.delete(stored.url, token)
        } catch (e: ServerError) {
            log("Could not remove the installation from the server: ${e.message}")
        }
    }

    private suspend fun save(server: StoredServer) {
        stored = server
        store.save(server)
    }

    /** Sets the state, the tunnel route and the poller to match. */
    private fun publish(state: ServerState) {
        mutableState.value = state
        val token = stored.token
        mutableRoute.value = if (state is ServerState.Approved && token != null) {
            TunnelRoute(stored.url, token, state.hosts)
        } else {
            null
        }
        if (state.awaitsDecision()) startPolling() else stopPolling()
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            var waited = Duration.ZERO
            while (true) {
                val interval = if (waited < fastPollWindow) fastPoll else slowPoll
                delay(interval)
                waited += interval
                refresh()
            }
        }
    }

    private fun stopPolling() {
        val job = poller ?: return
        poller = null
        // refresh() runs inside the poller: cancelling there would end it mid-call, harmlessly.
        job.cancel()
    }

    private fun parseExpiry(text: String): Instant = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        now().plusSeconds(DEFAULT_LINK_TTL_SECONDS)
    }

    private fun ServerState.awaitsDecision() = this is ServerState.AwaitingTelegram || this is ServerState.Pending

    private fun ServerError.problem(): ServerProblem = when (this) {
        is ServerError.Network -> ServerProblem.UNREACHABLE
        is ServerError.NotBookRecon -> ServerProblem.NOT_BOOK_RECON
        is ServerError.Http, is ServerError.Unauthorized -> ServerProblem.SERVER_ERROR
    }

    private companion object {
        const val DEFAULT_LINK_TTL_SECONDS = 15 * 60L
    }
}
