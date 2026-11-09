package ru.tinyops.android.bookrecon.core.flibusta

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Legacy Russian code pages, null when the runtime lacks one. */
private val cp1251: Charset? = charsetOrNull("windows-1251")
private val cp866: Charset? = charsetOrNull("IBM866")

/**
 * The file name from a `Content-Disposition` header, as valid text, or null.
 *
 * `filename*=` (RFC 5987) is always UTF-8. A percent-encoded `filename=` may hold UTF-8 or,
 * from old servers, CP1251 or CP866 bytes; the decoding with the most Cyrillic wins.
 */
internal fun filenameFromDisposition(disposition: String?): String? {
    if (disposition.isNullOrBlank()) return null

    parameter(disposition, "filename*=")?.let { value ->
        val encoded = value.substringAfter("''", missingDelimiterValue = "")
        val bytes = percentDecode(encoded)
        if (bytes != null) return String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    val value = parameter(disposition, "filename=")?.trim('"') ?: return null
    val bytes = percentDecode(value) ?: return value.takeIf { it.isNotBlank() }
    return toUtf8(bytes, cp1251, cp866).takeIf { it.isNotBlank() }
}

/**
 * A zip entry name as valid text. Archives without the UTF-8 flag store names in the OEM
 * code page, CP866 for Russian tools. [name] must come from a zip opened as ISO-8859-1, so
 * every char of an unflagged name is one raw byte.
 */
internal fun decodeZipEntryName(name: String): String {
    if (name.any { it.code > 0xFF }) return name
    return toUtf8(name.toByteArray(Charsets.ISO_8859_1), cp866, cp1251)
}

/**
 * Decodes [bytes] as UTF-8 when they are valid UTF-8; otherwise as the candidate that
 * yields the most Cyrillic letters, or as UTF-8 with replacement characters when none does.
 */
internal fun toUtf8(bytes: ByteArray, vararg candidates: Charset?): String {
    strictUtf8(bytes)?.let { return it }

    return candidates.filterNotNull()
        .map { String(bytes, it) }
        .filter { cyrillicCount(it) > 0 }
        .maxByOrNull(::cyrillicCount)
        ?: String(bytes, Charsets.UTF_8)
}

/** The value of [key] up to the next `;`, or null when the header has no such parameter. */
private fun parameter(header: String, key: String): String? {
    val start = header.indexOf(key, ignoreCase = true)
    if (start < 0) return null
    return header.substring(start + key.length).substringBefore(';').trim()
}

/**
 * Percent-decodes [value] into raw bytes; other chars are taken as Latin-1 bytes when they
 * fit, as header values arrive byte by byte. Returns null on a malformed escape.
 */
private fun percentDecode(value: String): ByteArray? {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '%') {
            if (c.code <= 0xFF) out.write(c.code) else out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
            continue
        }
        if (i + 2 >= value.length) return null
        val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
        out.write(byte)
        i += 3
    }
    return out.toByteArray()
}

private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private fun cyrillicCount(text: String): Int = text.count { it in 'Ѐ'..'ӿ' }

private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
