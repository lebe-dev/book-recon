package ru.tinyops.android.bookrecon.storage.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * [DocumentTree] over the Storage Access Framework. Document IDs of the supported providers
 * (external storage and downloads) are the parent ID, a slash and the file name, so the
 * document of a relative path is addressed directly instead of walking the folders.
 */
class DocumentsTree(private val resolver: ContentResolver) : DocumentTree {

    override fun hasGrant(root: String): Boolean {
        val uri = Uri.parse(root)
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    override fun exists(root: String, path: String): Boolean = mimeTypeOf(documentUri(root, path)) != null

    override fun isFolder(root: String, path: String): Boolean = mimeTypeOf(documentUri(root, path)) == Document.MIME_TYPE_DIR

    override fun listFiles(root: String, path: String): List<String> {
        val folder = documentUri(root, path)
        if (mimeTypeOf(folder) != Document.MIME_TYPE_DIR) return emptyList()
        val found = mutableListOf<String>()
        collectFiles(Uri.parse(root), DocumentsContract.getDocumentId(folder), path, found)
        return found
    }

    override fun copyInto(root: String, from: File, to: String) {
        val segments = to.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw IOException("empty target path")
        var parent = documentUri(root, "")
        var parentPath = ""
        // The first item this call creates; removed again when the copy fails.
        var created: Uri? = null
        try {
            for (name in segments.dropLast(1)) {
                val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                val existing = documentUri(root, childPath)
                parent = if (mimeTypeOf(existing) == Document.MIME_TYPE_DIR) {
                    existing
                } else {
                    createDocument(parent, Document.MIME_TYPE_DIR, name).also { if (created == null) created = it }
                }
                parentPath = childPath
            }
            val name = segments.last()
            val top = if (from.isDirectory) copyFolder(parent, from, name) else copyFile(parent, from, name)
            if (created == null) created = top
        } catch (e: IOException) {
            created?.let(::deleteQuietly)
            throw e
        } catch (e: RuntimeException) {
            created?.let(::deleteQuietly)
            throw IOException("cannot copy ${from.name}", e)
        }
    }

    override fun delete(root: String, path: String) {
        if (path.isEmpty()) return
        deleteQuietly(documentUri(root, path))
    }

    override fun uriOf(root: String, path: String): String = documentUri(root, path).toString()

    private fun documentUri(root: String, path: String): Uri {
        val tree = Uri.parse(root)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val id = when {
            path.isEmpty() -> rootId
            rootId.endsWith(":") || rootId.endsWith("/") -> rootId + path
            else -> "$rootId/$path"
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun mimeTypeOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun collectFiles(tree: Uri, documentId: String, path: String, out: MutableList<String>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val entries = mutableListOf<Child>()
        try {
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries += Child(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                }
            }
        } catch (_: RuntimeException) {
            return
        }
        for (child in entries) {
            val childPath = "$path/${child.name}"
            if (child.mimeType == Document.MIME_TYPE_DIR) collectFiles(tree, child.id, childPath, out) else out += childPath
        }
    }

    private class Child(val id: String, val name: String, val mimeType: String)

    private fun copyFolder(parent: Uri, from: File, name: String): Uri {
        val folder = createDocument(parent, Document.MIME_TYPE_DIR, name)
        for (child in from.listFiles().orEmpty().sortedBy { it.name }) {
            if (child.isDirectory) copyFolder(folder, child, child.name) else copyFile(folder, child, child.name)
        }
        return folder
    }

    private fun copyFile(parent: Uri, from: File, name: String): Uri {
        val target = createDocument(parent, mimeTypeFor(name), name)
        val output = resolver.openOutputStream(target, "w") ?: throw IOException("cannot open $name for writing")
        output.use { out -> from.inputStream().use { it.copyTo(out) } }
        return target
    }

    /**
     * Creates a document named exactly [name]. A provider may change the name to fit the
     * MIME type, which later lookups by path would not find, so a changed name is put back.
     */
    private fun createDocument(parent: Uri, mimeType: String, name: String): Uri {
        val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw IOException("cannot create $name")
        val actual = displayNameOf(created)
        if (actual == name) return created
        val renamed = try {
            DocumentsContract.renameDocument(resolver, created, name)
        } catch (_: RuntimeException) {
            null
        }
        if (renamed != null && displayNameOf(renamed) == name) return renamed
        deleteQuietly(renamed ?: created)
        throw IOException("the provider changed the name of $name")
    }

    private fun displayNameOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: RuntimeException) {
            // Nothing more can be done for a leftover.
        } catch (_: java.io.FileNotFoundException) {
            // Already gone.
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
