package ru.tinyops.android.bookrecon.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl

/**
 * Passes a Cloudflare challenge in a WebView that is never shown: the challenge script runs
 * there just as in a browser and, unless Cloudflare asks for a click, sets `cf_clearance` in
 * the shared cookie store within a few seconds.
 */
class WebViewChallengeSolver(
    private val context: Context,
    private val cookies: CookieStore,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : ChallengeSolver {

    private val main = Handler(Looper.getMainLooper())

    override fun solve(url: HttpUrl): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "cloudflare check must not run on the main thread" }
        val target = url.toString()
        val before = cookieValue(cookies.cookieHeader(target), CF_CLEARANCE)
        val passed = AtomicBoolean(false)
        val done = CountDownLatch(1)

        Log.i(TAG, "Starting a background Cloudflare check for ${url.host}")
        main.post { Attempt(target, before, passed, done).start() }
        // The attempt ends itself on its deadline; the margin covers a busy main thread.
        val finished = done.await(timeout.inWholeMilliseconds + MAIN_THREAD_MARGIN_MS, TimeUnit.MILLISECONDS)
        if (passed.get()) {
            Log.i(TAG, "Background Cloudflare check passed for ${url.host}")
            return true
        }
        Log.w(TAG, "Background Cloudflare check failed for ${url.host} (finished: $finished)")
        return false
    }

    /** One check; lives on the main thread from [start] until [finish]. */
    private inner class Attempt(
        private val url: String,
        private val before: String?,
        private val passed: AtomicBoolean,
        private val done: CountDownLatch,
    ) {
        private var webView: WebView? = null
        private val deadline = SystemClock.uptimeMillis() + timeout.inWholeMilliseconds

        private val poll = object : Runnable {
            override fun run() {
                if (isFreshCookie(before, cookieValue(cookies.cookieHeader(url), CF_CLEARANCE))) {
                    passed.set(true)
                    finish()
                    return
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    finish()
                    return
                }
                main.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun start() {
            val view = try {
                WebView(context)
            } catch (e: RuntimeException) {
                // WebView is missing or being updated; the user can still pass the check by hand.
                Log.w(TAG, "WebView is not available for the Cloudflare check", e)
                done.countDown()
                return
            }
            webView = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = WebViewClient()
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.loadUrl(url)
            main.postDelayed(poll, POLL_INTERVAL_MS)
        }

        private fun finish() {
            main.removeCallbacks(poll)
            webView?.run {
                stopLoading()
                destroy()
            }
            webView = null
            cookies.flush()
            done.countDown()
        }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
        private const val POLL_INTERVAL_MS = 500L
        private const val MAIN_THREAD_MARGIN_MS = 5_000L
        private const val TAG = "CloudflareCheck"
    }
}
