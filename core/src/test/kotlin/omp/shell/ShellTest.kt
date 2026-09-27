package omp.shell

import omp.shell.exec.ExecContext
import omp.shell.exec.JobGroup
import omp.shell.exec.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * The shell's observable behaviour, pinned on the JVM. Each expectation below marks something that
 * is easy to get subtly wrong and impossible to eyeball in a screenshot.
 */
class ShellTest {

    private companion object {
        const val DOLLAR = "${'$'}"

        /** Long enough for a stage to have started, or for a substitution to be over. */
        const val SETTLE_MS = 200L
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

    @Test
    fun theLastStageDecidesEvenWhenAnEarlierStageFinishesLater() {
        h.reset()
        // A pipeline waits for an earlier stage to drain after the last one has returned, so that
        // stage is still running when the status is read. Finishing late is not deciding: the
        // last stage already had the answer, and only it gets to publish it.
        assertEquals(1, h.run("cat | false", lateStdin()))
        assertEquals(0, h.run("false | cat", lateStdin()))
    }

    @Test
    fun aFailingEarlierStageWithASucceedingLastIsSuccess() {
        h.reset()
        assertEquals(0, h.run("false | true"))
        assertEquals(0, h.run("nosuchcmd | true"))
        assertEquals(0, h.run("cat missing | true"))
    }

    @Test
    fun aThreeStagePipelineReportsItsLastStage() {
        h.reset()
        assertEquals(0, h.run("echo hi | cat | cat"))
        assertEquals("hi\n", run("echo hi | cat | cat"))
        // Two earlier stages, both still reading, both finishing after the last one returned.
        assertEquals(1, h.run("cat | cat | false", lateStdin()))
        assertEquals(0, h.run("false | echo hi | true"))
    }

    @Test
    fun aCancelledLastStageIsNotSuccess() {
        h.reset()
        // One line, because the job removes itself the moment it finishes and `wait` has to be
        // looking at it already. The first stage's success is not this pipeline's status.
        assertEquals(0, h.run("true | sleep 5 & kill -9 ${DOLLAR}!; wait ${DOLLAR}!"))
        assertEquals("130\n", h.stdout())
    }

    @Test
    fun aCancelledEarlierStageLeavesTheLastStagesStatus() {
        h.reset()
        assertEquals(0, h.run("sleep 5 | true &"))
        val job = h.session.job(h.session.lastBackgroundPid)!!
        job.cancel()
        // 130 belongs to the stage that was cancelled, and that stage is not the last one.
        assertEquals(0, job.join())
    }

    @Test
    fun andOrBranchOnTheStatusOfThePipelineTheyRan() {
        h.reset()
        h.run("cat | false && echo no", lateStdin())
        assertEquals(1, h.session.lastStatus)
        assertEquals("", h.stdout())
        assertEquals("yes\n", run("false | true && echo yes"))
        h.reset()
        h.run("cat | false || echo fallback", lateStdin())
        assertEquals("fallback\n", h.stdout())
    }

    @Test
    fun aPipelineInsideAPipelineIsItsOwnStatus() {
        h.reset()
        // The substitution's pipeline fails, and the outer one is still `cat`'s to report.
        assertEquals(0, h.run("echo [${DOLLAR}(cat | false)] | cat", lateStdin()))
        assertEquals("[]\n", h.stdout())
        assertEquals(1, h.run("echo [${DOLLAR}(cat | false)] | false", lateStdin()))
    }

    @Test
    fun aPipelineAfterAFailingCommandTakesItsOwnStatus() {
        h.reset()
        assertEquals(1, h.run("false"))
        assertEquals(1, h.run("true | false"))
        assertEquals(1, h.run("false; cat | false", lateStdin()))
        assertEquals(0, h.run("false; false | true"))
    }


    // ---- interrupting, abandoning, waiting -------------------------------------------------

    @Test
    fun ctrlCReachesTheStageThatIsRunningASubstitution() {
        h.reset()
        // The last stage is a `sleep` that only the interrupt can end, and the first stage is
        // inside a substitution that only the same interrupt can end: both are this one job.
        val status = runAndInterrupt("echo ${DOLLAR}(sleep 5) | sleep 5")
        assertEquals(ExecContext.EXIT_INTERRUPTED, status)
    }

    @Test
    fun aFinishedSubstitutionLeavesTheForegroundSlotAlone() {
        h.reset()
        // `$(echo x)` is over long before the interrupt arrives, and the job it ran inside is
        // still the one an interrupt has to reach: a slot a substitution emptied is an interrupt
        // that silently does nothing.
        val claimed = startInBackground("echo ${DOLLAR}(echo x) | sleep 5")
        Thread.sleep(SETTLE_MS)
        assertTrue("the substitution took the foreground slot", claimed === h.session.foreground)
        interruptForeground(0)
        assertEquals(ExecContext.EXIT_INTERRUPTED, joinForeground())
    }

    @Test
    fun ctrlCInterruptsAPlainPipeline() {
        h.reset()
        assertEquals(ExecContext.EXIT_INTERRUPTED, runAndInterrupt("sleep 5 | sleep 5"))
        // Interrupted, and still the last stage that answers: `true` never sees the flag.
        assertEquals(0, runAndInterrupt("sleep 5 | true"))
    }

    @Test
    fun ctrlCInterruptsASingleForegroundCommand() {
        h.reset()
        assertEquals(ExecContext.EXIT_INTERRUPTED, runAndInterrupt("sleep 5"))
    }

    @Test
    fun aStageThatFinishesJustAfterTheDrainIsAbandonedNotAwaited() {
        h.reset()
        // The first stage outlives the two seconds the shell waits for it by a little, so the
        // shell stops waiting, says so, and still reports the last stage's status.
        assertEquals(1, h.run("cat | false", lateStdin(Shell.STAGE_DRAIN_MS + 300)))
        assertTrue(h.stderr(), h.stderr().contains("sh: abandoned: cat was still running when the last stage finished"))
    }

    @Test
    fun aStageThatFinishesWellAfterTheDrainIsAbandonedNotAwaited() {
        h.reset()
        assertEquals(1, h.run("cat | false", lateStdin(30_000)))
        assertTrue(h.stderr(), h.stderr().contains("sh: abandoned: cat was still running when the last stage finished"))
    }

    @Test
    fun aCancelledStageIsNotAnAbandonedOne() {
        h.reset()
        // The interrupt stops the first stage well inside the drain, so nothing is abandoned and
        // nothing is said about it; the status is still the last stage's.
        assertEquals(0, runAndInterrupt("sleep 5 | cat"))
        assertEquals("", h.stderr().replace("[1] ", ""))
    }

    @Test
    fun waitOnAFinishedBackgroundJobPrintsItsStatus() {
        h.reset()
        h.run("true | false &")
        val pid = h.session.lastBackgroundPid
        awaitFinished(pid)
        h.reset()
        assertEquals(0, h.run("wait $pid"))
        assertEquals("1\n", h.stdout())
    }

    @Test
    fun waitOnALiveBackgroundJobStillWaits() {
        h.reset()
        val file = File(folder.root, "waited.txt")
        h.run("cat > ${file.name} &", lateStdin(300))
        val pid = h.session.lastBackgroundPid
        h.reset()
        assertEquals(0, h.run("wait $pid"))
        assertEquals("late\n", file.readText())
    }

    @Test
    fun waitingTwiceOnTheSameJobGivesTheSameAnswer() {
        h.reset()
        h.run("false &")
        val pid = h.session.lastBackgroundPid
        awaitFinished(pid)
        h.reset()
        assertEquals(0, h.run("wait $pid"))
        assertEquals("1\n", h.stdout())
        h.reset()
        assertEquals(0, h.run("wait $pid"))
        assertEquals("1\n", h.stdout())
    }

    @Test
    fun onlyTheLastFewFinishedBackgroundJobsAreKept() {
        h.reset()
        val first = h.session.nextPid
        for (i in 0..Shell.FINISHED_JOBS_KEPT) h.run("false &")
        val last = h.session.nextPid - 1
        awaitFinished(last)
        awaitGone(first)
        assertTrue("the newest record is still waitable", h.session.job(last) != null)
        // A pid that has aged out is a pid this shell no longer answers for.
        assertEquals(1, h.run("wait $first"))
        assertTrue(h.stderr(), h.stderr().contains("no such job"))
        h.reset()
        assertEquals(0, h.run("wait $last"))
        assertEquals("1\n", h.stdout())
    }

    /** Runs [line] as a REPL would, and interrupts the job it is running the way Ctrl-C does. */
    private fun runAndInterrupt(line: String, stdin: InputStream = ByteArrayInputStream(ByteArray(0))): Int {
        startInBackground(line, stdin)
        interruptForeground()
        return joinForeground()
    }

    private var running: Thread? = null
    private var status = 0

    /** Starts [line] on a thread of its own, the way the REPL runs it, and returns the job it took. */
    private fun startInBackground(line: String, stdin: InputStream = ByteArrayInputStream(ByteArray(0))): JobGroup {
        status = 0
        running = Thread({ status = h.run(line, stdin) }, "repl").apply { isDaemon = true; start() }
        return awaitForeground()
    }

    /** The job the session is blocked on, which is the one a keypress would interrupt. */
    private fun awaitForeground(timeoutMillis: Long = 5000L): JobGroup {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            val job = h.session.foreground
            if (job != null) return job
            Thread.sleep(5)
        }
        throw AssertionError("the shell never took the foreground slot")
    }

