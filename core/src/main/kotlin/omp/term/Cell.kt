package omp.term

/**
 * One terminal cell. `code` is a Unicode codepoint (or [WIDE_PAD] for the
 * second half of a double-width character) and `attr` is the packed foreground
 * word from [Attr], so two cells compare as a pair of int compares. The
 * background travels in the parallel `bgs` array of the same layout.
 */
data class Cell(val code: Int, val attr: Int)

/** Marks the right half of a double-width character; never a real codepoint. */
const val WIDE_PAD: Int = -1

/** A mutable row of cells owned by the screen (or by the scrollback ring). */
internal class CellArray(cols: Int) {

    var cols: Int = maxOf(1, cols)
        private set

    var codes = IntArray(this.cols)
    var attrs = IntArray(this.cols)
    var bgs = IntArray(this.cols)

    /** True when this row continues onto the next because of autowrap. */
    var wrapped: Boolean = false

    fun resize(newCols: Int) {
        val n = maxOf(1, newCols)
        if (n == cols) return
        codes = IntArray(n)
        attrs = IntArray(n)
        bgs = IntArray(n)
        cols = n
    }

    fun clear(bg: Int) {
        codes.fill(' '.code)
        attrs.fill(Attr.DEFAULT_ATTR)
        bgs.fill(bg)
        wrapped = false
    }

    fun copyFrom(other: CellArray) {
        val n = minOf(cols, other.cols)
        System.arraycopy(other.codes, 0, codes, 0, n)
        System.arraycopy(other.attrs, 0, attrs, 0, n)
        System.arraycopy(other.bgs, 0, bgs, 0, n)
        wrapped = other.wrapped
    }
}

/**
 * An immutable copy of one rendered row, taken under [Screen.lock] so the draw
 * thread never touches live state. `bgs` has the same layout as `attrs`; a row
 * built from the three-argument form has no background words, which read back as
 * the theme default.
 */
data class RenderRow(
    val codes: IntArray,
    val attrs: IntArray,
    val wrapped: Boolean,
    val bgs: IntArray = IntArray(0),
) {

    internal constructor(cells: CellArray) :
        this(cells.codes.copyOf(), cells.attrs.copyOf(), cells.wrapped, cells.bgs.copyOf())

    fun cellAt(col: Int): Cell = Cell(codes[col], attrs[col])

    fun bgAt(col: Int): Int = if (col < bgs.size) bgs[col] else Attr.DEFAULT_ATTR

    /** Row text, with the padding half of wide characters dropped. */
    fun text(): String {
        val sb = StringBuilder(codes.size)
        for (code in codes) {
            if (code == WIDE_PAD || code == 0) continue
            sb.appendCodePoint(code)
        }
        return sb.toString()
    }

    fun isBlank(): Boolean {
        for (i in codes.indices) {
            val c = codes[i]
            if (c != ' '.code && c != 0) return false
        }
        return true
    }
}
