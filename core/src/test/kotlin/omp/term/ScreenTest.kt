package omp.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A screen wired to capture parser output, clipboard writes and bells. */
internal class TermHarness(rows: Int = 4, cols: Int = 10, scrollback: Int = 50) {
    val output = StringBuilder()
    var clipboard: String? = null
    var bells = 0

    val screen = Screen(rows, cols, scrollback) { bells++ }

    init {
        screen.onOutput = { bytes -> output.append(String(bytes, Charsets.UTF_8)) }
        screen.onClipboard = { text -> clipboard = text }
    }

    fun write(text: String) = screen.write(text)

    fun writeBytes(vararg bytes: Int) = screen.write(ByteArray(bytes.size) { bytes[it].toByte() })

    fun rows(): List<RenderRow> = screen.snapshot()

    fun line(row: Int): String = rows()[row].text().trimEnd()

    fun lines(): List<String> = rows().map { it.text().trimEnd() }

    fun out(): String = output.toString()
}

class ScreenTest {

    // ------------------------------------------------------- the five basics

    @Test
    fun boldRedThenResetLeavesLaterCellsAtDefault() {
        val h = TermHarness(2, 10)
        h.write("\u001B[1;31mhi\u001B[0m")
        val row = h.rows()[0]
        assertTrue(Attr.bold(row.attrs[0]))
        assertEquals(0xFF0000, Attr.fg(row.attrs[0]))
        assertFalse(Attr.fgIsDefault(row.attrs[0]))
        assertEquals('h'.code, row.codes[0])
        assertEquals('i'.code, row.codes[1])
        assertEquals(Attr.DEFAULT_ATTR, row.attrs[2])
        assertTrue(Attr.fgIsDefault(row.attrs[2]))
        assertEquals("hi", h.line(0))
    }

    @Test
    fun eraseDisplayTwoClearsTheScreen() {
        val h = TermHarness(3, 8)
        h.write("abc\r\ndef")
        h.write("\u001B[2J")
        h.rows().forEachIndexed { i, row ->
            assertTrue("row $i is not blank", row.isBlank())
            assertEquals(Attr.DEFAULT_ATTR, row.attrs[0])
            assertEquals(Attr.DEFAULT_ATTR, row.bgs[0])
        }
    }

    @Test
    fun alternateScreenSwapsInAnEmptyBufferAndBack() {
        val h = TermHarness(3, 8)
        h.write("main")
        h.write("\u001B[?1049h")
        assertTrue(h.screen.altScreen)
        assertTrue(h.rows().all { it.isBlank() })
        h.write("alt")
        assertEquals("alt", h.line(0))
        h.write("\u001B[?1049l")
        assertFalse(h.screen.altScreen)
        assertEquals("main", h.line(0))
    }

    @Test
    fun truecolorForegroundIsPackedIntoTheAttribute() {
        val h = TermHarness(2, 8)
        h.write("\u001B[38;2;1;2;3mx")
        assertEquals(rgb(1, 2, 3), Attr.fg(h.rows()[0].attrs[0]))
        assertEquals('x'.code, h.rows()[0].codes[0])
    }

    @Test
    fun utf8CharacterSplitAcrossTwoWritesOccupiesOneCell() {
        val h = TermHarness(2, 8)
        h.writeBytes(0xE2, 0x82)
        h.writeBytes(0xAC)
        assertEquals(0x20AC, h.rows()[0].codes[0])
        assertEquals(1, h.screen.cursorColumn)
        assertEquals("\u20AC", h.line(0))
    }

    // ------------------------------------------------------------------ SGR

    @Test
    fun sgrBoldAndUnderlineClearIndependently() {
        val h = TermHarness(2, 6)
        h.write("\u001B[1;4;31ma\u001B[22mb")
        val row = h.rows()[0]
        assertTrue(Attr.bold(row.attrs[0]))
        assertTrue(Attr.underline(row.attrs[0]))
        assertFalse(Attr.bold(row.attrs[1]))
        assertTrue(Attr.underline(row.attrs[1]))
        assertEquals(0xFF0000, Attr.fg(row.attrs[1]))
        h.write("\u001B[24mc")
        assertFalse(Attr.underline(h.rows()[0].attrs[2]))
        h.write("\u001B[39md")
        assertTrue(Attr.fgIsDefault(h.rows()[0].attrs[3]))
    }

    @Test
    fun sgr256BrightAndBackgroundColoursResolveToRgb() {
        val h = TermHarness(2, 6)
        h.write("\u001B[38;5;196ma")
        assertEquals(rgb(255, 0, 0), Attr.fg(h.rows()[0].attrs[0]))
        h.write("\u001B[90mb")
        assertEquals(rgb(128, 128, 128), Attr.fg(h.rows()[0].attrs[1]))
        h.write("\u001B[91mc")
        assertEquals(rgb(255, 85, 85), Attr.fg(h.rows()[0].attrs[2]))
        h.write("\u001B[44md")
        assertEquals(rgb(0, 0, 255), Attr.bg(h.rows()[0].bgs[3]))
        assertFalse(Attr.bgIsDefault(h.rows()[0].bgs[3]))
        h.write("\u001B[49me")
        assertTrue(Attr.bgIsDefault(h.rows()[0].bgs[4]))
    }

