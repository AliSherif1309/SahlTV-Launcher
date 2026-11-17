package com.aly.sahlquran

/** A zero-based PDF location is independent of its printed Madinah page number. */
internal object PdfPageMapping {
    // Extra PDF pages may be at the end; page count cannot identify front matter.
    fun alignmentStart(savedOffset: Int, count: Int) = if (savedOffset >= 0)
        savedOffset.coerceIn(0, (count - 1).coerceAtLeast(0)) else 0

    fun index(offset: Int, page: Int) = offset + page - 1
    fun page(offset: Int, index: Int) = index - offset + 1
    fun correctedOffset(offset: Int, current: Int, printed: Int, count: Int): Int? {
        val next = offset + current - printed
        return next.takeIf { printed in 1..604 && it >= 0 && it + 603 < count }
    }
}
