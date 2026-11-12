package ru.tinyops.android.bookrecon.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.tinyops.android.bookrecon.di.appContainer
import java.io.File
import java.io.IOException

/**
 * Installs an APK through a [PackageInstaller] session. The system asks the user to confirm
 * ([PackageInstaller.STATUS_PENDING_USER_ACTION]) and then answers [InstallResultReceiver].
 * Android accepts the update only when it is signed with the key of the installed app.
 */
class PackageInstallerApkInstaller(private val context: Context) : ApkInstaller {

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(SPLIT_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(resultSender(sessionId))
            }
        } catch (e: IOException) {
            installer.abandonSession(sessionId)
            throw e
        } catch (e: SecurityException) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    /** Mutable: the system adds the status extras to the intent. */
    private fun resultSender(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender

    private companion object {
        const val SPLIT_NAME = "base.apk"
    }
}

/** Receives the answers of the system installer for [PackageInstallerApkInstaller]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val container = context.appContainer
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
            container.log.appLog.info(TAG, "Asking the user to confirm the update")
            container.updates.pendingConfirmation = confirm
            container.updates.openConfirmation(context)
            return
        }
        container.updates.pendingConfirmation = null
        val result = installResultOf(status) ?: return
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        container.log.appLog.info(TAG, "The system installer finished with status $status $message".trim())
        container.updates.updater.onInstallResult(result)
    }

    private companion object {
        const val TAG = "Update"
    }
}

/** The result of a final installer [status]; null for success, after which the app is replaced. */
internal fun installResultOf(status: Int): InstallResult? = when (status) {
    PackageInstaller.STATUS_SUCCESS -> null
    PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.ABORTED
    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallResult.CONFLICT
    PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.STORAGE
    else -> InstallResult.FAILED
}