    @Test
    fun eraseKeepsTheBackgroundButDropsTheForeground() {
        val h = TermHarness(2, 6)
        h.write("\u001B[44m\u001B[2J")
        assertEquals(rgb(0, 0, 255), Attr.bg(h.rows()[0].bgs[0]))
        assertEquals(Attr.DEFAULT_ATTR, h.rows()[0].attrs[0])
    }

    // --------------------------------------------------------------- layout

    @Test
    fun autowrapMovesToTheNextLineOnTheCharacterAfterTheLastColumn() {
        val h = TermHarness(3, 4)
        h.write("abcd")
        assertEquals(0, h.screen.cursorRow)
        assertEquals(3, h.screen.cursorColumn)
        assertTrue(h.rows()[0].wrapped)
        h.write("e")
        assertEquals(1, h.screen.cursorRow)
        assertEquals(1, h.screen.cursorColumn)
        assertEquals("abcd", h.line(0))
        assertEquals("e", h.line(1))
    }

    @Test
    fun insertDeleteAndEraseCharsShiftTheRow() {
        val h = TermHarness(2, 8)
        h.write("abcdefgh")
        h.write("\u001B[H\u001B[2P")
        assertEquals("cdefgh", h.line(0))
        h.write("\u001B[H\u001B[2@")
        assertEquals("  cdefgh", h.line(0))
        h.write("\u001B[H\u001B[3X")
        assertEquals("   defgh", h.line(0))
    }

    @Test
    fun insertAndDeleteLinesShiftTheRegion() {
        val h = TermHarness(4, 4)
        h.write("a\r\nb\r\nc\r\nd")
        assertEquals(listOf("a", "b", "c", "d"), h.lines())
        h.write("\u001B[1;1H\u001B[1L")
        assertEquals(listOf("", "a", "b", "c"), h.lines())
        h.write("\u001B[1;1H\u001B[1M")
        assertEquals(listOf("a", "b", "c", ""), h.lines())
    }

    @Test
    fun cursorMovementAndSaveRestoreAgree() {
        val h = TermHarness(6, 12)
        h.write("\u001B[3;4H")
        assertEquals(2, h.screen.cursorRow)
        assertEquals(3, h.screen.cursorColumn)
        h.write("\u001B[2A")
        assertEquals(0, h.screen.cursorRow)
        h.write("\u001B[2;3H\u001B[5d")
        assertEquals(4, h.screen.cursorRow)
        assertEquals(2, h.screen.cursorColumn)
        h.write("\u001B[4G")
        assertEquals(3, h.screen.cursorColumn)
        h.write("\u001B[1;1H\u001B7\u001B[5;5H\u001B8")
        assertEquals(0, h.screen.cursorRow)
        assertEquals(0, h.screen.cursorColumn)
        h.write("\u001B[5;5H\u001B[s\u001B[1;1H\u001B[u")
        assertEquals(4, h.screen.cursorRow)
        assertEquals(4, h.screen.cursorColumn)
    }

    @Test
    fun eraseInLineClearsFromTheCursor() {
        val h = TermHarness(2, 8)
        h.write("abcdefgh")
        h.write("\u001B[1;4H\u001B[K")
        assertEquals("abc", h.line(0))
        h.write("\u001B[1;2H\u001B[1K")
        assertEquals("  c", h.line(0))
        h.write("\u001B[2K")
        assertEquals("", h.line(0))
    }

    @Test
    fun wideCharactersTakeTwoCellsAndCombiningMarksTakeNone() {
        val h = TermHarness(2, 6)
        h.write("\u6F22")
        val row = h.rows()[0]
        assertEquals(0x6F22, row.codes[0])
        assertEquals(WIDE_PAD, row.codes[1])
        assertEquals(2, h.screen.cursorColumn)
        assertEquals("\u6F22", h.line(0))
        h.write("\u0301")
        assertEquals(0x6F22, h.rows()[0].codes[0])
        assertEquals(2, h.screen.cursorColumn)
    }

    @Test
    fun repeatLastPrintedDuplicatesTheLastCharacter() {
        val h = TermHarness(2, 8)
        h.write("x\u001B[2b")
        assertEquals("xxx", h.line(0))
    }

    // ------------------------------------------------------------ scrollback

    @Test
    fun scrolledOffLinesGoToTheRingAndTheViewCanReachThem() {
        val h = TermHarness(3, 5, scrollback = 10)
        h.write("one\r\ntwo\r\nthree\r\nfour")
        assertEquals(listOf("two", "three", "four"), h.lines())
        assertEquals(1, h.screen.scrollbackSize)
        assertEquals(0, h.screen.scrollbackOffset)
        h.screen.scrollUp(1)
        assertEquals(1, h.screen.scrollbackOffset)
        assertEquals(listOf("one", "two", "three"), h.lines())
        h.screen.scrollUp(5)
        assertEquals(1, h.screen.scrollbackOffset)
        h.screen.scrollDown(1)
        assertEquals(0, h.screen.scrollbackOffset)
        h.screen.scrollUp(1)
        h.screen.scrollToBottom()
        assertEquals(0, h.screen.scrollbackOffset)
        h.write("\u001B[3J")
        assertEquals(0, h.screen.scrollbackSize)
    }