    /**
     * What `ShellSession` does with a Ctrl-C byte: cancel whatever the session's slot holds. Not a
     * job this test kept a reference to -- a slot holding the wrong thing, or nothing at all, is
     * the whole defect, and reaching past it would hide exactly that.
     */
    private fun interruptForeground(settleMillis: Long = SETTLE_MS) {
        Thread.sleep(settleMillis)
        val job = h.session.foreground ?: throw AssertionError("a keypress had no job to interrupt")
        job.cancel()
    }

    private fun joinForeground(): Int {
        val t = running ?: throw AssertionError("nothing was started")
        t.join(10_000)
        assertTrue("the interrupt did not stop the job", !t.isAlive)
        return status
    }

    private fun awaitFinished(pid: Int, timeoutMillis: Long = 5000L) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (h.session.job(pid)?.isRunning() == false) return
            Thread.sleep(5)
        }
        throw AssertionError("background job $pid never finished")
    }

    private fun awaitGone(pid: Int, timeoutMillis: Long = 5000L) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (h.session.job(pid) == null) return
            Thread.sleep(5)
        }
        throw AssertionError("background job $pid is still recorded")
    }

    // ---- a substitution is a child -------------------------------------------------------

    @Test
    fun anExitInsideASubstitutionEndsTheSubstitutionOnly() {
        h.reset()
        // The `exit` ends the substitution, which is a child; the program typed around it goes on
        // to its next statement and the session is still there afterwards.
        assertEquals(0, h.run("echo [${DOLLAR}(exit)] && echo after"))
        assertEquals("[]\nafter\n", h.stdout())
        assertTrue("a child's exit ended the session", !h.session.exitRequested)
    }

    @Test
    fun aSubstitutionDoesNotMoveTheStatusTheUserTyped() {
        h.reset()
        // The substitution runs on a stage thread and finishes after the shell has answered the
        // line, which is exactly when a `$?` written from there would be a lie about this one.
        assertEquals(0, h.run("false &"))
        awaitFinished(h.session.lastBackgroundPid)
        assertEquals(0, h.run("x=${DOLLAR}(false) &"))
        awaitFinished(h.session.lastBackgroundPid)
        assertEquals("the substitution moved `$?`", 0, h.session.lastStatus)
        assertEquals(0, h.run("echo ${DOLLAR}?"))
        assertEquals("0\n", h.stdout())
    }

    @Test
    fun aNestedSubstitutionIsScopedTheSameWay() {
        h.reset()
        assertEquals(0, h.run("echo [${DOLLAR}(echo [${DOLLAR}(exit)])] && echo after"))
        assertEquals("[[]]\nafter\n", h.stdout())
        assertTrue("a nested child's exit ended the session", !h.session.exitRequested)
    }

    @Test
    fun theForegroundSlotIsEmptyOnceTheCommandHasReturned() {
        h.reset()
        // What the deleted `^C` echo in `ShellSession` used to look for, and could not find: a job
        // is in the slot only while it is running, so a keypress after the command is a keypress
        // at a prompt, not a cancel of a job that ended.
        h.run("echo ${DOLLAR}(echo x) | cat")
        assertEquals(null, h.session.foreground)
        h.run("sleep 5 | true")
        assertEquals(null, h.session.foreground)
    }

    @Test
    fun anAbandonedStageIsNotReportedAsAFailedCommand() {
        h.reset()
        assertEquals(1, h.run("cat | false", lateStdin(30_000)))
        val said = h.stderr()
        // The verdict is the first word, because `sh: name: ...` is what a failure looks like and
        // this is not one.
        assertTrue(said, said.contains("sh: abandoned: cat was still running when the last stage finished"))
        assertTrue("the stage's failure is dressed up as a command error: $said", !said.contains("sh: cat:"))
    }

    /**
     * A stdin that delivers one line and EOF only after [afterMillis], so a stage reading it is
     * still running when the last stage of its pipeline has already returned. The stub's clock
     * does not move, so `sleep` cannot stand in for a stage that takes a while.
     */
    private fun lateStdin(afterMillis: Long = 300L): InputStream {
        val pipe = PipedInputStream()
        val writer = PipedOutputStream(pipe)
        Thread({
            try {
                Thread.sleep(afterMillis)
                writer.write("late\n".toByteArray())
                writer.flush()
            } catch (e: Exception) {
            } finally {
                Shell.closeQuietly(writer)
            }
        }, "late-stdin").apply { isDaemon = true }.start()
        return pipe
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
