package omp.term

/**
 * The escape sequences the shell, the builtins and the tests exchange. Kept as
 * constants and small builders so a sequence is never typed out inline.
 */
object Ansi {

    const val NUL = 0x00
    const val BEL = 0x07
    const val BS = 0x08
    const val HT = 0x09
    const val LF = 0x0A
    const val CR = 0x0D
    const val ESC = 0x1B
    const val DEL = 0x7F

    const val CSI = '['
    const val OSC = ']'
    const val ST_FINAL = '\\'

    /** Device attributes reported for `CSI c`: a VT220 with printer and ANSI colour. */
    const val DA_PRIMARY = "\u001B[?62;1;2c"

    fun csi(params: String, final: Char): String = "\u001B[$params$final"

    fun csi(final: Char): String = "\u001B[$final"

    fun osc(payload: String): String = "\u001B]$payload\u0007"

    /** `CSI H` then `CSI 2 J` then `CSI 3 J`: the `clear` builtin, scrollback included. */
    val CLEAR = "\u001B[H\u001B[2J\u001B[3J"

    /** Full reset, the `reset` builtin. */
    val RESET = "\u001Bc"

    val ENTER_ALT = "\u001B[?1049h"
    val LEAVE_ALT = "\u001B[?1049l"

    val SGR_RESET = "\u001B[0m"

    fun hideCursor(): String = "\u001B[?25l"

    fun showCursor(): String = "\u001B[?25h"

    fun cursorTo(row: Int, col: Int): String = "\u001B[${row + 1};${col + 1}H"

    fun eraseDisplay(mode: Int): String = "\u001B[${mode}J"

    fun eraseLine(mode: Int): String = "\u001B[${mode}K"

    fun sgr(code: Int): String = "\u001B[${code}m"

    fun truecolor(r: Int, g: Int, b: Int): String = "\u001B[38;2;$r;$g;${b}m"

    fun color256(n: Int): String = "\u001B[38;5;${n}m"

    fun setTitle(title: String): String = osc("0;$title")
}
