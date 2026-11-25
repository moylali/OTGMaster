package app.fayaz.otgmaster.util

/**
 * Overflow-safe checks for offsets and lengths read off a drive.
 *
 * Every size, offset and count in an on-disk header is attacker- or corruption-controlled.
 * Arithmetic on them is done here in Long with Math.*Exact, so a value that would wrap is
 * refused instead of producing a small, plausible-looking result.
 */
object CheckedRange {

    /** Thrown for a range that does not fit; the message names the field. */
    class OutOfRange(message: String) : IllegalArgumentException(message)

    /** [a] + [b], or [OutOfRange] naming [what] if it overflows. */
    fun add(a: Long, b: Long, what: String): Long =
        try { Math.addExact(a, b) } catch (_: ArithmeticException) { throw OutOfRange("$what overflows") }

    /** [a] × [b], or [OutOfRange] naming [what] if it overflows. */
    fun multiply(a: Long, b: Long, what: String): Long =
        try { Math.multiplyExact(a, b) } catch (_: ArithmeticException) { throw OutOfRange("$what overflows") }

    /**
     * Requires [offset, offset + length) to lie inside [lower, upper), with a non-negative
     * offset and a positive length. Returns the end (exclusive).
     */
    fun requireWithin(offset: Long, length: Long, lower: Long, upper: Long, what: String): Long {
        if (offset < 0) throw OutOfRange("$what starts at a negative offset ($offset)")
        if (length <= 0) throw OutOfRange("$what has no length ($length)")
        val end = add(offset, length, what)
        if (offset < lower || end > upper) {
            throw OutOfRange("$what [$offset, $end) lies outside [$lower, $upper)")
        }
        return end
    }

    /** [value] as an Int, or [OutOfRange] naming [what] if it does not fit or is negative. */
    fun toIntSize(value: Long, what: String): Int {
        if (value < 0 || value > Int.MAX_VALUE) throw OutOfRange("$what ($value) is not a usable size")
        return value.toInt()
    }

    /** Requires [value] to be a multiple of [unit]. */
    fun requireAligned(value: Long, unit: Long, what: String) {
        if (unit <= 0 || value % unit != 0L) throw OutOfRange("$what ($value) is not a multiple of $unit")
    }
}
