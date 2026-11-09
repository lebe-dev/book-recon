package ru.tinyops.android.bookrecon.core.selection

import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.naming.isAudioFile
import ru.tinyops.android.bookrecon.core.naming.isEbookFile
import ru.tinyops.android.bookrecon.core.naming.splitExtension

/**
 * Selection state of a folder: counts and sizes include files in all nested folders.
 *
 * @property path slash-separated folder path as in [TorrentFile.path], without a trailing
 *   slash.
 */
data class FolderState(
    val path: String,
    val fileCount: Int,
    val selectedCount: Int,
    val size: Long,
    val selectedSize: Long,
) {
    /** Tri-state checkbox value. */
    enum class Check { ALL, PARTIAL, NONE }

    val check: Check
        get() = when (selectedCount) {
            0 -> Check.NONE
            fileCount -> Check.ALL
            else -> Check.PARTIAL
        }
}

/**
 * Immutable file picker state for one torrent. [files] are the offered files in natural
 * order of their paths; files of other kinds are not offered at all. Selection is keyed by
 * [TorrentFile.index]. Every operation returns a new instance.
 */
class FileSelection private constructor(
    val files: List<TorrentFile>,
    selected: Set<Int>,
) {
    private val offered: Set<Int> = files.mapTo(HashSet()) { it.index }

    /** Selected torrent file indexes. */
    val selected: Set<Int> = selected.filterTo(HashSet()) { it in offered }

    /** True when the torrent has no files of the expected kind. */
    val isEmpty: Boolean
        get() = files.isEmpty()

    val selectedCount: Int
        get() = selected.size

    val totalSize: Long by lazy { files.sumOf { it.size } }

    val selectedSize: Long by lazy { files.filter { it.index in selected }.sumOf { it.size } }

    /** Selected indexes in display order, ready for a download request. */
    val selectedIndexes: Set<Int> by lazy { files.mapNotNullTo(LinkedHashSet()) { it.index.takeIf { i -> i in selected } } }

    private val folderStates: Map<String, FolderState> by lazy { buildFolderStates() }

    fun isSelected(index: Int): Boolean = index in selected

    /** Flips one file; an index that is not offered changes nothing. */
    fun toggleFile(index: Int): FileSelection {
        if (index !in offered) return this
        return copy(if (index in selected) selected - index else selected + index)
    }

    /**
     * Clears every file under [path] (nested folders included) when all of them are
     * selected, otherwise selects them all. An unknown folder changes nothing.
     */
    fun toggleFolder(path: String): FileSelection {
        val prefix = "$path/"
        val inside = files.filter { it.path.startsWith(prefix) }.map { it.index }
        if (inside.isEmpty()) return this
        val allSelected = inside.all { it in selected }
        return copy(if (allSelected) selected - inside.toSet() else selected + inside)
    }

    fun selectAll(): FileSelection = copy(offered)

    fun selectNone(): FileSelection = copy(emptySet())

    /** The same files with [indexes] selected, for restoring saved state; unknown indexes are dropped. */
    fun withSelected(indexes: Set<Int>): FileSelection = copy(indexes)

    /** Every folder that holds offered files, parents before children, in natural order. */
    fun folders(): List<FolderState> = folderStates.values.sortedWith(compareBy(NaturalOrder) { it.path })

    /** State of the folder at [path], or null if it holds no offered files. */
    fun folder(path: String): FolderState? = folderStates[path]

    /**
     * Flattened tree for a lazy list: each folder is followed by its subfolders and then its
     * files, all in natural order. Children of folders in [collapsed] (by path) are left out.
     * With [skipRoot], a single top-level folder that wraps everything (the torrent root) is
     * not shown and its content starts at depth 0.
     */
    fun tree(collapsed: Set<String> = emptySet(), skipRoot: Boolean = true): List<TreeNode> {
        val root = Dir("", "")
        files.forEach { root.add(it) }
        val start = root.dirs.values.singleOrNull()?.takeIf { skipRoot && root.files.isEmpty() } ?: root
        val out = ArrayList<TreeNode>(files.size + folderStates.size)
        emit(start, 0, collapsed, out)
        return out
    }

    private fun emit(dir: Dir, depth: Int, collapsed: Set<String>, out: MutableList<TreeNode>) {
        dir.dirs.values.sortedWith(compareBy(NaturalOrder) { it.name }).forEach { sub ->
            val expanded = sub.path !in collapsed
            out += TreeNode.Folder(folderStates.getValue(sub.path), sub.name, depth, expanded)
            if (expanded) emit(sub, depth + 1, collapsed, out)
        }
        dir.files.sortedWith(compareBy(NaturalOrder) { it.path.substringAfterLast('/') }).forEach { file ->
            out += TreeNode.File(file, file.path.substringAfterLast('/'), depth, file.index in selected)
        }
    }

    private fun buildFolderStates(): Map<String, FolderState> {
        val states = HashMap<String, FolderState>()
        files.forEach { file ->
            val isOn = file.index in selected
            folderPrefixes(file.path).forEach { path ->
                val current = states[path] ?: FolderState(path, 0, 0, 0, 0)
                states[path] = current.copy(
                    fileCount = current.fileCount + 1,
                    selectedCount = current.selectedCount + if (isOn) 1 else 0,
                    size = current.size + file.size,
                    selectedSize = current.selectedSize + if (isOn) file.size else 0,
                )
            }
        }
        return states
    }

    private fun copy(selected: Set<Int>): FileSelection = FileSelection(files, selected)

    override fun equals(other: Any?): Boolean =
        other is FileSelection && files == other.files && selected == other.selected

    override fun hashCode(): Int = 31 * files.hashCode() + selected.hashCode()

    override fun toString(): String = "FileSelection(files=${files.size}, selected=${selected.size})"

    private class Dir(val path: String, val name: String) {
        val dirs = LinkedHashMap<String, Dir>()
        val files = ArrayList<TorrentFile>()

        fun add(file: TorrentFile) {
            val segments = file.path.split('/')
            var dir = this
            segments.dropLast(1).forEach { segment ->
                val path = if (dir.path.isEmpty()) segment else "${dir.path}/$segment"
                dir = dir.dirs.getOrPut(segment) { Dir(path, segment) }
            }
            dir.files += file
        }
    }

    companion object {
        /** E-book format fallback order after the preferred one, as in the bot. */
        val formatPriority: List<Format> = listOf(Format.EPUB, Format.FB2, Format.MOBI, Format.PDF, Format.DJVU)

        /** How many e-book files are preselected at most, as the bot sends at most five. */
        const val DEFAULT_MAX_PRESELECTED = 5

        /** Audio files only, all selected. */
        fun forAudio(info: TorrentInfo): FileSelection {
            val files = sorted(info.files.filter { isAudioFile(it.path) })
            return FileSelection(files, files.mapTo(HashSet()) { it.index })
        }

        /**
         * E-book files only. Preselects up to [maxPreselected] files of one format: [preferred]
         * if the torrent has it, otherwise the first available by [formatPriority].
         */
        fun forBooks(
            info: TorrentInfo,
            preferred: Format = Format.EPUB,
            maxPreselected: Int = DEFAULT_MAX_PRESELECTED,
        ): FileSelection {
            val files = sorted(info.files.filter { isEbookFile(it.path) })
            val byFormat = files.groupBy { formatOf(it.path) }
            val format = (listOf(preferred) + formatPriority).firstOrNull { !byFormat[it].isNullOrEmpty() }
            val picked = format?.let { byFormat.getValue(it) }.orEmpty().take(maxPreselected)
            return FileSelection(files, picked.mapTo(HashSet()) { it.index })
        }

        private fun sorted(files: List<TorrentFile>): List<TorrentFile> = files.sortedWith(compareBy(NaturalOrder) { it.path })

        private fun formatOf(path: String): Format? {
            val extension = splitExtension(path.substringAfterLast('/')).second
            if (extension == "fb2.zip") return Format.FB2
            return Format.fromExtension(extension)
        }

        private fun folderPrefixes(path: String): List<String> {
            val segments = path.split('/').dropLast(1)
            return segments.indices.map { segments.subList(0, it + 1).joinToString("/") }
        }
    }
}

/** A row of the file tree. */
sealed interface TreeNode {
    val name: String
    val depth: Int

    /** Stable key for a lazy list. */
    val key: String

    data class Folder(val state: FolderState, override val name: String, override val depth: Int, val expanded: Boolean) : TreeNode {
        override val key: String get() = "d:${state.path}"
    }

    data class File(val file: TorrentFile, override val name: String, override val depth: Int, val selected: Boolean) : TreeNode {
        override val key: String get() = "f:${file.index}"
    }
}
