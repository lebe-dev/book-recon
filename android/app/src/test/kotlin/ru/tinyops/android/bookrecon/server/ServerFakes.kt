package ru.tinyops.android.bookrecon.server

/** Server API whose answers the test sets; tokens are "token1", "token2"... */
internal class FakeServerApi : ServerApi {
    var status = StatusResponse(ClientStatus.UNLINKED)
    var failure: ServerError? = null
    var registered = 0
    var statusCalls = 0
    val deleted = mutableListOf<String>()
    var knownTokens = mutableSetOf<String>()
    var linkExpires = "2026-11-10T10:15:00Z"

    private fun check() {
        failure?.let { throw it }
    }

    override suspend fun ping(baseUrl: String) = check()

    override suspend fun register(baseUrl: String, deviceName: String): String {
        check()
        registered++
        return "token$registered".also { knownTokens += it }
    }

    override suspend fun status(baseUrl: String, token: String): StatusResponse {
        check()
        statusCalls++
        if (token !in knownTokens) throw ServerError.Unauthorized()
        return status
    }

    override suspend fun link(baseUrl: String, token: String): LinkResponse {
        check()
        return LinkResponse("https://t.me/bot?start=link_$token", linkExpires)
    }

    override suspend fun delete(baseUrl: String, token: String) {
        check()
        deleted += token
        knownTokens -= token
    }
}

internal class MemoryServerStore(var server: StoredServer = StoredServer(enabled = true)) : ServerStore {
    override suspend fun load() = server

    override suspend fun save(server: StoredServer) {
        this.server = server
    }
}
