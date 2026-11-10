package ru.tinyops.android.bookrecon.torrent

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** Minimal bencode writer for generating test torrents. */
object TestBencode {
    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Int -> out.write("i${value}e".toByteArray())
            is Long -> out.write("i${value}e".toByteArray())
            is String -> writeBytes(out, value.toByteArray(Charsets.UTF_8))
            is ByteArray -> writeBytes(out, value)
            is List<*> -> {
                out.write('l'.code)
                value.forEach { write(out, it!!) }
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                value.entries.sortedBy { it.key as String }.forEach { (key, item) ->
                    writeBytes(out, (key as String).toByteArray(Charsets.UTF_8))
                    write(out, item!!)
                }
                out.write('e'.code)
            }
            else -> error("cannot bencode ${value::class}")
        }
    }

    private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write("${bytes.size}:".toByteArray())
        out.write(bytes)
    }
}

/**
 * A file of a generated torrent.
 *
 * @property path path components below the torrent root, written as `path`.
 * @property attr BEP 47 attributes, `p` marks a padding file.
 * @property extra additional keys of the file dictionary, for example `path.utf-8`.
 */
class TestFile(
    val path: List<Any>,
    val data: ByteArray,
    val attr: String? = null,
    val extra: Map<String, Any> = emptyMap(),
) {
    constructor(path: String, size: Int, seed: Int = path.hashCode()) :
        this(path.split('/'), Random(seed).nextBytes(size))
}

/** Generated multi-file torrent with real piece hashes, so it can be seeded. */
class TestTorrent(
    val name: String,
    val files: List<TestFile>,
    val pieceLength: Int = 16 * 1024,
    private val infoExtra: Map<String, Any> = emptyMap(),
    private val announce: String = "http://127.0.0.1:1/announce?passkey=secret",
) {
    val infoBytes: ByteArray by lazy { TestBencode.encode(info()) }

    val bytes: ByteArray by lazy {
        TestBencode.encode(mapOf("announce" to announce, "info" to info()))
    }

    val infoHash: String by lazy {
        MessageDigest.getInstance("SHA-1").digest(infoBytes).joinToString("") { "%02x".format(it) }
    }

    private fun info(): Map<String, Any> {
        val all = files.fold(ByteArrayOutputStream()) { out, file -> out.also { it.write(file.data) } }.toByteArray()
        val pieces = ByteArrayOutputStream()
        val sha = MessageDigest.getInstance("SHA-1")
        var offset = 0
        while (offset < all.size) {
            val end = minOf(offset + pieceLength, all.size)
            pieces.write(sha.digest(all.copyOfRange(offset, end)))
            offset = end
        }
        val fileList = files.map { file ->
            buildMap<String, Any> {
                put("length", file.data.size.toLong())
                put("path", file.path)
                file.attr?.let { put("attr", it) }
                putAll(file.extra)
            }
        }
        return mapOf(
            "name" to name,
            "piece length" to pieceLength,
            "pieces" to pieces.toByteArray(),
            "files" to fileList,
        ) + infoExtra
    }

    /** Writes the payload under `<dir>/<name>/`, the layout a seeder needs. */
    fun writeTo(dir: File) {
        files.filter { it.attr?.contains('p') != true }.forEach { file ->
            val target = File(dir, (listOf(name) + file.path.map { it.toString() }).joinToString("/"))
            target.parentFile?.mkdirs()
            target.writeBytes(file.data)
        }
    }
}
