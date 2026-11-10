package ru.tinyops.android.bookrecon.net

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Passes a Cloudflare challenge for [url] and stores the `cf_clearance` cookie in the shared
 * cookie store. Blocks the calling thread; never called on the main thread. Returns false
 * when the check was not passed in time.
 */
fun interface ChallengeSolver {
    fun solve(url: HttpUrl): Boolean
}

/** Thrown through OkHttp when a challenge was not solved; the client turns it into AppError. */
class CloudflareChallengeException(val url: String) : IOException("cloudflare challenge was not solved for $url")

/** Tells a Cloudflare challenge page from an ordinary response. */
object CloudflareDetector {
    private const val PEEK_BYTES = 32L * 1024
    private val MARKERS = listOf("Just a moment", "challenge-platform", "cf-chl-")

    fun isChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
        val head = try {
            response.peekBody(PEEK_BYTES).string()
        } catch (_: IOException) {
            return false
        }
        return MARKERS.any { head.contains(it, ignoreCase = true) }
    }
}

/**
 * Detects a Cloudflare challenge, asks [solver] to pass it and repeats the request once with the
 * fresh clearance. A request that still meets a challenge fails with
 * [CloudflareChallengeException], so the UI can let the user pass the check by hand.
 *
 * Requests to one host that hit a challenge wait for a single check: each request remembers
 * the check round it started in, and when a check finished while it waited, it takes that
 * outcome instead of starting another one. Must be an application interceptor, so the repeated
 * request picks up the new cookie from the cookie jar.
 */
class CloudflareInterceptor(private val solver: ChallengeSolver) : Interceptor {

    private class HostState {
        val lock = ReentrantLock()

        @Volatile
        var round = 0

        @Volatile
        var lastPassed = false
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val state = hosts.computeIfAbsent(request.url.host) { HostState() }
        val round = state.round

        val response = chain.proceed(request)
        if (!CloudflareDetector.isChallenge(response)) return response
        val challengeUrl = response.request.url
        response.close()

        if (!awaitCheck(chain.call(), challengeUrl, state, round)) {
            throw CloudflareChallengeException(challengeUrl.toString())
        }

        val retried = chain.proceed(request)
        if (!CloudflareDetector.isChallenge(retried)) return retried
        retried.close()
        throw CloudflareChallengeException(retried.request.url.toString())
    }

    private fun awaitCheck(call: Call, url: HttpUrl, state: HostState, round: Int): Boolean {
        try {
            state.lock.lockInterruptibly()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while waiting for a cloudflare check").apply { initCause(e) }
        }
        try {
            if (state.round != round) return state.lastPassed
            if (call.isCanceled()) throw IOException("Canceled")
            val passed = try {
                solver.solve(url)
            } catch (_: RuntimeException) {
                false
            }
            state.lastPassed = passed
            state.round = round + 1
            return passed
        } finally {
            state.lock.unlock()
        }
    }
}
