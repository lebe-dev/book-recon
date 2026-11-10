package ru.tinyops.android.bookrecon

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.download.DownloadEffects
import ru.tinyops.android.bookrecon.ui.nav.BookReconNavHost
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme
import ru.tinyops.android.bookrecon.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsFlow = appContainer.settings.settingsRepository.settings
        setContent {
            // The first frame waits for the stored theme (one small DataStore read), so a
            // chosen theme does not flash the default one at start.
            val settings: Settings? by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val loaded = settings ?: return@setContent
            val dark = loaded.darkMode.isDark(isSystemInDarkTheme())
            SystemBarsFollow(dark)
            BookReconTheme(colorTheme = loaded.colorTheme, darkTheme = dark) {
                DownloadEffects()
                BookReconNavHost()
            }
        }
    }

    /** Status and navigation bar icons follow the app mode, which may differ from the system one. */
    @Composable
    private fun SystemBarsFollow(dark: Boolean) {
        LaunchedEffect(dark) {
            val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
    }
}
