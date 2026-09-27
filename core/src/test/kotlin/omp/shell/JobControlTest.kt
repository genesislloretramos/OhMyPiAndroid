package omp.shell

import omp.shell.exec.ExecContext
import omp.shell.exec.Shell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the three job-control commands each answer about the same record.
 *
 * A finished background job stays on the index so `wait <pid>` can report it, and that is the whole
 * of its purpose. `jobs` and `kill` read the same index and have to say something different about a
 * job that is over: a list of jobs that are not running, and a `kill` that says it stopped one.
 */
class JobControlTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: ShellHarness

    @Before
    fun setUp() {
        installTestCommands()
        h = ShellHarness(folder.root)
    }

    /** Whatever is still running is asked to stop, so a test does not leave one going. */
    @After
    fun stopWhatIsLeftRunning() {
        for (job in h.session.jobs()) job.cancel()
    }

    // ---- jobs ------------------------------------------------------------------------------

    @Test
    fun jobsDoesNotListAJobThatHasFinished() {
        h.run("false &")
        awaitFinished(h.session.lastBackgroundPid)
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("jobs"))
        assertEquals("a finished job was listed as one this shell is running", "", h.stdout())
    }

    @Test
    fun jobsListsAJobThatIsStillRunning() {
        // The stub clock does not move, so `sleep` runs until something stops it.
        h.run("false &")
        awaitFinished(h.session.lastBackgroundPid)
        h.run("sleep 5 &")
        val live = h.session.lastBackgroundPid
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("jobs"))
        // Numbered from one over what is shown, not over what is on record: the finished job held
        // the first slot before it left.
        assertEquals("[1]  $live  sleep\n", h.stdout())
    }

    // ---- kill ------------------------------------------------------------------------------

    @Test
    fun killOnAJobThatHasFinishedDoesNotReportSuccess() {
        h.run("false &")
        val pid = h.session.lastBackgroundPid
        awaitFinished(pid)
        h.reset()
        assertEquals(ExecContext.EXIT_GENERAL_ERROR, h.run("kill $pid"))
        assertTrue(h.stderr(), h.stderr().contains("has already finished"))
        // It did exist, so the words used for a pid this shell never had would be the false ones.
        assertFalse(h.stderr(), h.stderr().contains("No such process"))
    }

    @Test
    fun killOnALiveJobStillStopsIt() {
        h.run("sleep 5 &")
        val pid = h.session.lastBackgroundPid
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("kill $pid"))
        assertEquals("", h.stderr())
        // Stopped, and stopped by the flag: the job ends by itself, on the interrupt's status.
        awaitFinished(pid)
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("wait $pid"))
        assertEquals("${ExecContext.EXIT_INTERRUPTED}\n", h.stdout())
    }

    @Test
    fun killOnAPidThisShellNeverHadStillSaysNoSuchProcess() {
        h.reset()
        assertEquals(ExecContext.EXIT_GENERAL_ERROR, h.run("kill 999999"))
        assertTrue(h.stderr(), h.stderr().contains("kill: (999999) - No such process"))
        h.reset()
        assertEquals(ExecContext.EXIT_GENERAL_ERROR, h.run("kill notapid"))
        assertTrue(h.stderr(), h.stderr().contains("kill: (notapid) - No such process"))
    }

    // ---- wait, which the other two deliberately do not reach --------------------------------

    @Test
    fun waitStillAnswersForAJobThatJobsAndKillRefuse() {
        h.run("false &")
        val pid = h.session.lastBackgroundPid
        awaitFinished(pid)
        h.reset()
        // One record, three answers: not in the list, refused by kill, still answerable by wait.
        assertEquals("", runAndStdout("jobs"))
        assertEquals(ExecContext.EXIT_GENERAL_ERROR, h.run("kill $pid"))
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("wait $pid"))
        assertEquals("1\n", h.stdout())
        // And the second time is the same question with the same answer.
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("wait $pid"))
        assertEquals("1\n", h.stdout())
    }

    @Test
    fun onlyTheNewestThirtyTwoFinishedJobsStayWaitable() {
        val first = h.session.nextPid
        for (i in 0..Shell.FINISHED_JOBS_KEPT) h.run("false &")
        val last = h.session.nextPid - 1
        awaitFinished(last)
        awaitAllFinished()
        awaitGone(first)
        // Thirty-two and thirty-two: the oldest is gone, so it is a bound, and the second oldest is
        // kept, so the bound has not crept down either.
        assertTrue("the second oldest record was dropped too", h.session.job(first + 1) != null)
        h.reset()
        assertEquals(ExecContext.EXIT_GENERAL_ERROR, h.run("wait $first"))
        assertTrue(h.stderr(), h.stderr().contains("no such job"))
        h.reset()
        assertEquals(ExecContext.EXIT_OK, h.run("wait $last"))
        assertEquals("1\n", h.stdout())
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Runs [line] with the streams cleared and hands back what it printed. */
    private fun runAndStdout(line: String): String {
        h.reset()
        h.run(line)
        return h.stdout()
    }

    private fun awaitFinished(pid: Int, timeoutMillis: Long = 5000L) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (h.session.job(pid)?.isRunning() == false) return
            Thread.sleep(5)
        }
        throw AssertionError("background job $pid never finished")
    }

    /** Waits until nothing this session started is still running, so the bound has settled. */
    private fun awaitAllFinished(timeoutMillis: Long = 5000L) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (h.session.jobs().none { it.isRunning() }) return
            Thread.sleep(5)
        }
        throw AssertionError("a background job was still running")
    }

    private fun awaitGone(pid: Int, timeoutMillis: Long = 5000L) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (h.session.job(pid) == null) return
            Thread.sleep(5)
        }
        throw AssertionError("background job $pid is still recorded")
    }
}
