package ru.tinyops.android.bookrecon.torrent

import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Reads the file list of a `.torrent` without native code, so the UI can show it before the
 * torrent session starts.
 *
 * File indexes are positions in the info dictionary's `files` list, the same indexes
 * libtorrent uses for file priorities. Padding files and files with unsafe paths are left
 * out. Names in `path.utf-8` win over `path`; names that are not valid UTF-8 are read as
 * windows-1251, the encoding of old RuTracker torrents. Torrents without a v1 file list
 * (pure BitTorrent v2) are not supported.
 */
object TorrentParser {
    private const val PADDING_PREFIX = "_____padding_file_"
    private val legacyCharset: Charset = Charset.forName("windows-1251")

    fun parse(bytes: ByteArray): TorrentInfo {
        val root = try {
            BencodeDecoder.decode(bytes)
        } catch (e: BencodeException) {
            throw AppError.ParseError("invalid torrent file: ${e.message}", e)
        }
        val info = (root as? BValue.BDict)?.get("info") as? BValue.BDict
            ?: throw AppError.ParseError("torrent has no info dictionary")
        val infoHash = MessageDigest.getInstance("SHA-1")
            .apply { update(bytes, info.start, info.end - info.start) }
            .digest()
            .joinToString("") { "%02x".format(it) }
        val name = text(info, "name") ?: throw AppError.ParseError("torrent has no name")
        if (!isSafeComponent(name)) throw AppError.ParseError("torrent name is not a safe folder name")

        val files = info["files"]
        if (files == null) {
            val length = (info["length"] as? BValue.BInt)?.value
                ?: throw AppError.ParseError("torrent has neither a file list nor a length")
            if (length < 0) throw AppError.ParseError("negative file length")
            return TorrentInfo(infoHash, name, listOf(TorrentFile(0, name, length)))
        }
        if (files !is BValue.BList) throw AppError.ParseError("file list is not a list")

        val result = files.items.mapIndexedNotNull { index, item -> fileOf(index, item, name) }
        return TorrentInfo(infoHash, name, result)
    }

    private fun fileOf(index: Int, item: BValue, root: String): TorrentFile? {
        val dict = item as? BValue.BDict ?: throw AppError.ParseError("file entry is not a dictionary")
        val length = (dict["length"] as? BValue.BInt)?.value ?: throw AppError.ParseError("file has no length")
        if (length < 0) throw AppError.ParseError("negative file length")
        val attr = (dict["attr"] as? BValue.BBytes)?.let { String(it.bytes, Charsets.US_ASCII) }.orEmpty()
        if ('p' in attr) return null
        val components = path(dict, "path.utf-8") ?: path(dict, "path") ?: return null
        if (components.isEmpty() || !components.all(::isSafeComponent)) return null
        if (components.last().startsWith(PADDING_PREFIX)) return null
        return TorrentFile(index, (listOf(root) + components).joinToString("/"), length)
    }

    private fun path(dict: BValue.BDict, key: String): List<String>? {
        val list = dict[key] as? BValue.BList ?: return null
        return list.items.map { (it as? BValue.BBytes)?.let { bytes -> decode(bytes.bytes) } ?: return null }
    }

    private fun text(dict: BValue.BDict, key: String): String? {
        val value = dict["$key.utf-8"] ?: dict[key]
        return (value as? BValue.BBytes)?.let { decode(it.bytes) }
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, legacyCharset)
    }

    private fun isSafeComponent(component: String): Boolean =
        component.isNotEmpty() &&
            component != "." &&
            component != ".." &&
            component.none { it == '/' || it == '\\' || it == '\u0000' }
}
