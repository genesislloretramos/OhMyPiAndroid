package omp.term

/**
 * Column width of a codepoint, from a compact `wcwidth` range table. Ranges are
 * sorted, non-overlapping and stored as lo/hi pairs, so lookup is a binary
 * search with no allocation — this runs once per printed character.
 */
object CharWidth {

    private val WIDE = intArrayOf(
        0x1100, 0x115F,   // Hangul Jamo initial consonants
        0x2E80, 0x303E,   // CJK radicals, Kangxi, punctuation
        0x3041, 0x33FF,   // kana, Hangul compat, CJK compat
        0x3400, 0x4DBF,   // CJK unified ext A
        0x4E00, 0x9FFF,   // CJK unified ideographs
        0xA000, 0xA4CF,   // Yi
        0xA960, 0xA97F,   // Hangul jamo extended A
        0xAC00, 0xD7A3,   // Hangul syllables
        0xF900, 0xFAFF,   // CJK compat ideographs
        0xFE10, 0xFE19,   // vertical forms
        0xFE30, 0xFE6F,   // CJK compat forms, small form variants
        0xFF00, 0xFF60,   // fullwidth forms
        0xFFE0, 0xFFE6,   // fullwidth signs
        0x16FE0, 0x16FE4,
        0x17000, 0x18AFF, // Tangut
        0x1B000, 0x1B16F, // kana supplement/extended
        0x1F004, 0x1F004,
        0x1F0CF, 0x1F0CF,
        0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A,
        0x1F200, 0x1F320,
        0x1F32D, 0x1F335,
        0x1F337, 0x1F37C,
        0x1F37E, 0x1F393,
        0x1F3A0, 0x1F3CA,
        0x1F3CF, 0x1F3D3,
        0x1F3E0, 0x1F3F0,
        0x1F3F4, 0x1F3F4,
        0x1F3F8, 0x1F43E,
        0x1F440, 0x1F440,
        0x1F442, 0x1F4FC,
        0x1F4FF, 0x1F53D,
        0x1F54B, 0x1F54E,
        0x1F550, 0x1F567,
        0x1F57A, 0x1F57A,
        0x1F595, 0x1F596,
        0x1F5A4, 0x1F5A4,
        0x1F5FB, 0x1F64F,
        0x1F680, 0x1F6C5,
        0x1F6CC, 0x1F6CC,
        0x1F6D0, 0x1F6D2,
        0x1F6EB, 0x1F6EC,
        0x1F6F4, 0x1F6FC,
        0x1F7E0, 0x1F7EB,
        0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945,
        0x1F947, 0x1F978,
        0x1F97A, 0x1F9CB,
        0x1F9CD, 0x1F9FF,
        0x1FA70, 0x1FA74,
        0x1FA78, 0x1FA7A,
        0x1FA80, 0x1FA86,
        0x1FA90, 0x1FAA8,
        0x1FAB0, 0x1FAB6,
        0x1FAC0, 0x1FAC2,
        0x1FAD0, 0x1FAD6,
        0x20000, 0x2FFFD,
        0x30000, 0x3FFFD,
    )

    /** NON_SPACING_MARK plus the format characters that take no column. */
    private val ZERO_WIDTH = intArrayOf(
        0x0300, 0x036F,   // combining diacritical marks
        0x0483, 0x0489,   // Cyrillic combining
        0x0591, 0x05BD,
        0x05BF, 0x05BF,
        0x05C1, 0x05C2,
        0x05C4, 0x05C5,
        0x05C7, 0x05C7,
        0x0610, 0x061A,
        0x064B, 0x065F,
        0x0670, 0x0670,
        0x06D6, 0x06DC,
        0x06DF, 0x06E4,
        0x06E7, 0x06E8,
        0x06EA, 0x06ED,
        0x0711, 0x0711,
        0x0730, 0x074A,
        0x07A6, 0x07B0,
        0x07EB, 0x07F3,
        0x0816, 0x0819,
        0x081B, 0x0823,
        0x0825, 0x0827,
        0x0829, 0x082D,
        0x0900, 0x0903,
        0x093A, 0x093C,
        0x093E, 0x094F,
        0x0951, 0x0957,
        0x0962, 0x0963,
        0x0E31, 0x0E31,
        0x0E34, 0x0E3A,
        0x0E47, 0x0E4E,
        0x200B, 0x200F,
        0x202A, 0x202E,
        0x2060, 0x2064,
        0x20D0, 0x20F0,
        0xFE00, 0xFE0F,   // variation selectors
        0xFE20, 0xFE2F,
        0xFEFF, 0xFEFF,   // BOM / zero width no-break space
        0x1AB0, 0x1AFF,
        0x1DC0, 0x1DFF,
        0xE0100, 0xE01EF,
    )

    fun isWide(code: Int): Boolean = inRanges(WIDE, code)

    fun isCombining(code: Int): Boolean = inRanges(ZERO_WIDTH, code)

    /** Columns occupied: 0 for combining marks, 2 for wide, 1 otherwise. */
    fun width(code: Int): Int = when {
        code == 0 || isCombining(code) -> 0
        isWide(code) -> 2
        else -> 1
    }

    private fun inRanges(table: IntArray, code: Int): Boolean {
        var lo = 0
        var hi = table.size / 2
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            when {
                code < table[mid * 2] -> hi = mid
                code > table[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
