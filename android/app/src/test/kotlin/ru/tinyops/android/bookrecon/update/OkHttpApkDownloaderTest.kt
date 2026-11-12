package ru.tinyops.android.bookrecon.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.update.ApkAsset
import java.io.File
import java.security.MessageDigest

class OkHttpApkDownloaderTest {
    private val server = MockWebServer()

    @TempDir
    lateinit var temp: File

    private val dir get() = File(temp, "updates")
    private val downloader get() = OkHttpApkDownloader(OkHttpClient(), dir)

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun asset(size: Long = APK.size.toLong(), sha256: String? = SHA256) =
        ApkAsset("book-recon-1.0.0.apk", server.url("/book-recon-1.0.0.apk").toString(), size, sha256)

    @Test
    fun downloadsVerifiesAndReportsProgress() = runBlocking {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(APK)).build())
        File(dir, "old.apk").apply { parentFile.mkdirs() }.writeText("left from the last update")
        val progress = mutableListOf<Long>()

        val file = downloader.download(asset()) { progress += it }

        assertArrayEquals(APK, file.readBytes())
        assertEquals(APK.size.toLong(), progress.last())
        assertFalse(File(dir, "old.apk").exists(), "the folder holds one apk at a time")
    }

    @Test
    fun aMissingDigestIsNotRequired() = runBlocking {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(APK)).build())
        assertArrayEquals(APK, downloader.download(asset(sha256 = null)) {}.readBytes())
    }

    @Test
    fun aWrongDigestIsRejectedAndTheFileRemoved() {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(APK)).build())
        assertThrows<ApkCorrupted> { runBlocking { downloader.download(asset(sha256 = "0".repeat(64))) {} } }
        assertFalse(File(dir, "update.apk").exists())
    }

    @Test
    fun aWrongSizeIsRejected() {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(APK)).build())
        assertThrows<ApkCorrupted> { runBlocking { downloader.download(asset(size = APK.size + 1L)) {} } }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(APK)).build())
        assertThrows<ApkCorrupted> { runBlocking { downloader.download(asset(size = APK.size - 1L)) {} } }
    }

    @Test
    fun anErrorStatusIsServiceDown() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThrows<AppError.ServiceDown> { runBlocking { downloader.download(asset()) {} } }
    }

    @Test
    fun aDroppedConnectionIsANetworkError() {
        val unreachable = ApkAsset("a.apk", "http://127.0.0.1:1/a.apk", 1, null)
        assertThrows<AppError.Network> { runBlocking { downloader.download(unreachable) {} } }
    }

    private companion object {
        val APK = ByteArray(200_000) { (it % 251).toByte() }
        val SHA256: String = MessageDigest.getInstance("SHA-256").digest(APK).joinToString("") { "%02x".format(it) }
    }
}
