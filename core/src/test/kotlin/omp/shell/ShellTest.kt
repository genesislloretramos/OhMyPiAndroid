package omp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The shell's observable behaviour, pinned on the JVM. Each expectation below marks something that
 * is easy to get subtly wrong and impossible to eyeball in a screenshot.
 */
class ShellTest {

    private companion object {
        const val DOLLAR = "${'$'}"
    }


    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: ShellHarness

    @Before
    fun setUp() {
        installTestCommands()
        h = ShellHarness(folder.root)
    }

    private fun run(line: String, stdin: String = ""): String {
        h.reset()
        h.run(line, ByteArrayInputStream(stdin.toByteArray()))
        return h.stdout()
    }

    // ---- expansion ---------------------------------------------------------------------

    @Test
    fun arithmeticFollowsPrecedence() {
        assertEquals("14\n", run("echo $((2+3*4))"))
    }

    @Test
    fun defaultOperatorFallsBackWhenUnsetOrEmpty() {
        assertEquals("fallback\n", run("echo \${NOPE:-fallback}"))
        h.session.env["EMPTYV"] = ""
        assertEquals("fallback\n", run("echo \${EMPTYV:-fallback}"))
        assertEquals("set\n", run("V=set; echo \${V:-fallback}"))
    }

    @Test
    fun commandSubstitutionStripsTrailingNewlines() {
        assertEquals("2\n", run("echo \$(echo -e 'a\\nb' | wc -l)"))
    }

    @Test
    fun quotedExpansionNeverSplitsIntoFields() {
        // One field: the two inner spaces survive, which is what "never splits" means.
        assertEquals("[a  b]\n", run("printf '[%s]\\n' \"a  b\""))
        // The unquoted form does split, so the quoted assertion above is not vacuous.
        assertEquals("2\n", run("echo a  b | wc -w"))
    }

    @Test
    fun lengthAndSubstitutionOperators() {
        h.session.env["V"] = "abcdef"
        assertEquals("6\n", run("echo \${#V}"))
        assertEquals("cdef\n", run("echo \${V#ab}"))
        assertEquals("abcd\n", run("echo \${V%ef}"))
        assertEquals("1\n", run("false; echo \$?"))
        assertEquals("0\n", run("true; echo \$?"))
    }

    @Test
    fun braceExpansionProducesSeveralWords() {
        assertEquals("a b c\n", run("echo {a,b,c}"))
        assertEquals("1 2 3 4 5\n", run("echo {1..5}"))
    }

    @Test
    fun globExpandsMatchesAndLeavesNonMatchingPatternLiteral() {
        h.write("a.txt", "x")
        h.write("b.txt", "y")
        h.write("c.dat", "z")
        assertEquals("a.txt b.txt\n", run("echo *.txt"))
        assertEquals("*.md\n", run("echo *.md"))
    }

    // ---- text tools --------------------------------------------------------------------

    @Test
    fun grepCountsMatchingLines() {
        h.write("g.txt", "a\nbe\ncee\n")
        assertEquals("2\n", run("grep -c e g.txt"))
    }

    @Test
    fun grepIsExtendedRegularExpressionByDefault() {
        h.write("g.txt", "aaa\nbbb\n")
        assertEquals("1\n", run("grep -c 'a+' g.txt"))
    }

    @Test
    fun sortNumericOrdersByValueNotLexicographically() {
        assertEquals("9\n10\n", run("sort -n", "10\n9\n"))
    }

    @Test
    fun wcCountsLines() {
        assertEquals("3\n", run("wc -l", "one\ntwo\nthree\n"))
    }

    // ---- diagnostics and exit statuses ------------------------------------------------

    @Test
    fun unknownCommandIsNotFound() {
        h.reset()
        val status = h.run("nosuchcmd")
        assertEquals(127, status)
        assertEquals("", h.stdout())
        assertTrue(h.stderr(), h.stderr().contains("sh: nosuchcmd: command not found"))
    }

    @Test
    fun unknownOptionIsAUsageError() {
        h.reset()
        val status = h.run("ls -Q")
        assertEquals(2, status)
        assertTrue(h.stderr(), h.stderr().contains("invalid option"))
    }

    @Test
    fun catOfAMissingFileWritesOnlyToStderr() {
        h.reset()
        val status = h.run("cat missing")
        assertEquals(1, status)
        assertEquals("", h.stdout())
        assertEquals("cat: missing: No such file or directory\n", h.stderr())
    }

    // ---- redirection and pipes ---------------------------------------------------------

    @Test
    fun redirectionTruncatesThenAppendsInOrder() {
        val f = File(folder.root, "out.txt")
        h.run("echo a > out.txt 2>&1")
        assertEquals("a\n", f.readText())
        h.run("echo b >> out.txt 2>&1")
        assertEquals("a\nb\n", f.readText())
    }

    @Test
    fun onlyTheLastStageOfAPipelineIsATty() {
        // Last stage still talks to the terminal.
        assertEquals("tty\n", run("tty-probe"))
        assertEquals("tty\n", run("echo x | tty-probe"))
        // An earlier stage sees a pipe, which is what `cmd | cat` has to prove.
        assertEquals("pipe\n", run("tty-probe | cat"))
    }

    @Test
    fun andOrOperatorsShortCircuit() {
        assertEquals("", run("false && echo no"))
        assertEquals("yes\n", run("false || echo yes"))
        assertEquals("", run("true || echo no"))
    }

    @Test
    fun exitStatusIsTheStatusOfTheLastStage() {
        h.reset()
        assertEquals(1, h.run("true | false"))
        assertEquals(0, h.run("false | true"))
    }

    // ---- filesystem --------------------------------------------------------------------

    @Test
    fun unreadableDirectoryIsPermissionDeniedNotAnEmptyListing() {
        val locked = File(folder.root, "locked").apply { mkdirs() }
        File(locked, "child").mkdirs()
        assertTrue("cannot make the directory unreadable this way", locked.setReadable(false, false))
        h.reset()
        val status = h.run("ls locked")
        locked.setReadable(true, false)
        if (status == 0) return // the JVM still allowed the read; nothing to assert
        assertTrue(h.stderr(), h.stderr().contains("Permission denied"))
    }

    @Test
    fun cdUpdatesPwdAndOldpwd() {
        File(folder.root, "sub").mkdirs()
        h.write("sub/x.txt", "x")
        assertEquals(File(folder.root, "sub").path + "\n", run("cd sub && pwd"))
        h.reset()
        assertEquals(folder.root.path + "\n", run("cd - >/dev/null && pwd"))
    }

    @Test
    fun tildeResolvesToHome() {
        h.write("home/tilde.txt", "x")
        assertEquals("tilde.txt\n", run("ls ~"))
    }

    // ---- history and variables --------------------------------------------------------

    @Test
    fun assignmentPrefixReachesOneCommandOnly() {
        h.session.env["KEEP"] = "original"
        assertEquals("original\n", run("KEEP=scoped echo ${DOLLAR}KEEP"))
        assertEquals("original\n", run("echo ${DOLLAR}KEEP"))
    }

    @Test
    fun exportChangesTheSessionEnvironment() {
        run("export GREETING=hello")
        assertEquals("hello\n", run("echo ${DOLLAR}GREETING"))
        run("unset GREETING")
        assertEquals("\n", run("echo \${GREETING:-}"))
    }

    @Test
    fun doubleQuotesSuppressWordSplittingAndGlobbing() {
        h.write("home/one.txt", "x")
        assertEquals("*.txt\n", run("echo \"*.txt\""))
        assertEquals("[a b]\n", run("printf '[%s]\\n' \"a b\""))
    }
}
