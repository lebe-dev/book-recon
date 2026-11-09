package ru.tinyops.android.bookrecon.core.selection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.fake.FakeData

class FileTreeTest {
    private fun render(nodes: List<TreeNode>): List<String> = nodes.map { node ->
        val indent = "  ".repeat(node.depth)
        when (node) {
            is TreeNode.Folder -> "$indent${node.name}/ ${node.state.check} ${node.state.selectedCount}/${node.state.fileCount}"
            is TreeNode.File -> "$indent${node.name} ${if (node.selected) "x" else "-"}"
        }
    }

    private val cd = FileSelection.forAudio(FakeData.torrentInfo(FakeData.AUDIO_TOPIC_CD))

    @Test
    fun `tree skips the common torrent root and lists folders first`() {
        val nodes = cd.toggleFile(cd.files.first().index).tree()

        assertEquals(
            listOf(
                "CD1/ PARTIAL 2/3",
                "  01 Глава 1. Никогда не разговаривайте с неизвестными.mp3 -",
                "  02 Глава 2. Понтий Пилат.mp3 x",
                "  10 Глава 10. Вести из Ялты.mp3 x",
                "CD2/ ALL 3/3",
                "  01 Глава 13. Явление героя.mp3 x",
                "  02 Глава 14. Слава петуху!.mp3 x",
                "  11 Глава 32. Прощение и вечный приют.mp3 x",
            ),
            render(nodes),
        )
    }

    @Test
    fun `collapsed folders hide their children`() {
        val nodes = cd.tree(collapsed = setOf("Булгаков - Мастер и Маргарита/CD1"))

        assertEquals(
            listOf(
                "CD1/ ALL 3/3",
                "CD2/ ALL 3/3",
                "  01 Глава 13. Явление героя.mp3 x",
                "  02 Глава 14. Слава петуху!.mp3 x",
                "  11 Глава 32. Прощение и вечный приют.mp3 x",
            ),
            render(nodes),
        )
    }

    @Test
    fun `tree can keep the root folder`() {
        val nodes = cd.tree(skipRoot = false)

        assertEquals("Булгаков - Мастер и Маргарита/ ALL 6/6", render(nodes).first())
        assertEquals(1, nodes[1].depth)
    }

    @Test
    fun `files at the top level stay next to folders`() {
        val info = TorrentInfo(
            "hash",
            "Mixed",
            listOf(
                TorrentFile(0, "10.mp3", 1),
                TorrentFile(1, "Часть 2/01.mp3", 1),
                TorrentFile(2, "2.mp3", 1),
                TorrentFile(3, "Часть 10/01.mp3", 1),
            ),
        )

        val nodes = FileSelection.forAudio(info).tree()

        assertEquals(
            listOf("Часть 2/ ALL 1/1", "  01.mp3 x", "Часть 10/ ALL 1/1", "  01.mp3 x", "2.mp3 x", "10.mp3 x"),
            render(nodes),
        )
        assertEquals("Часть 2", (nodes[0] as TreeNode.Folder).state.path)
    }
}
