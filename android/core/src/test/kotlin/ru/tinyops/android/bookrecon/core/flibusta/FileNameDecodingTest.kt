package ru.tinyops.android.bookrecon.core.flibusta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class FileNameDecodingTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "utf8 percent-encoded | attachment; filename=\"%D0%9A%D1%83%D0%BF%D1%86%D0%BE%D0%B2.epub\" | Купцов.epub",
            "plain ascii          | attachment; filename=\"plain.fb2.zip\"                             | plain.fb2.zip",
            "rfc 5987             | attachment; filename*=UTF-8''%D0%9A%D1%83%D0%BF%D1%86%D0%BE%D0%B2.epub | Купцов.epub",
            "cp1251 percent       | attachment; filename=\"%CA%F3%EF%F6%EE%E2.epub\"                  | Купцов.epub",
            "cp866 percent        | attachment; filename=\"%8A%AD%A8%A3%A0.fb2\"                      | Книга.fb2",
            "unquoted with params | attachment; filename=Author.Book.fb2; size=10                     | Author.Book.fb2",
            "both, star wins      | attachment; filename=\"x.fb2\"; filename*=UTF-8''%D0%AF.fb2      | Я.fb2",
            "malformed escape     | attachment; filename=\"100%.pdf\"                                 | 100%.pdf",
        ],
    )
    fun `filename from content disposition`(@Suppress("UNUSED_PARAMETER") case: String, header: String, expected: String) {
        assertEquals(expected, filenameFromDisposition(header))
    }

    @Test
    fun `raw cp1251 bytes in a header value`() {
        val raw = String("Купцов.epub".toByteArray(Charset.forName("windows-1251")), Charsets.ISO_8859_1)

        assertEquals("Купцов.epub", filenameFromDisposition("attachment; filename=\"$raw\""))
    }

    @Test
    fun `missing or empty header`() {
        assertNull(filenameFromDisposition(null))
        assertNull(filenameFromDisposition(""))
        assertNull(filenameFromDisposition("inline"))
    }

    @Test
    fun `zip entry names`() {
        fun raw(text: String, charset: String) = String(text.toByteArray(Charset.forName(charset)), Charsets.ISO_8859_1)

        assertEquals("Книга.fb2", decodeZipEntryName("Книга.fb2"))
        assertEquals("Книга.fb2", decodeZipEntryName(raw("Книга.fb2", "UTF-8")))
        assertEquals("Книга.fb2", decodeZipEntryName(raw("Книга.fb2", "IBM866")))
        assertEquals("Книга.fb2", decodeZipEntryName(raw("Книга.fb2", "windows-1251")))
        assertEquals("Author. Title.fb2", decodeZipEntryName("Author. Title.fb2"))
    }
}
