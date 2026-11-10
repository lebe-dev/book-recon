package ru.tinyops.android.bookrecon.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exactly one launcher entry is enabled, and its label is the chosen name. */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 fails to set up SDK 36+ on JDK 21 (ApplicationSharedMemory), so pin 35.
@Config(sdk = [35])
class LauncherNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageManager = context.packageManager

    private fun stateOf(className: String) =
        packageManager.getComponentEnabledSetting(ComponentName(context, className))

    @Test
    fun cyrillicNameEnablesOnlyTheCyrillicEntry() {
        LauncherName.apply(context, cyrillic = true)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf(LauncherName.CYRILLIC_ENTRY))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf(LauncherName.DEFAULT_ENTRY))
    }

    @Test
    fun latinNameEnablesOnlyTheDefaultEntry() {
        LauncherName.apply(context, cyrillic = true)
        LauncherName.apply(context, cyrillic = false)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf(LauncherName.DEFAULT_ENTRY))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf(LauncherName.CYRILLIC_ENTRY))
    }

    @Test
    fun applyingTheSameNameTwiceChangesNothing() {
        LauncherName.apply(context, cyrillic = true)
        LauncherName.apply(context, cyrillic = true)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf(LauncherName.CYRILLIC_ENTRY))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf(LauncherName.DEFAULT_ENTRY))
    }

    @Test
    fun entriesAreDeclaredInTheManifest() {
        val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS)
        val names = info.activities.orEmpty().map { it.name }
        assertEquals(true, LauncherName.DEFAULT_ENTRY in names)
        assertEquals(true, LauncherName.CYRILLIC_ENTRY in names)
    }
}
