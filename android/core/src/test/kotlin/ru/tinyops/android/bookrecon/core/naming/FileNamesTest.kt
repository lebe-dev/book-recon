package ru.tinyops.android.bookrecon.core.naming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class FileNamesTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("sanitizeCases")
    fun `sanitizeFileName cleans names for FAT and exFAT`(input: String, expected: String) {
        assertEquals(expected, sanitizeFileName(input))
    }

    @Test
    fun `sanitizeFileName limits length`() {
        val long = "Очень длинное название ".repeat(20)

        val result = sanitizeFileName(long)

        assertTrue(result.length <= MAX_FILE_NAME_LENGTH, "length ${result.length}")
        assertTrue(result.startsWith("Очень длинное название"))
        assertFalse(result.endsWith(" "))
    }

    @Test
    fun `sanitizeFileName does not split surrogate pairs`() {
        val emoji = "📚"
        val name = "a".repeat(MAX_FILE_NAME_LENGTH - 1) + emoji + "tail"

        val result = sanitizeFileName(name)

        assertEquals("a".repeat(MAX_FILE_NAME_LENGTH - 1), result)
        assertFalse(result.last().isHighSurrogate())
    }

    @Test
    fun `sanitizeFileName honours a custom limit`() {
        assertEquals("Мастер", sanitizeFileName("Мастер и Маргарита", maxLength = 7))
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("extensionCases")
    fun `splitExtension knows compound extensions`(name: String, stem: String, extension: String) {
        assertEquals(stem to extension, splitExtension(name))
    }

    @Test
    fun `media extensions match the bot`() {
        assertEquals(listOf("epub", "fb2", "fb2.zip", "mobi", "pdf", "djvu"), ebookExtensions)
        assertEquals(listOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma"), audioExtensions)
    }

    @Test
    fun `media checks use the file extension`() {
        assertTrue(isAudioFile("Книга/CD1/01 Глава.MP3"))
        assertTrue(isEbookFile("Книга/Достоевский.fb2.zip"))
        assertTrue(isEbookFile("Толстой/Том 01.djvu"))
        assertFalse(isAudioFile("Книга/cover.jpg"))
        assertFalse(isEbookFile("Книга/архив.zip"))
        assertFalse(isEbookFile("Книга/epub"))
    }

    companion object {
        @JvmStatic
        fun sanitizeCases() = listOf(
            Arguments.of("Мастер и Маргарита", "Мастер и Маргарита"),
            Arguments.of("Том 1: Начало", "Том 1 Начало"),
            Arguments.of("AC/DC \\ Back*In?Black", "AC DC Back In Black"),
            Arguments.of("\"Цитата\" <тег> a|b", "Цитата тег a b"),
            Arguments.of("Строка\u0000с\tуправляющими\nсимволами\u007F", "Строка с управляющими символами"),
            Arguments.of("  ...Скрытый файл... ", "Скрытый файл"),
            Arguments.of("Много    пробелов и nbsp", "Много пробелов и nbsp"),
            Arguments.of("Generation «П»", "Generation «П»"),
            Arguments.of("Книга 📚 с эмодзи", "Книга 📚 с эмодзи"),
            Arguments.of("", "Без названия"),
            Arguments.of(" ?*: ", "Без названия"),
            Arguments.of("..", "Без названия"),
        )

        @JvmStatic
        fun extensionCases() = listOf(
            Arguments.of("Мастер и Маргарита.epub", "Мастер и Маргарита", "epub"),
            Arguments.of("Преступление и наказание.FB2.ZIP", "Преступление и наказание", "fb2.zip"),
            Arguments.of("архив.zip", "архив", "zip"),
            Arguments.of("Том 1. Начало.pdf", "Том 1. Начало", "pdf"),
            Arguments.of("Без расширения", "Без расширения", ""),
            Arguments.of(".nomedia", ".nomedia", ""),
            Arguments.of("файл.", "файл.", ""),
        )
    }
}
