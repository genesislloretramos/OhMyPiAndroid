package omp.term

/**
 * Attribute words. A cell carries two of them, both with the same layout: the
 * style flags live in the foreground word (`RenderRow.attrs`) and the colour in
 * the word it belongs to, so one word holds one colour at full 24-bit
 * precision and equality stays an int compare.
 *
 * | bits    | meaning                                                     |
 * |---------|-------------------------------------------------------------|
 * | 0       | bold                                                        |
 * | 1       | dim                                                          |
 * | 2       | italic                                                      |
 * | 3       | underline                                                   |
 * | 4       | double underline                                            |
 * | 5       | blink                                                       |
 * | 6       | inverse                                                     |
 * | 7       | the colour is explicit (clear = use the theme default)      |
 * | 8..31   | colour `0xRRGGBB`                                          |
 *
 * Foreground and background cannot share one 32-bit word: two 24-bit colours
 * plus the style flags need 57 bits. `strike` (SGR 9) and `invisible` (SGR 8)
 * are recognised by the parser and dropped; nothing this shell runs emits them.
 */
object Attr {

    val BOLD = 1 shl 0
    val DIM = 1 shl 1
    val ITALIC = 1 shl 2
    val UNDERLINE = 1 shl 3
    val DOUBLE_UNDERLINE = 1 shl 4
    val BLINK = 1 shl 5
    val INVERSE = 1 shl 6
    val COLOUR_SET = 1 shl 7

    /** Every bit that is a style rather than a colour. */
    val STYLE_MASK: Int = BOLD or DIM or ITALIC or UNDERLINE or DOUBLE_UNDERLINE or BLINK or INVERSE

    const val COLOUR_SHIFT = 8
    val COLOUR_MASK: Int = 0xFFFFFF shl COLOUR_SHIFT

    const val DEFAULT_ATTR = 0

    const val SGR_RESET = 0
    const val SGR_BOLD = 1
    const val SGR_DIM = 2
    const val SGR_ITALIC = 3
    const val SGR_UNDERLINE = 4
    const val SGR_BLINK = 5
    const val SGR_INVERSE = 7
    const val SGR_INVISIBLE = 8
    const val SGR_STRIKE = 9
    const val SGR_DOUBLE_UNDERLINE = 21
    const val SGR_BOLD_OFF = 22
    const val SGR_ITALIC_OFF = 23
    const val SGR_UNDERLINE_OFF = 24
    const val SGR_BLINK_OFF = 25
    const val SGR_INVERSE_OFF = 27
    const val SGR_INVISIBLE_OFF = 28
    const val SGR_STRIKE_OFF = 29
    const val SGR_FG_FIRST = 30
    const val SGR_FG_LAST = 37
    const val SGR_FG_DEFAULT = 39
    const val SGR_BG_FIRST = 40
    const val SGR_BG_LAST = 47
    const val SGR_BG_DEFAULT = 49
    const val SGR_FG_BRIGHT_FIRST = 90
    const val SGR_FG_BRIGHT_LAST = 97
    const val SGR_BG_BRIGHT_FIRST = 100
    const val SGR_BG_BRIGHT_LAST = 107
    const val SGR_EXT_FG = 38
    const val SGR_EXT_BG = 48
    const val SGR_UNDERLINE_COLOR = 58

    private val ANSI_COLORS = intArrayOf(
        0x000000, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        0x808080, 0xFF5555, 0x55FF55, 0xFFFF55,
        0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF,
    )

