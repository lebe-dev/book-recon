package ru.tinyops.android.bookrecon.download

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import ru.tinyops.android.bookrecon.di.appContainer

/**
 * Download housekeeping tied to the app being on screen: brings back downloads interrupted by
 * a process death and schedules the job for them (the system allows that only while the app
 * is visible), and asks for the notification permission when the first download is queued.
 */
@Composable
fun DownloadEffects() {
    val context = LocalContext.current
    val module = context.appContainer.download
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TAG, "Notification permission granted: $granted")
    }

    LaunchedEffect(Unit) { module.onAppVisible() }

    LaunchedEffect(asked) {
        if (asked) return@LaunchedEffect
        module.queue.observe().first { list -> list.any { it.status.isActive } }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        asked = true
        launcher.launch(permission)
    }
}

private const val TAG = "DownloadEffects"