    @Test
    fun scrollRegionScrollsOnlyItsOwnRows() {
        val h = TermHarness(5, 6, scrollback = 10)
        h.write("top")
        h.write("\u001B[2;4r")
        assertEquals(0, h.screen.cursorRow)
        h.write("\r\n1\r\n2\r\n3\r\n4")
        assertEquals(listOf("top", "2", "3", "4", ""), h.lines())
        assertEquals(0, h.screen.scrollbackSize)
    }

    // ----------------------------------------------------------------- misc

    @Test
    fun resizeKeepsContentAndResetsTheScrollRegion() {
        val h = TermHarness(3, 8)
        h.write("abc")
        h.screen.resize(5, 4)
        assertEquals(5, h.screen.rows)
        assertEquals(4, h.screen.cols)
        assertEquals(5, h.rows().size)
        assertEquals(4, h.rows()[0].codes.size)
        assertEquals("abc", h.line(0))
        h.screen.resize(1, 1)
        assertEquals(1, h.rows().size)
        assertEquals(1, h.rows()[0].codes.size)
    }

    @Test
    fun dirtyRangeTracksTouchedRowsAndCanBeRearmed() {
        val h = TermHarness(4, 6)
        assertTrue(h.screen.isDirty())
        h.screen.clearDirty()
        assertFalse(h.screen.isDirty())
        h.write("x")
        assertTrue(h.screen.isDirty())
        assertEquals(0, h.screen.dirtyTop)
        assertEquals(1, h.screen.dirtyBottom)
        h.screen.clearDirty()
        h.write("\u001B[3;1Hy")
        assertEquals(2, h.screen.dirtyTop)
        assertEquals(3, h.screen.dirtyBottom)
        h.screen.markAllDirty()
        assertEquals(0, h.screen.dirtyTop)
        assertEquals(4, h.screen.dirtyBottom)
    }

    @Test
    fun invalidUtf8IsReplacedAndTheParserRecovers() {
        val h = TermHarness(2, 8)
        h.writeBytes(0xE2, 'A'.code)
        assertEquals(0xFFFD, h.rows()[0].codes[0])
        assertEquals('A'.code, h.rows()[0].codes[1])
    }

    @Test
    fun aTruncatedCharacterDoesNotSwallowTheEscapeThatFollows() {
        val h = TermHarness(2, 8)
        h.write("x")
        h.writeBytes(0xE2)
        h.write("\u001B[2J")
        assertTrue(h.line(0).isEmpty())
        h.write("\u001B[Hz")
        assertEquals("z", h.line(0))
    }

    @Test
    fun charWidthTableIsSortedAndBinarySearchable() {
        assertTrue(CharWidth.isWide(0x4E00))
        assertTrue(CharWidth.isWide(0xAC00))
        assertTrue(CharWidth.isWide(0xFF21))
        assertFalse(CharWidth.isWide('a'.code))
        assertFalse(CharWidth.isWide(0x20AC))
        assertTrue(CharWidth.isCombining(0x0301))
        assertTrue(CharWidth.isCombining(0xFE0F))
        assertFalse(CharWidth.isCombining('a'.code))
        assertEquals(2, CharWidth.width(0x6F22))
        assertEquals(0, CharWidth.width(0x0301))
        assertEquals(1, CharWidth.width('a'.code))
    }

    @Test
    fun cellEqualityIsAPairOfIntCompares() {
        val attr = Attr.fgSet(Attr.BOLD, 0x123456)
        assertEquals(Cell('a'.code, attr), Cell('a'.code, attr))
        assertFalse(Cell('a'.code, attr) == Cell('a'.code, Attr.DEFAULT_ATTR))
        assertEquals(Attr.DEFAULT_ATTR, 0)
        assertTrue(Attr.bold(attr))
    }

    @Test
    fun renderRowHelpersSkipWidePadding() {
        val h = TermHarness(2, 6)
        h.write("\u6F22x")
        val row = h.rows()[0]
        assertEquals("\u6F22x", h.line(0))
        assertEquals(Cell(0x6F22, row.attrs[0]), row.cellAt(0))
        assertTrue(h.rows()[1].isBlank())
    }

    @Test
    fun ansiHelpersProduceTheSequencesTheShellExpects() {
        assertEquals("\u001B[?1049h", Ansi.ENTER_ALT)
        assertEquals("\u001B[?1049l", Ansi.LEAVE_ALT)
        assertEquals("\u001B[0m", Ansi.SGR_RESET)
        assertEquals("\u001B[H\u001B[2J\u001B[3J", Ansi.CLEAR)
        assertEquals("\u001B[2;3H", Ansi.cursorTo(1, 2))
        assertEquals("\u001B[38;2;1;2;3m", Ansi.truecolor(1, 2, 3))
    }
}

private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
