package ru.tinyops.android.bookrecon.torrent

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Points libtorrent4j at the host native library from the desktop jars on the test
 * classpath. Tests that need real libtorrent call [assumeAvailable] and are skipped on hosts
 * without a matching library.
 */
object HostLibTorrent {
    private const val PROPERTY = "libtorrent4j.jni.path"

    private val available: Boolean by lazy { load() }

    fun assumeAvailable() {
        assumeTrue(available, "no libtorrent4j native library for this host")
    }

    private fun load(): Boolean {
        if (!System.getProperty(PROPERTY).isNullOrEmpty()) return true
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val resource = when {
            "mac" in os && arch in setOf("aarch64", "arm64") -> "lib/arm64/libtorrent4j.dylib"
            "linux" in os && arch in setOf("amd64", "x86_64") -> "lib/x86_64/libtorrent4j.so"
            else -> return false
        }
        val input = javaClass.classLoader?.getResourceAsStream(resource) ?: return false
        val target = File.createTempFile("libtorrent4j", resource.substringAfterLast('/'))
        target.deleteOnExit()
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        System.setProperty(PROPERTY, target.absolutePath)
        return true
    }
}
