package ru.tinyops.android.bookrecon.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import ru.tinyops.android.bookrecon.di.appContainer

/**
 * Hears about a finished boot and makes sure downloads and seeding continue without the app
 * being opened. Not exported: the system delivers its own broadcasts to such receivers.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.appContainer.log.appLog.info(TAG, "Device booted, checking downloads and seeding")
        val pending = goAsync()
        val module = context.appContainer.download
        module.scope.launch {
            try {
                module.onBoot()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
