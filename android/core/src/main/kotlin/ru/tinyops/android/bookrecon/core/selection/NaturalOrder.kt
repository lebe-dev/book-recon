package ru.tinyops.android.bookrecon.core.selection

/**
 * Compares strings with embedded numbers numerically, so "Глава 2" goes before "Глава 10"
 * and "a01" equals "a1". Other characters compare by code unit. Numbers of any length work:
 * they are compared as digit strings, never parsed.
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
            val endA = digitsEnd(a, i)
            val endB = digitsEnd(b, j)
            val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
            val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
            if (numA.length != numB.length) return numA.length.compareTo(numB.length)
            val byDigits = numA.compareTo(numB)
            if (byDigits != 0) return byDigits
            i = endA
            j = endB
            continue
        }
        if (ca != cb) return ca.compareTo(cb)
        i++
        j++
    }
    return (a.length - i).compareTo(b.length - j)
}

/** [naturalCompare] as a comparator. */
val NaturalOrder: Comparator<String> = Comparator(::naturalCompare)

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun digitsEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length && text[end].isAsciiDigit()) end++
    return end
}
