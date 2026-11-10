package ru.tinyops.android.bookrecon.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Name under the launcher icon. Android has no call to rename an app, so the manifest declares
 * two launcher entries (activity aliases) with different labels, and this switches which one
 * is enabled. The launcher search matches the label of the enabled entry.
 */
object LauncherName {
    const val DEFAULT_ENTRY = "ru.tinyops.android.bookrecon.LauncherDefaultAlias"
    const val CYRILLIC_ENTRY = "ru.tinyops.android.bookrecon.LauncherCyrillicAlias"

    /**
     * Shows "Бук Ракун" under the icon when [cyrillic] is true, "Book Recon" otherwise. The app
     * keeps running; some launchers drop an icon pinned to the home screen and need it added
     * again.
     */
    fun apply(context: Context, cyrillic: Boolean) {
        val packageManager = context.packageManager
        // The new entry goes first, so the app never has a moment without a launcher icon.
        setEnabled(packageManager, ComponentName(context, if (cyrillic) CYRILLIC_ENTRY else DEFAULT_ENTRY), true)
        setEnabled(packageManager, ComponentName(context, if (cyrillic) DEFAULT_ENTRY else CYRILLIC_ENTRY), false)
    }

    private fun setEnabled(packageManager: PackageManager, component: ComponentName, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (packageManager.getComponentEnabledSetting(component) == state) return
        packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
