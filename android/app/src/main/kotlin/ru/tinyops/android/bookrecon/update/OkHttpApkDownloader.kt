package ru.tinyops.android.bookrecon.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.update.ApkAsset
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads APKs over [client] into [dir], one at a time: every download starts by clearing
 * the folder. The file is checked against the size and SHA-256 GitHub reports for the asset.
 */
class OkHttpApkDownloader(private val client: OkHttpClient, private val dir: File) : ApkDownloader {

    override suspend fun download(asset: ApkAsset, onProgress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        clear()
        if (!dir.mkdirs() && !dir.isDirectory) throw AppError.StorageFailed("cannot create the updates folder")
        val file = File(dir, FILE_NAME)
        val call = client.newCall(Request.Builder().url(asset.url).build())
        val digest = MessageDigest.getInstance("SHA-256")
        val total = try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw AppError.ServiceDown("apk download answered ${response.code}")
                response.body.byteStream().use { input ->
                    file.outputStream().use { output -> copy(input, output, digest, asset.size, onProgress) }
                }
            }
        } catch (e: FileNotFoundException) {
            throw AppError.StorageFailed("cannot write the apk", e)
        } catch (e: InterruptedIOException) {
            throw AppError.Timeout("apk download timed out", e)
        } catch (e: IOException) {
            throw AppError.Network("apk download failed", e)
        } finally {
            call.cancel()
        }
        verify(asset, total, digest.digest().toHex())
        file
    }

    override fun clear() {
        dir.deleteRecursively()
    }

    private suspend fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
        expected: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (expected > 0 && total > expected) throw ApkCorrupted("the apk is larger than $expected bytes")
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            onProgress(total)
        }
    }

    private fun verify(asset: ApkAsset, total: Long, sha256: String) {
        if (asset.size > 0 && total != asset.size) {
            clear()
            throw ApkCorrupted("the apk has $total bytes instead of ${asset.size}")
        }
        if (asset.sha256 != null && asset.sha256 != sha256) {
            clear()
            throw ApkCorrupted("the apk sha-256 does not match the release")
        }
    }

    private companion object {
        const val FILE_NAME = "update.apk"
        const val BUFFER_SIZE = 64 * 1024

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
