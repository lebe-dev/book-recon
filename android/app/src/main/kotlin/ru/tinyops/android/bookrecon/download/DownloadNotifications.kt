package ru.tinyops.android.bookrecon.download

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.tinyops.android.bookrecon.MainActivity
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ErrorCode

/** The "Загрузки" channel: the ongoing progress notification and the result of each download. */
object DownloadNotifications {
    const val CHANNEL_ID = "downloads"

    /** ID of the ongoing notification attached to the download job. */
    const val PROGRESS_ID = 1

    /** Broadcast sent by the "Отменить" button; handled while the download job runs. */
    const val ACTION_CANCEL = "ru.tinyops.android.bookrecon.action.CANCEL_DOWNLOAD"
    const val EXTRA_DOWNLOAD_ID = "download_id"

    private const val RESULT_ID_BASE = 1000
    private const val PERCENT_MAX = 100

    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.notif_channel_downloads))
            .setDescription(context.getString(R.string.notif_channel_downloads_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** The ongoing notification: the followed download with its progress and a cancel button. */
    fun progress(context: Context, summary: QueueSummary): Notification {
        val current = summary.current
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(current?.title ?: context.getString(R.string.notif_progress_title))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val details = buildList {
            if (current == null) add(context.getString(R.string.notif_progress_waiting))
            summary.percent?.let { add(context.getString(R.string.notif_progress_percent, it)) }
            if (summary.running > 1) add(context.getString(R.string.notif_progress_more_running, summary.running - 1))
            if (summary.queued > 0) add(context.getString(R.string.notif_progress_queued, summary.queued))
        }
        builder.setContentText(details.joinToString(" · "))
        if (current == null) return builder.build()
        builder.setProgress(PERCENT_MAX, summary.percent ?: 0, summary.percent == null)
        builder.addAction(0, context.getString(R.string.notif_action_cancel), cancelIntent(context, current.id))
        return builder.build()
    }

    /** Tells that a download completed or failed; skipped without the notification permission. */
    fun showResult(context: Context, event: DownloadEvent) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        when (event) {
            is DownloadEvent.Completed -> builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.notif_done_title))
                .setContentText(event.download.title)
            is DownloadEvent.Failed -> builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.notif_failed_title))
                .setContentText(
                    context.getString(
                        R.string.notif_result_text,
                        event.download.title,
                        context.getString(event.error.downloadMessage()),
                    ),
                )
        }
        NotificationManagerCompat.from(context).notify(resultId(event.download.id), builder.build())
    }

    private fun resultId(downloadId: String): Int = RESULT_ID_BASE + (downloadId.hashCode() and 0xFFFFFF)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(context: Context, downloadId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        downloadId.hashCode(),
        Intent(ACTION_CANCEL).setPackage(context.packageName).putExtra(EXTRA_DOWNLOAD_ID, downloadId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Short Russian description of why a download failed, for notifications and the downloads screen. */
@StringRes
fun AppError.downloadMessage(): Int = when (code) {
    ErrorCode.NETWORK -> R.string.download_error_network
    ErrorCode.SERVICE_DOWN -> R.string.download_error_service_down
    ErrorCode.TIMEOUT -> R.string.download_error_timeout
    ErrorCode.NOT_FOUND -> R.string.download_error_not_found
    ErrorCode.PARSE_ERROR -> R.string.download_error_parse
    ErrorCode.FORMAT_NOT_AVAILABLE -> R.string.download_error_format
    ErrorCode.BOOK_UNAVAILABLE -> R.string.download_error_book_unavailable
    ErrorCode.CLOUDFLARE_CHALLENGE -> R.string.download_error_cloudflare
    ErrorCode.RUTRACKER_AUTH_REQUIRED -> R.string.download_error_auth_required
    ErrorCode.RUTRACKER_LOGIN_FAILED -> R.string.download_error_login_failed
    ErrorCode.RUTRACKER_CAPTCHA -> R.string.download_error_captcha
    ErrorCode.TORRENT_TOO_LARGE -> R.string.download_error_torrent_too_large
    ErrorCode.NO_SEEDERS -> R.string.download_error_no_seeders
    ErrorCode.STALLED -> R.string.download_error_stalled
    ErrorCode.ALREADY_DOWNLOADING -> R.string.download_error_already
    ErrorCode.SELECTION_EMPTY -> R.string.download_error_selection_empty
    ErrorCode.NO_MATCHING_FILES -> R.string.download_error_no_matching_files
    ErrorCode.STORAGE_NOT_GRANTED -> R.string.download_error_storage_not_granted
    ErrorCode.ROOT_NOT_CONFIGURED -> R.string.download_error_root_not_configured
    ErrorCode.STORAGE_FAILED -> R.string.download_error_storage_failed
    ErrorCode.INTERRUPTED -> R.string.download_error_interrupted
    ErrorCode.VALIDATION, ErrorCode.UNKNOWN -> R.string.download_error_unknown
}
