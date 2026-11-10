package ru.tinyops.android.bookrecon.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Cookie Cloudflare sets once a challenge is passed; bound to the User-Agent and IP. */
const val CF_CLEARANCE = "cf_clearance"

/** RuTracker session cookie, set by a successful login. */
const val BB_SESSION = "bb_session"

/**
 * Cookie storage shared by OkHttp and WebView. In the app it is the WebView cookie manager, so
 * a cookie set by a page in a WebView is sent by OkHttp, and the other way round.
 */
interface CookieStore {
    /** The `Cookie` header the store would send to [url], or null when there is none. */
    fun cookieHeader(url: String): String?

    /** Stores a cookie given as a `Set-Cookie` header value received from [url]. */
    fun save(url: String, setCookie: String)

    /** Writes the cookies to disk, so they survive a process restart. */
    fun flush()
}

/** [CookieStore] over [CookieManager], the store every WebView in the process uses. */
class WebkitCookieStore : CookieStore {
    private val manager: CookieManager
        get() = CookieManager.getInstance()

    override fun cookieHeader(url: String): String? = manager.getCookie(url)

    override fun save(url: String, setCookie: String) {
        manager.setCookie(url, setCookie)
    }

    override fun flush() {
        manager.flush()
    }
}

/** OkHttp cookie jar that keeps nothing itself and reads and writes [store]. */
class SharedCookieJar(private val store: CookieStore) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val target = url.toString()
        cookies.forEach { store.save(target, it.toString()) }
        store.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = store.cookieHeader(url.toString())
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { pair ->
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            Cookie.parse(url, trimmed)
        }
    }
}

/** Value of the cookie [name] in a `Cookie` header such as `a=1; b=2`, or null. */
fun cookieValue(header: String?, name: String): String? {
    if (header.isNullOrEmpty()) return null
    return header.split(';')
        .map { it.trim() }
        .firstOrNull { it.substringBefore('=') == name && it.contains('=') }
        ?.substringAfter('=')
}

/**
 * True when a cookie appeared or changed since [before] was read. A cookie that was already
 * there is not proof of a passed check: it may be the stale one the site just rejected.
 */
fun isFreshCookie(before: String?, now: String?): Boolean = !now.isNullOrEmpty() && now != before
