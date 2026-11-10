package ru.tinyops.android.bookrecon.storage.files

import java.io.File
import java.io.IOException

/**
 * Operations on a folder tree the user picked with the system folder picker. A root is the
 * tree URI as text; every path is relative to it, slash-separated, with "" meaning the root
 * folder itself. Calls block on file I/O, so run them off the main thread.
 */
interface DocumentTree {
    /** True when the app holds a persisted read and write grant on [root]. */
    fun hasGrant(root: String): Boolean

    fun exists(root: String, path: String): Boolean

    fun isFolder(root: String, path: String): Boolean

    /** Paths relative to [root] of every file under the folder [path], subfolders included. */
    fun listFiles(root: String, path: String): List<String>

    /**
     * Copies a local file or folder to [to], creating missing parent folders. A failed copy
     * leaves nothing of the new item behind.
     *
     * @throws IOException when the copy fails.
     */
    fun copyInto(root: String, from: File, to: String)

    /** Deletes a file or a folder with its content; failures are ignored. */
    fun delete(root: String, path: String)

    /** The content URI of [path] that other apps can open while this app holds the grant. */
    fun uriOf(root: String, path: String): String?
}
