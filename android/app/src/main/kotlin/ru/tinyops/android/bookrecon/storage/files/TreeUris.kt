package ru.tinyops.android.bookrecon.storage.files

import java.io.ByteArrayOutputStream

/**
 * Reads a folder picked with the system folder picker (a document tree URI). The roots are
 * reached through document grants; only the external storage provider is supported, since
 * its document IDs are a parent ID, a slash and a name, which lets the app address a file
 * by its path. [pathOf] turns a tree into a plain path only to read the paths that
 * version 0.9.0 and older recorded.
 */
object TreeUris {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    const val DEFAULT_PRIMARY_ROOT = "/storage/emulated/0"

    private const val PRIMARY_VOLUME = "primary"
    private const val HOME_VOLUME = "home"
    private const val RAW_PREFIX = "raw:"
    private const val STORAGE_DIR = "/storage"
    private const val DOCUMENTS_DIR = "Documents"

    /**
     * Path of the folder [treeUri] points to, or null when the URI is not a folder on
     * shared storage. `primary:Audiobooks` maps to `<primaryRoot>/Audiobooks`,
     * `XXXX-XXXX:path` to `/storage/XXXX-XXXX/path`, `home:path` to `<primaryRoot>/Documents/path`.
     * [primaryRoot] is the primary volume of the current user.
     */
    fun pathOf(treeUri: String, primaryRoot: String = DEFAULT_PRIMARY_ROOT): String? {
        val tree = parseTree(treeUri) ?: return null
        val documentId = decode(tree.encodedDocumentId) ?: return null
        return when (tree.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> externalStoragePath(documentId, primaryRoot)
            DOWNLOADS_AUTHORITY -> rawPath(documentId)
            else -> null
        }
    }

    /** True for a folder, not a whole volume, of the external storage provider. */
    fun isSupported(treeUri: String): Boolean = label(treeUri) != null

    /**
     * Short human-readable name of the picked folder: `Books/Fiction` on the primary volume,
     * `Documents/Notes` for the documents folder, `1234-ABCD:/Audio` on a card. Null for
     * other providers and for the root of a volume.
     */
    fun label(treeUri: String): String? {
        val tree = parseTree(treeUri) ?: return null
        if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = decode(tree.encodedDocumentId) ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        if (volume.contains('/')) return null
        val relative = relativePath(documentId.substring(separator + 1))?.takeIf { it.isNotEmpty() } ?: return null
        return when (volume) {
            PRIMARY_VOLUME -> relative
            HOME_VOLUME -> "$DOCUMENTS_DIR/$relative"
            else -> "$volume:/$relative"
        }
    }

    private class Tree(val authority: String, val encodedDocumentId: String)

    private fun parseTree(uri: String): Tree? {
        val rest = uri.removePrefix("content://").takeIf { it != uri } ?: return null
        val segments = rest.substringBefore('?').substringBefore('#').split('/')
        if (segments.size < 3 || segments[1] != "tree") return null
        return Tree(segments[0], segments[2])
    }

    private fun externalStoragePath(documentId: String, primaryRoot: String): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        if (volume.contains('/')) return null
        val relative = relativePath(documentId.substring(separator + 1)) ?: return null
        val base = when (volume) {
            PRIMARY_VOLUME -> primaryRoot.trimEnd('/')
            HOME_VOLUME -> "${primaryRoot.trimEnd('/')}/$DOCUMENTS_DIR"
            else -> "$STORAGE_DIR/$volume"
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    private fun rawPath(documentId: String): String? {
        if (!documentId.startsWith(RAW_PREFIX)) return null
        val path = documentId.removePrefix(RAW_PREFIX)
        if (!path.startsWith("/")) return null
        val relative = relativePath(path) ?: return null
        return "/$relative"
    }

    /** Normalized relative path without empty segments; null when it climbs up with `..`. */
    private fun relativePath(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** Percent-decoding as in URIs: unlike form decoding, `+` stays a plus. */
    private fun decode(encoded: String): String? {
        val out = ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c != '%') {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            if (i + 3 > encoded.length) return null
            val high = Character.digit(encoded[i + 1], 16)
            val low = Character.digit(encoded[i + 2], 16)
            if (high < 0 || low < 0) return null
            out.write(high * 16 + low)
            i += 3
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
