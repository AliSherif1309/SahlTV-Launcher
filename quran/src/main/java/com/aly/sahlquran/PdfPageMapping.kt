package com.aly.sahlquran

/** A zero-based PDF location is independent of its printed Madinah page number. */
internal object PdfPageMapping {
    fun index(offset: Int, page: Int) = offset + page - 1
    fun page(offset: Int, index: Int) = index - offset + 1
    fun correctedOffset(offset: Int, current: Int, printed: Int, count: Int): Int? {
        val next = offset + current - printed
        return next.takeIf { printed in 1..604 && it >= 0 && it + 603 < count }
    }
}
