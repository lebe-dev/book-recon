package ru.tinyops.android.bookrecon.ui.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.ErrorCode

class SearchFormatTest {
    @ParameterizedTest
    @CsvSource(
        "0, 0, BYTES",
        "1023, 1023, BYTES",
        "1024, 1, KILOBYTES",
        "1536, 1,5, KILOBYTES",
        "10485760, 10, MEGABYTES",
        "1288490189, 1,2, GIGABYTES",
        "536870912000, 500, GIGABYTES",
        delimiterString = ", ",
    )
    fun `size is shown with one decimal in Russian notation`(bytes: Long, number: String, unit: SizeUnit) {
        assertEquals(FormattedSize(number, unit), formatSize(bytes))
    }

    @Test
    fun `RuTracker credential errors lead to settings`() {
        assertTrue(AppError.RuTrackerAuthRequired().leadsToSettings())
        assertTrue(AppError.RuTrackerLoginFailed().leadsToSettings())
        assertFalse(AppError.Network().leadsToSettings())
        assertFalse(AppError.Timeout().leadsToSettings())
    }

    @Test
    fun `every error code has a message`() {
        val messages = ErrorCode.entries.map { AppError.restore(it, null).searchMessage() }
        assertTrue(messages.all { it != 0 })
    }
}
