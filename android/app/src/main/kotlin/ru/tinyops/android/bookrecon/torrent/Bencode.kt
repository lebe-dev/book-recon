package ru.tinyops.android.bookrecon.torrent

/** Decoded bencode value. Dictionaries remember their byte range for info-hash computation. */
internal sealed interface BValue {
    class BInt(val value: Long) : BValue

    class BBytes(val bytes: ByteArray) : BValue

    class BList(val items: List<BValue>) : BValue

    class BDict(val entries: Map<String, BValue>, val start: Int, val end: Int) : BValue {
        operator fun get(key: String): BValue? = entries[key]
    }
}

/** Thrown for malformed bencode; the parser turns it into a parse error. */
internal class BencodeException(message: String) : Exception(message)

/** Strict bencode decoder with a nesting limit, so hostile input cannot exhaust the stack. */
internal class BencodeDecoder private constructor(private val data: ByteArray) {
    private var pos = 0

    private fun value(depth: Int): BValue {
        if (depth > MAX_DEPTH) throw BencodeException("nesting is too deep")
        if (pos >= data.size) throw BencodeException("unexpected end of data")
        return when (data[pos].toInt().toChar()) {
            'i' -> integer()
            'l' -> list(depth)
            'd' -> dict(depth)
            in '0'..'9' -> BValue.BBytes(bytes())
            else -> throw BencodeException("unexpected byte at $pos")
        }
    }

    private fun integer(): BValue.BInt {
        pos++
        val end = indexOf('e')
        val text = String(data, pos, end - pos, Charsets.US_ASCII)
        val value = text.toLongOrNull() ?: throw BencodeException("bad integer '$text'")
        pos = end + 1
        return BValue.BInt(value)
    }

    private fun bytes(): ByteArray {
        val colon = indexOf(':')
        val length = String(data, pos, colon - pos, Charsets.US_ASCII).toIntOrNull()
            ?: throw BencodeException("bad string length")
        val start = colon + 1
        if (length < 0 || start + length > data.size) throw BencodeException("string runs past the end")
        pos = start + length
        return data.copyOfRange(start, start + length)
    }

    private fun list(depth: Int): BValue.BList {
        pos++
        val items = mutableListOf<BValue>()
        while (peek() != 'e') items += value(depth + 1)
        pos++
        return BValue.BList(items)
    }

    private fun dict(depth: Int): BValue.BDict {
        val start = pos
        pos++
        val entries = LinkedHashMap<String, BValue>()
        while (peek() != 'e') {
            if (data[pos].toInt().toChar() !in '0'..'9') throw BencodeException("dictionary key is not a string")
            val key = String(bytes(), Charsets.UTF_8)
            entries[key] = value(depth + 1)
        }
        pos++
        return BValue.BDict(entries, start, pos)
    }

    private fun peek(): Char {
        if (pos >= data.size) throw BencodeException("unexpected end of data")
        return data[pos].toInt().toChar()
    }

    private fun indexOf(char: Char): Int {
        for (i in pos until data.size) {
            if (data[i].toInt().toChar() == char) return i
        }
        throw BencodeException("missing '$char'")
    }

    companion object {
        private const val MAX_DEPTH = 64

        /** Decodes the first value of [data]; trailing bytes are ignored, as libtorrent does. */
        fun decode(data: ByteArray): BValue = BencodeDecoder(data).value(0)
    }
}
