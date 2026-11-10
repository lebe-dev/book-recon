package ru.tinyops.android.bookrecon.core.naming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind

class LayoutTest {
    private fun taken(vararg names: String): (String) -> Boolean = { it in names }

    @Test
    fun `book name is the title when free`() {
        assertEquals("Мастер и Маргарита.epub", bookTargetName("Мастер и Маргарита", "Михаил Булгаков", Format.EPUB, taken()))
    }

    @Test
    fun `book name adds the author on collision`() {
        val exists = taken("Мастер и Маргарита.epub")

        assertEquals("Мастер и Маргарита (Михаил Булгаков).epub", bookTargetName("Мастер и Маргарита", "Михаил Булгаков", Format.EPUB, exists))
    }

    @Test
    fun `book name numbers further collisions`() {
        val exists = taken("Мы.pdf", "Мы (Евгений Замятин).pdf", "Мы (Евгений Замятин) (2).pdf")

        assertEquals("Мы (Евгений Замятин) (3).pdf", bookTargetName("Мы", "Евгений Замятин", Format.PDF, exists))
    }

    @Test
    fun `book name without author goes straight to numbers`() {
        val exists = taken("Сказки.fb2")

        assertEquals("Сказки (2).fb2", bookTargetName("Сказки", " ", Format.FB2, exists))
    }

    @Test
    fun `book name keeps compound extensions and sanitizes parts`() {
        val exists = taken("Том 1 Начало.fb2.zip")

        assertEquals(
            "Том 1 Начало (Иванов Иван).fb2.zip",
            bookTargetName("Том 1: Начало", "Иванов/Иван", "FB2.ZIP", exists),
        )
    }

    @Test
    fun `long book name keeps extension and collision suffix`() {
        val title = "Очень длинное название книги ".repeat(10)

        val first = bookTargetName(title, "Автор", Format.EPUB, taken())
        val second = bookTargetName(title, "Автор", Format.EPUB, taken(first))
        val third = bookTargetName(title, "Автор", Format.EPUB, taken(first, second))

        listOf(first, second, third).forEach { assertTrue(it.length <= MAX_FILE_NAME_LENGTH, it) }
        assertTrue(first.startsWith("Очень длинное название книги"), first)
        assertTrue(first.endsWith(".epub"), first)
        assertTrue(second.endsWith(" (Автор).epub"), second)
        assertTrue(third.endsWith(" (Автор) (2).epub"), third)
    }

    @Test
    fun `book name gives up when everything is taken`() {
        assertThrows<AppError.StorageFailed> { bookTargetName("Мы", "Замятин", Format.PDF) { true } }
    }

    @Test
    fun `audio folder keeps its name when free`() {
        assertEquals(
            "Булгаков Михаил - Мастер и Маргарита",
            audioFolderName("Булгаков Михаил - Мастер и Маргарита", taken()),
        )
    }

    @Test
    fun `audio folder gets a number on collision`() {
        val exists = taken("Кинг Стивен - Оно", "Кинг Стивен - Оно (2)")

        assertEquals("Кинг Стивен - Оно (3)", audioFolderName("Кинг Стивен - Оно", exists))
    }

    @Test
    fun `audio folder name is sanitized`() {
        assertEquals("Без названия", audioFolderName("  ...  ", taken()))
        assertEquals("Хокинг - Время и пространство", audioFolderName("Хокинг - Время: и пространство?", taken()))
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("torrentPaths")
    fun `mapTorrentPath lays out files`(path: String, kind: MediaKind, expected: String) {
        assertEquals(expected, mapTorrentPath(path, kind))
    }

    companion object {
        @JvmStatic
        fun torrentPaths() = listOf(
            Arguments.of("Булгаков - Мастер и Маргарита/CD1/01 Глава 1.mp3", MediaKind.AUDIO, "CD1/01 Глава 1.mp3"),
            Arguments.of("Пикник на обочине/01. Пролог.mp3", MediaKind.AUDIO, "01. Пролог.mp3"),
            Arguments.of("Одиночный файл.m4b", MediaKind.AUDIO, "Одиночный файл.m4b"),
            Arguments.of("Root/Диск 1: начало/01*.mp3", MediaKind.AUDIO, "Диск 1 начало/01.mp3"),
            Arguments.of("Root/../CD1//02.mp3", MediaKind.AUDIO, "CD1/02.mp3"),
            Arguments.of("Толстой/Том 04. Война и мир/Том 04.djvu", MediaKind.BOOK, "Том 04.djvu"),
            Arguments.of("Достоевский.fb2.zip", MediaKind.BOOK, "Достоевский.fb2.zip"),
            Arguments.of("Root/Книга?.epub", MediaKind.BOOK, "Книга.epub"),
        )
    }
}
