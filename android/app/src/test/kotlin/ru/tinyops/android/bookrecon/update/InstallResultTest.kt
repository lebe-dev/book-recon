package ru.tinyops.android.bookrecon.update

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InstallResultTest {
    @Test
    fun mapsInstallerStatuses() {
        assertNull(installResultOf(PackageInstaller.STATUS_SUCCESS))
        assertEquals(InstallResult.ABORTED, installResultOf(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallResult.CONFLICT, installResultOf(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(InstallResult.CONFLICT, installResultOf(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(InstallResult.STORAGE, installResultOf(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(InstallResult.FAILED, installResultOf(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(InstallResult.FAILED, installResultOf(PackageInstaller.STATUS_FAILURE))
    }
}
