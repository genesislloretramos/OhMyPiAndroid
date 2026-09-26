package omp.shell

import omp.term.Screen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Editing behaviour that a screenshot cannot show, because it is all about key handling. */
class LineEditorTest {

    private lateinit var dir: File
    private lateinit var services: StubPlatformServices
    private lateinit var session: Session
    private lateinit var screen: Screen
    private lateinit var input: InputChannel
    private lateinit var editor: LineEditor

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("omp-editor").toFile()
        services = StubPlatformServices(home = File(dir, "home").path, initialDir = dir.path)
        File(dir, "home").mkdirs()
        screen = Screen(24, 80)
        session = Session(services, screen)
        session.cwd = dir.path
        input = InputChannel()
        editor = LineEditor(session, screen, input)
    }

    private fun read(vararg chunks: String): ReadResult {
        for (c in chunks) input.feed(c.toByteArray(Charsets.UTF_8))
        return editor.readLine("omp:~$ ")
    }

    @Test
    fun ctrlAMovesToTheStartAndTypingInsertsThere() {
        val result = read("abc", CTRL_A, "X", ENTER)
        assertEquals(ReadResult.Line("Xabc"), result)
    }

    @Test
    fun ctrlEMovesToTheEnd() {
        val result = read("abc", CTRL_A, CTRL_E, "Z", ENTER)
        assertEquals(ReadResult.Line("abcZ"), result)
    }

    @Test
    fun backspaceDeletesTheCharacterBeforeTheCursor() {
        val result = read("abc", BACKSPACE, ENTER)
        assertEquals(ReadResult.Line("ab"), result)
    }

    @Test
    fun ctrlKKillsToEndAndCtrlYYanksItBack() {
        val result = read("hello world", CTRL_A, LEFT, LEFT, LEFT, LEFT, LEFT, CTRL_K, CTRL_Y, ENTER)
        assertEquals(ReadResult.Line("hello world"), result)
    }

    @Test
    fun arrowLeftThenInsertLandsInTheMiddle() {
        val result = read("ac", LEFT, "b", ENTER)
        assertEquals(ReadResult.Line("abc"), result)
    }

    @Test
    fun ctrlDOnAnEmptyLineEndsInput() {
        assertEquals(ReadResult.Eof, read(CTRL_D))
    }

    @Test
    fun ctrlCCancelsTheLineWithoutAcceptingIt() {
        assertEquals(ReadResult.Interrupted, read("abc", CTRL_C))
    }

    @Test
    fun upArrowRecallsThePreviousHistoryEntry() {
        session.history.add("echo one")
        assertEquals(ReadResult.Line("echo one"), read(UP, ENTER))
    }

    @Test
    fun tabCompletesACommandNameFromTheRegistry() {
        assertEquals(ReadResult.Line("echo "), read("ec", TAB, ENTER))
    }

    private companion object {
        fun ctrl(n: Int) = n.toChar().toString()
        fun csi(final: Char) = 27.toChar().toString() + "[" + final

        const val ENTER = "\r"
        const val TAB = "\t"
        val CTRL_A = ctrl(0x01)
        val CTRL_E = ctrl(0x05)
        val CTRL_C = ctrl(0x03)
        val CTRL_D = ctrl(0x04)
        val CTRL_K = ctrl(0x0B)
        val CTRL_Y = ctrl(0x19)
        val BACKSPACE = ctrl(0x7F)
        val UP = csi('A')
        val LEFT = csi('D')
    }
}
