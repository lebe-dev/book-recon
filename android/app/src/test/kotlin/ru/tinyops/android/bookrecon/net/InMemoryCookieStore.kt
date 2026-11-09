package ru.tinyops.android.bookrecon.net

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Cookie store keyed by host, standing in for the WebView cookie manager in JVM tests. */
class InMemoryCookieStore : CookieStore {
    private val byHost = ConcurrentHashMap<String, MutableMap<String, String>>()

    @Volatile
    var flushes = 0
        private set

    override fun cookieHeader(url: String): String? {
        val cookies = byHost[url.toHttpUrl().host] ?: return null
        if (cookies.isEmpty()) return null
        return synchronized(cookies) { cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } }
    }

    override fun save(url: String, setCookie: String) {
        val httpUrl = url.toHttpUrl()
        val cookie = Cookie.parse(httpUrl, setCookie) ?: return
        val cookies = byHost.computeIfAbsent(httpUrl.host) { LinkedHashMap() }
        synchronized(cookies) {
            if (cookie.expiresAt < System.currentTimeMillis()) {
                cookies.remove(cookie.name)
            } else {
                cookies[cookie.name] = cookie.value
            }
        }
    }

    override fun flush() {
        flushes++
    }

    fun put(url: String, name: String, value: String) = save(url, "$name=$value; path=/")
}
