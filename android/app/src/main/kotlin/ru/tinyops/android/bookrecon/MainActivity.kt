package ru.tinyops.android.bookrecon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.tinyops.android.bookrecon.download.DownloadEffects
import ru.tinyops.android.bookrecon.ui.nav.BookReconNavHost
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BookReconTheme {
                DownloadEffects()
                BookReconNavHost()
            }
        }
    }
}
