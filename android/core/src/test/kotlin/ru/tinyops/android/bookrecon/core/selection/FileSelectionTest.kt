package ru.tinyops.android.bookrecon.core.selection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.fake.FakeData

class FileSelectionTest {
    private fun info(vararg files: Pair<String, Long>) = TorrentInfo(
        infoHash = "hash",
        name = "Book A",
        files = files.mapIndexed { index, (path, size) -> TorrentFile(index, path, size) },
    )

    private fun paths(selection: FileSelection) = selection.files.map { it.path }

    // Ported from TestAudiobookSelectionFilterSortPreselect (no per-file size limit in the app).
    private val goAudio = info(
        "cd/Глава 10.mp3" to 100,
        "cover.jpg" to 5,
        "cd/Глава 2.mp3" to 100,
        "bonus/huge.m4b" to 1000,
        "notes.txt" to 1,
    )

    @Test
    fun `audio keeps only audio files in natural order, all selected`() {
        val selection = FileSelection.forAudio(goAudio)

        assertEquals(listOf("bonus/huge.m4b", "cd/Глава 2.mp3", "cd/Глава 10.mp3"), paths(selection))
        assertEquals(3, selection.selectedCount)
        assertEquals(1200, selection.selectedSize)
        assertEquals(1200, selection.totalSize)
        assertEquals(listOf(3, 2, 0), selection.selectedIndexes.toList())
    }

    @Test
    fun `toggling a folder clears it when full and fills it otherwise`() {
        val selection = FileSelection.forAudio(goAudio)

        val off = selection.toggleFolder("cd")
        assertEquals(1, off.selectedCount)
        assertEquals(FolderState.Check.NONE, off.folder("cd")?.check)

        val partial = off.toggleFile(2)
        assertEquals(FolderState.Check.PARTIAL, partial.folder("cd")?.check)

        val on = partial.toggleFolder("cd")
        assertEquals(3, on.selectedCount)
        assertEquals(FolderState.Check.ALL, on.folder("cd")?.check)
    }

    @Test
    fun `selection is immutable`() {
        val selection = FileSelection.forAudio(goAudio)

        selection.selectNone()
        selection.toggleFile(0)

        assertEquals(3, selection.selectedCount)
    }

    @Test
    fun `select all and none`() {
        val none = FileSelection.forAudio(goAudio).selectNone()
        assertEquals(0, none.selectedCount)
        assertTrue(none.selectedIndexes.isEmpty())
        assertEquals(3, none.selectAll().selectedCount)
    }

    @Test
    fun `toggling an unknown file or folder changes nothing`() {
        val selection = FileSelection.forAudio(goAudio)

        assertEquals(selection, selection.toggleFile(1))
        assertEquals(selection, selection.toggleFolder("missing"))
    }

    @Test
    fun `folders aggregate nested files`() {
        val selection = FileSelection.forAudio(FakeData.torrentInfo(FakeData.AUDIO_TOPIC_CD)).toggleFolder("Булгаков - Мастер и Маргарита/CD2")
        val root = selection.folder("Булгаков - Мастер и Маргарита")!!

        assertEquals(6, root.fileCount)
        assertEquals(3, root.selectedCount)
        assertEquals(FolderState.Check.PARTIAL, root.check)
        assertEquals(selection.totalSize, root.size)
        assertEquals(
            listOf("Булгаков - Мастер и Маргарита", "Булгаков - Мастер и Маргарита/CD1", "Булгаков - Мастер и Маргарита/CD2"),
            selection.folders().map { it.path },
        )
    }

    @Test
    fun `toggling a parent folder covers subfolders`() {
        val selection = FileSelection.forAudio(FakeData.torrentInfo(FakeData.AUDIO_TOPIC_CD))

        val off = selection.toggleFolder("Булгаков - Мастер и Маргарита")
        assertEquals(0, off.selectedCount)

        val partial = off.toggleFolder("Булгаков - Мастер и Маргарита/CD1")
        val on = partial.toggleFolder("Булгаков - Мастер и Маргарита")
        assertEquals(6, on.selectedCount)
    }

    @Test
    fun `restoring a saved selection ignores unknown indexes`() {
        val selection = FileSelection.forAudio(goAudio).withSelected(setOf(0, 1, 99))

        assertEquals(setOf(0), selection.selectedIndexes)
    }

    @Test
    fun `torrent without matching files gives an empty selection`() {
        val selection = FileSelection.forAudio(info("cover.jpg" to 1, "book.epub" to 2))

        assertTrue(selection.isEmpty)
        assertEquals(0, selection.totalSize)
    }

    // Ported from file_picker_test.go: preselection picks one format, up to five files.
    @Test
    fun `books preselect the preferred format`() {
        val selection = FileSelection.forBooks(
            info("book1.epub" to 1024, "book2.fb2" to 2048, "book3.epub" to 3072),
            preferred = Format.EPUB,
        )

        assertEquals(listOf("book1.epub", "book2.fb2", "book3.epub"), paths(selection))
        assertEquals(setOf(0, 2), selection.selectedIndexes)
    }

    @Test
    fun `books fall back by priority when the preferred format is missing`() {
        val selection = FileSelection.forBooks(info("book1.fb2" to 1024, "book2.pdf" to 2048), preferred = Format.EPUB)

        assertEquals(setOf(0), selection.selectedIndexes)
    }

    @Test
    fun `books preselect at most the limit`() {
        val selection = FileSelection.forBooks(
            info("book1.epub" to 1, "book2.epub" to 2, "book3.epub" to 3),
            preferred = Format.EPUB,
            maxPreselected = 2,
        )

        assertEquals(setOf(0, 1), selection.selectedIndexes)
        assertEquals(3, selection.files.size)
    }

    @Test
    fun `books ignore non-book files and count fb2 zip as fb2`() {
        val selection = FileSelection.forBooks(
            info("readme.txt" to 100, "cover.jpg" to 200, "book.fb2.zip" to 1024, "book.djvu" to 4096),
            preferred = Format.FB2,
        )

        assertEquals(listOf("book.djvu", "book.fb2.zip"), paths(selection))
        assertEquals(setOf(2), selection.selectedIndexes)
    }

    @Test
    fun `books of a collection are preselected in natural order`() {
        val selection = FileSelection.forBooks(FakeData.torrentInfo(FakeData.BOOK_TOPIC_COLLECTION), preferred = Format.PDF)

        assertEquals(4, selection.files.size)
        assertEquals(listOf(3), selection.selectedIndexes.toList())
        assertFalse(selection.files.any { it.path.endsWith(".txt") })
    }

    @ParameterizedTest(name = "[{index}] {0} vs {1}")
    @MethodSource("naturalCases")
    fun `naturalCompare matches the bot`(a: String, b: String, expected: Int) {
        assertEquals(expected, naturalCompare(a, b).coerceIn(-1, 1))
        assertEquals(-expected, naturalCompare(b, a).coerceIn(-1, 1))
    }

    companion object {
        @JvmStatic
        fun naturalCases() = listOf(
            Arguments.of("Глава 2", "Глава 10", -1),
            Arguments.of("10", "9", 1),
            Arguments.of("a01", "a1", 0),
            Arguments.of("cd1/02.mp3", "cd1/10.mp3", -1),
            Arguments.of("abc", "abd", -1),
            Arguments.of("ab", "abc", -1),
            Arguments.of("CD2/01.mp3", "CD10/01.mp3", -1),
            Arguments.of("Том 9.djvu", "Том 10.djvu", -1),
            Arguments.of("99999999999999999999", "100000000000000000000", -1),
        )
    }
}
