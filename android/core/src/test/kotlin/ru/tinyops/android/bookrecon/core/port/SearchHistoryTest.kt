package ru.tinyops.android.bookrecon.core.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchHistoryTest {
    @Test
    fun `newest query goes first and the history keeps five`() {
        val history = (1..6).fold(emptyList<String>()) { list, n -> SearchHistory.push(list, "query $n") }

        assertEquals(listOf("query 6", "query 5", "query 4", "query 3", "query 2"), history)
    }

    @Test
    fun `a repeated query moves up instead of being added twice`() {
        val history = SearchHistory.push(listOf("Пелевин", "Токарчук"), "  токарчук ")

        assertEquals(listOf("токарчук", "Пелевин"), history)
    }

    @Test
    fun `blank queries are ignored and line breaks become spaces`() {
        assertEquals(listOf("a"), SearchHistory.push(listOf("a"), "  \n "))
        assertEquals(listOf("Веди свой плуг", "a"), SearchHistory.push(listOf("a"), "Веди\nсвой  плуг"))
    }
}