    /** xterm 256-colour value: 16 ANSI, 216 cube, 24 greys. */
    fun color(index: Int): Int = when {
        index < 16 -> ANSI_COLORS[index]
        index < 232 -> {
            val i = index - 16
            (cube(i / 36) shl 16) or (cube((i / 6) % 6) shl 8) or cube(i % 6)
        }
        index < 256 -> {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
        else -> 0
    }

    private fun cube(i: Int): Int = if (i == 0) 0 else 55 + i * 40

    fun fg(word: Int): Int = (word ushr COLOUR_SHIFT) and 0xFFFFFF

    fun bg(word: Int): Int = (word ushr COLOUR_SHIFT) and 0xFFFFFF

    fun fgIsDefault(word: Int): Boolean = word and COLOUR_SET == 0

    fun bgIsDefault(word: Int): Boolean = word and COLOUR_SET == 0

    fun fgSet(word: Int, rgb: Int): Int =
        (word and COLOUR_MASK.inv()) or ((rgb and 0xFFFFFF) shl COLOUR_SHIFT) or COLOUR_SET

    fun bgSet(word: Int, rgb: Int): Int = fgSet(word, rgb)

    fun fgDefault(word: Int): Int = word and STYLE_MASK

    fun bgDefault(word: Int): Int = fgDefault(word)

    fun bold(word: Int): Boolean = word and BOLD != 0

    fun dim(word: Int): Boolean = word and DIM != 0

    fun italic(word: Int): Boolean = word and ITALIC != 0

    fun underline(word: Int): Boolean = word and (UNDERLINE or DOUBLE_UNDERLINE) != 0

    fun doubleUnderline(word: Int): Boolean = word and DOUBLE_UNDERLINE != 0

    fun blink(word: Int): Boolean = word and BLINK != 0

    fun inverse(word: Int): Boolean = word and INVERSE != 0

    /**
     * The pen: style plus foreground in the high word, background in the low
     * word, so applying an SGR list allocates nothing.
     */
    fun fgWord(pen: Long): Int = (pen ushr 32).toInt()

    fun bgWord(pen: Long): Int = pen.toInt()

    fun pen(fg: Int, bg: Int): Long = (fg.toLong() and 0xFFFFFFFFL shl 32) or (bg.toLong() and 0xFFFFFFFFL)

    /**
     * Applies an SGR parameter list. `params` holds one entry per parameter with
     * -1 for an omitted one; a `count` of 0 means bare `CSI m`, a full reset.
     */
    fun applySgr(pen: Long, params: IntArray, count: Int): Long {
        if (count == 0) return 0L
        var fg = fgWord(pen)
        var bg = bgWord(pen)
        var i = 0
        while (i < count) {
            val v = if (params[i] < 0) 0 else params[i]
            when {
                v == SGR_RESET -> {
                    fg = DEFAULT_ATTR
                    bg = DEFAULT_ATTR
                }
                v == SGR_BOLD -> fg = fg or BOLD
                v == SGR_DIM -> fg = fg or DIM
                v == SGR_ITALIC -> fg = fg or ITALIC
                v == SGR_UNDERLINE -> fg = (fg and DOUBLE_UNDERLINE.inv()) or UNDERLINE
                v == SGR_BLINK -> fg = fg or BLINK
                v == SGR_INVERSE -> fg = fg or INVERSE
                v == SGR_DOUBLE_UNDERLINE -> fg = (fg and UNDERLINE.inv()) or DOUBLE_UNDERLINE
                v == SGR_BOLD_OFF -> fg = fg and (BOLD or DIM).inv()
                v == SGR_ITALIC_OFF -> fg = fg and ITALIC.inv()
                v == SGR_UNDERLINE_OFF -> fg = fg and (UNDERLINE or DOUBLE_UNDERLINE).inv()
                v == SGR_BLINK_OFF -> fg = fg and BLINK.inv()
                v == SGR_INVERSE_OFF -> fg = fg and INVERSE.inv()
                v in SGR_FG_FIRST..SGR_FG_LAST -> fg = fgSet(fg, color(v - SGR_FG_FIRST))
                v == SGR_FG_DEFAULT -> fg = fgDefault(fg)
                v in SGR_BG_FIRST..SGR_BG_LAST -> bg = bgSet(bg, color(v - SGR_BG_FIRST))
                v == SGR_BG_DEFAULT -> bg = bgDefault(bg)
                v in SGR_FG_BRIGHT_FIRST..SGR_FG_BRIGHT_LAST ->
                    fg = fgSet(fg, color(v - SGR_FG_BRIGHT_FIRST + 8))
                v in SGR_BG_BRIGHT_FIRST..SGR_BG_BRIGHT_LAST ->
                    bg = bgSet(bg, color(v - SGR_BG_BRIGHT_FIRST + 8))
                v == SGR_EXT_FG -> {
                    val rgb = extended(i, count, params)
                    if (rgb >= 0) fg = fgSet(fg, rgb)
                    i += extendedParamCount(i, count, params)
                }
                v == SGR_EXT_BG -> {
                    val rgb = extended(i, count, params)
                    if (rgb >= 0) bg = bgSet(bg, rgb)
                    i += extendedParamCount(i, count, params)
                }
                v == SGR_UNDERLINE_COLOR -> i += extendedParamCount(i, count, params)
            }
            i++
        }
        return pen(fg, bg)
    }

    /** Value of a `38/48/58` colour spec starting at the selector, or -1. */
    private fun extended(i: Int, count: Int, p: IntArray): Int {
        if (i + 1 >= count) return -1
        return when (p[i + 1]) {
            5 -> if (i + 2 < count) color(if (p[i + 2] < 0) 0 else p[i + 2]) else -1
            2 -> if (i + 4 < count) {
                (p[i + 2].coerceIn(0, 255) shl 16) or
                    (p[i + 3].coerceIn(0, 255) shl 8) or
                    p[i + 4].coerceIn(0, 255)
            } else -1
            else -> -1
        }
    }

    private fun extendedParamCount(i: Int, count: Int, p: IntArray): Int {
        if (i + 1 >= count) return count
        return when (p[i + 1]) {
            5 -> 2
            2 -> 4
            else -> 1
        }
    }
}
