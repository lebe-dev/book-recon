package ru.tinyops.android.bookrecon.di

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.tinyops.android.bookrecon.BuildConfig
import ru.tinyops.android.bookrecon.core.update.AppVersion
import ru.tinyops.android.bookrecon.core.update.UpdateChecker
import ru.tinyops.android.bookrecon.log.LoggingInterceptor
import ru.tinyops.android.bookrecon.net.OkHttpHttpClient
import ru.tinyops.android.bookrecon.update.AppUpdater
import ru.tinyops.android.bookrecon.update.OkHttpApkDownloader
import ru.tinyops.android.bookrecon.update.PackageInstallerApkInstaller

/** App updates from GitHub Releases. */
class UpdateModule(private val app: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** GitHub is reached directly: it is neither blocked nor behind Cloudflare, so no tunnel and no WebView checks. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor(app.log.appLog))
            .build()
    }

    private val downloader by lazy { OkHttpApkDownloader(client, File(app.context.cacheDir, UPDATES_FOLDER)) }

    val currentVersion: AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0)

    val updater: AppUpdater by lazy {
        val checker = UpdateChecker(OkHttpHttpClient(client), currentVersion, REPOSITORY)
        AppUpdater(
            fetchLatest = checker::check,
            downloader = downloader,
            installer = PackageInstallerApkInstaller(app.context),
            settings = app.settings.settingsRepository,
            scope = scope,
            info = { app.log.appLog.info(TAG, it) },
            warn = { message, error -> app.log.appLog.warn(TAG, message, error) },
        )
    }

    /**
     * The system screen that confirms the running install. Android may refuse to open it while
     * the app is in the background, so the updates page can open it again.
     */
    @Volatile
    var pendingConfirmation: Intent? = null

    /** Opens [pendingConfirmation]; false when there is none or the system refused. */
    fun openConfirmation(context: Context): Boolean {
        val confirm = pendingConfirmation ?: return false
        return try {
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            app.log.appLog.warn(TAG, "Cannot open the install confirmation", e)
            false
        }
    }

    /** Called once from Application.onCreate: the APK of an installed update is not needed any more. */
    fun start() {
        scope.launch(Dispatchers.IO) { downloader.clear() }
    }

    companion object {
        const val REPOSITORY = "lebe-dev/book-recon"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
        private const val TAG = "Update"
        private const val UPDATES_FOLDER = "updates"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
