package omp.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    // -------------------------------------------------------------- bounded

    @Test
    fun aParameterRunLongerThanTheBufferIsAbortedWithSgrReset() {
        val h = TermHarness(2, 10)
        h.write("\u001B[" + "1;".repeat(40) + "mok")
        assertEquals("\u001B[0m", h.out())
        assertEquals("ok", h.line(0))
        h.write("\u001B[31mx")
        assertEquals(0xFF0000, Attr.fg(h.rows()[0].attrs[2]))
    }

    @Test
    fun anUnknownCsiSequenceIsSwallowedAfterTheByteLimit() {
        val h = TermHarness(2, 10)
        h.write("\u001B[" + " ".repeat(40) + "Ahello")
        assertEquals("\u001B[0m", h.out())
        assertEquals("hello", h.line(0))
    }

    @Test
    fun aStringSequenceIsDiscardedWithoutPrintingItsBody() {
        val h = TermHarness(2, 10)
        h.write("\u001BP0;1|abcdefg\u001B\\ok")
        assertEquals("ok", h.line(0))
    }

    @Test
    fun aCharacterSetDesignationIsConsumedNotPrinted() {
        val h = TermHarness(2, 10)
        h.write("\u001B(Ba")
        assertEquals("a", h.line(0))
    }

    @Test
    fun oversizedParametersAreClampedToTheGrid() {
        val h = TermHarness(4, 10)
        h.write("\u001B[999999999;1Hx")
        assertEquals(3, h.screen.cursorRow)
        assertEquals('x'.code, h.rows()[3].codes[0])
    }

    // ------------------------------------------------------------- reports

    @Test
    fun deviceStatusReportAnswersWithTheCursorPosition() {
        val h = TermHarness(4, 10)
        h.write("\u001B[3;5H\u001B[6n")
        assertEquals("\u001B[3;5R", h.out())
    }

    @Test
    fun deviceStatusReportSupportsTheTerminalVariant() {
        val h = TermHarness(4, 10)
        h.write("\u001B[1;1H\u001B[?6n")
        assertEquals("\u001B[?1;1;1R", h.out())
    }

    @Test
    fun deviceAttributesReportIsTheAdvertisedString() {
        val h = TermHarness(2, 10)
        h.write("\u001B[c")
        assertEquals("\u001B[?62;1;2c", h.out())
    }

    @Test
    fun decrqmAnswersSoAProbingToolCannotHang() {
        val h = TermHarness(2, 10)
        h.write("\u001B[?25\$p")
        assertEquals("\u001B[?25;0\$y", h.out())
    }

    // ---------------------------------------------------------------- modes

    @Test
    fun cursorVisibilityIsRemembered() {
        val h = TermHarness(2, 10)
        assertTrue(h.screen.cursorVisible)
        h.write("\u001B[?25l")
        assertFalse(h.screen.cursorVisible)
        h.write("\u001B[?25h")
        assertTrue(h.screen.cursorVisible)
    }

    @Test
    fun disablingAutowrapMakesTheLastColumnOverwrite() {
        val h = TermHarness(3, 4)
        h.write("\u001B[?7l")
        assertFalse(h.screen.autoWrap)
        h.write("abcdX")
        assertEquals(0, h.screen.cursorRow)
        assertEquals("abcX", h.line(0))
        assertEquals("", h.line(1))
        h.write("\u001B[?7h")
        h.write("\u001B[2J\u001B[HabcdX")
        assertEquals("abcd", h.line(0))
        assertEquals("X", h.line(1))
    }

    @Test
    fun bracketedPasteAndMouseModesAreRecordedButNeverReport() {
        val h = TermHarness(2, 10)
        assertFalse(h.screen.bracketedPaste)
        assertEquals(0, h.screen.mouseTracking)
        h.write("\u001B[?2004h")
        assertTrue(h.screen.bracketedPaste)
        h.write("\u001B[?1002h\u001B[?1006h")
        assertEquals(1002, h.screen.mouseTracking)
        assertEquals("", h.out())
        h.write("\u001B[?1002l")
        assertEquals(0, h.screen.mouseTracking)
        h.write("\u001B[?2004l")
        assertFalse(h.screen.bracketedPaste)
    }

    @Test
    fun mode47SwapsToTheAlternateBufferToo() {
        val h = TermHarness(2, 8)
        h.write("main")
        h.write("\u001B[?47h")
        assertTrue(h.screen.altScreen)
        assertEquals("", h.line(0))
        h.write("\u001B[?47l")
        assertFalse(h.screen.altScreen)
        assertEquals("main", h.line(0))
    }

    @Test
    fun insertModeShiftsTextRightWhenTheCursorMovesBack() {
        val h = TermHarness(2, 8)
        h.write("abc")
        h.write("\u001B[H\u001B[4hX")
        assertEquals("Xabc", h.line(0))
        h.write("\u001B[4l\u001B[H\u001B[1@")
        assertEquals(" Xabc", h.line(0))
    }

    @Test
    fun newlineModeReturnsTheCarriage() {
        val h = TermHarness(3, 8)
        h.write("ab")
        h.write("\u001B[20h\nc")
        assertEquals("c", h.line(1))
    }

    @Test
    fun bellReachesTheHook() {
        val h = TermHarness(2, 10)
        h.write("\u0007x\u0007")
        assertEquals(2, h.bells)
        assertEquals("x", h.line(0))
    }

    @Test
    fun risResetsTheScreenAndTheModes() {
        val h = TermHarness(3, 8)
        h.write("\u001B[?25l\u001B[?7labc")
        h.write("\u001Bc")
        assertEquals("", h.line(0))
        assertTrue(h.screen.cursorVisible)
        assertTrue(h.screen.autoWrap)
    }

    // ------------------------------------------------------------------ OSC

    @Test
    fun oscTitleIsSetFromBothTerminators() {
        val h = TermHarness(2, 10)
        h.write("\u001B]0;omp shell\u0007")
        assertEquals("omp shell", h.screen.title)
        h.write("\u001B]2;second\u001B\\")
        assertEquals("second", h.screen.title)
        h.write("\u001B]1;icon\u0007")
        assertEquals("icon", h.screen.title)
    }

    @Test
    fun oscClipboardWriteGoesToTheCallbackAndAReadIsRefused() {
        val h = TermHarness(2, 10)
        h.write("\u001B]52;c;aGVsbG8=\u0007")
        assertEquals("hello", h.clipboard)
        h.write("\u001B]52;c;?\u0007")
        assertEquals("hello", h.clipboard)
        assertEquals("\u001B]52;c;OSC 52 read is disabled\u0007", h.out())
    }

    @Test
    fun oscPaletteEntriesCanBeSetAndQueried() {
        val h = TermHarness(2, 10)
        h.write("\u001B]4;1;#ff8800\u0007")
        h.write("\u001B]4;1;?\u0007")
        assertEquals("\u001B]4;1;rgb:255/136/0\u0007", h.out())
        h.write("\u001B]4;2;rgb:00/ff/00\u0007")
        h.write("\u001B]4;2;?\u0007")
        assertTrue(h.out().endsWith("\u001B]4;2;rgb:0/255/0\u0007"))
    }

    @Test
    fun oscHyperlinkMarkersAreConsumedAndTheLabelStaysPlain() {
        val h = TermHarness(2, 10)
        h.write("\u001B]8;;https://example.com\u0007link\u001B]8;;\u0007!")
        assertEquals("link!", h.line(0))
        assertEquals("", h.out())
    }

    // ------------------------------------------------------------- defaults

    @Test
    fun omittedParametersFallBackToTheXtermDefaults() {
        val h = TermHarness(4, 10)
        h.write("abc\u001B[1;2H\u001B[J")
        assertEquals("a", h.line(0))
        h.write("\u001B[H\u001B[2bz")
        assertEquals("ccz", h.line(0))
    }

    @Test
    fun partialControlCharactersBehaveLikeATerminal() {
        val h = TermHarness(3, 10)
        h.write("ab\bX")
        assertEquals("aX", h.line(0))
        h.write("\r\tY")
        assertEquals("aX      Y", h.line(0))
        h.write("\r\nZ")
        assertEquals("Z", h.line(1))
    }
}
