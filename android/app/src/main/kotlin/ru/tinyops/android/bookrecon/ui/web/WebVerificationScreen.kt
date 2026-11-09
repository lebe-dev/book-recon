package ru.tinyops.android.bookrecon.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.net.BB_SESSION
import ru.tinyops.android.bookrecon.net.CF_CLEARANCE
import ru.tinyops.android.bookrecon.net.cookieValue
import ru.tinyops.android.bookrecon.net.isFreshCookie
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

private const val POLL_INTERVAL_MS = 500L

/** Cookie whose arrival means the check behind [VerificationPurpose] is passed. */
private val VerificationPurpose.cookieName: String
    get() = when (this) {
        VerificationPurpose.CLOUDFLARE -> CF_CLEARANCE
        VerificationPurpose.RUTRACKER_LOGIN -> BB_SESSION
    }

/**
 * Shows [url] in a WebView so the user passes a Cloudflare check or logs in to RuTracker by
 * hand. The page shares the cookie store with the HTTP client, so once the expected cookie
 * appears the screen closes with true; «Отмена» and Back close it with false.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebVerificationScreen(
    url: String,
    purpose: VerificationPurpose,
    onDone: (passed: Boolean) -> Unit,
) {
    val cookieName = purpose.cookieName
    // Kept across rotation: a cookie that was already there before the screen opened is stale.
    val before = rememberSaveable(url, purpose) {
        cookieValue(CookieManager.getInstance().getCookie(url), cookieName).orEmpty()
    }
    var finished by remember { mutableStateOf(false) }
    val currentOnDone by rememberUpdatedState(onDone)
    val finish: (Boolean) -> Unit = { passed ->
        if (!finished) {
            finished = true
            CookieManager.getInstance().flush()
            currentOnDone(passed)
        }
    }

    LaunchedEffect(url, cookieName) {
        while (!finished) {
            delay(POLL_INTERVAL_MS)
            val now = cookieValue(CookieManager.getInstance().getCookie(url), cookieName)
            if (isFreshCookie(before.ifEmpty { null }, now)) finish(true)
        }
    }
    BackHandler { finish(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(purpose.titleRes())) },
                actions = {
                    TextButton(onClick = { finish(false) }) { Text(stringResource(R.string.web_cancel)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(purpose.hintRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            VerificationWebView(url, Modifier.fillMaxSize())
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VerificationWebView(url: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun VerificationPurpose.titleRes(): Int = when (this) {
    VerificationPurpose.CLOUDFLARE -> R.string.web_title_cloudflare
    VerificationPurpose.RUTRACKER_LOGIN -> R.string.web_title_login
}

private fun VerificationPurpose.hintRes(): Int = when (this) {
    VerificationPurpose.CLOUDFLARE -> R.string.web_hint_cloudflare
    VerificationPurpose.RUTRACKER_LOGIN -> R.string.web_hint_login
}
