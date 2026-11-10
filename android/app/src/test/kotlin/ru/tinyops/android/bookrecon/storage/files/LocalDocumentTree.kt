package ru.tinyops.android.bookrecon.storage.files

import java.io.File
import java.io.IOException

/** [DocumentTree] over local folders: each root "URI" maps to a directory. */
class LocalDocumentTree(private val roots: Map<String, File>) : DocumentTree {
    var granted: Boolean = true
    var failCopies: Boolean = false

    private fun file(root: String, path: String): File? {
        val dir = roots[root] ?: return null
        return if (path.isEmpty()) dir else File(dir, path)
    }

    override fun hasGrant(root: String): Boolean = granted && root in roots

    override fun exists(root: String, path: String): Boolean = file(root, path)?.exists() == true

    override fun isFolder(root: String, path: String): Boolean = file(root, path)?.isDirectory == true

    override fun listFiles(root: String, path: String): List<String> {
        val base = roots[root] ?: return emptyList()
        val folder = file(root, path)?.takeIf { it.isDirectory } ?: return emptyList()
        return folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }.toList()
    }

    override fun copyInto(root: String, from: File, to: String) {
        if (failCopies) throw IOException("disk full")
        val target = file(root, to) ?: throw IOException("unknown root")
        target.parentFile?.mkdirs()
        if (!from.copyRecursively(target, overwrite = false)) throw IOException("cannot copy")
    }

    override fun delete(root: String, path: String) {
        file(root, path)?.deleteRecursively()
    }

    override fun uriOf(root: String, path: String): String? = file(root, path)?.let { "content://test/${it.name}" }
}
