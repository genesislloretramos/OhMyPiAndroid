package omp.vm.provision

import omp.shell.fs.RealVfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateOutcome
import omp.vm.guestapi.UpdateRun
import omp.vm.guestapi.UpdateTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `omp update && omp`, in the order the user wrote it, and what is left standing when the first half
 * does not work.
 *
 * **The contract under test is a shell contract, and the only way to see it is to watch what was
 * *not* run.** Both halves are recorded — the update through the [UpdateTransport], the agent
 * through the [ProotLauncher] — and the assertions are about the order, about the count, and about
 * which agent the report says is answering. There is no clock and no network here: the guest's
 * answer is a value the test sets, and the only thing measured is this build's reaction to it. The
 * record is read back off a real filesystem, because the promise that a failed boot is still a
 * report somebody can read afterwards is half of what this step is for.
 */
class BootUpdateSequenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var paths: ProvisionPaths

    /** The two steps, in the order the boot ran them. */
    private val order = ArrayList<String>()

    private var answer = UpdateRun(0, "Current version: 18.3.5\n✔ Already up to date\n")

    private val proot = ProotCommand(
        prootPath = "/data/app/~~kQ==/com.omp.terminal-1==/lib/arm64/libproot.so",
        rootfs = "/data/files/omp/rootfs",
        host = Abi.ARM64,
        guest = Abi.ARM64,
        dataDir = "/data/files",
        visibleDir = "/storage/emulated/0/Documents/omp",
        agentDir = "/data/files/omp/bin",
    )

    private val transport = UpdateTransport { argv, _, _, _ ->
        order += "update"
        // The vector itself is asserted where the command is built, in AgentUpdateTest; what
        // matters here is that the step that ran was the update and not the agent.
        assertEquals(listOf("/usr/local/bin/omp", "update"), argv.takeLast(2))
        answer
    }

    private val launcher = ProotLauncher { _, _, _ ->
        order += "omp"
        0
    }

    @Before
    fun setUp() {
        files = folder.newFolder("files")
        paths = ProvisionPaths(files.path, null, files.path)
    }

    // ---- the order, which is the whole of the contract ---------------------------------------------

    @Test
    fun theUpdateRunsBeforeTheAgentAndTheAgentIsNotRunBeforeIt() {
        val report = boot(ProvisionState(true, true))

        assertEquals(listOf("update", "omp"), order)
        assertEquals(Answering.REAL_AGENT, report.answering)
        assertEquals(0, report.runStatus)
        // The lines read in the order they happened, and the update's is above the line that names
        // the agent: a report that announced the agent before it said how current it was would be
        // the wrong order for the reader asking "is it up to date?".
        val updateAt = report.lines.indexOfFirst { it.startsWith("omp update in the guest:") }
        val agentAt = report.lines.indexOfFirst { it.contains("the real omp") }
        assertTrue("the update's line must be printed, and above the agent's", updateAt >= 0)
        assertTrue("the update must be reported before the agent is announced", updateAt < agentAt)
    }

    @Test
    fun theReportCarriesTheUpdatesOwnNamedOutcomeAndTheVersionTheGuestReported() {
        val report = boot(ProvisionState(true, true))

        // A name out of exactly six, not a sentence a caller has to read: this is the field a
        // notification or a status line branches on.
        assertEquals(UpdateOutcome.ALREADY_CURRENT, report.update)
        assertEquals("18.3.5", report.version)
        assertEquals(0, report.updateStatus)
        // And the same name on the disk, so a boot report and `omp doctor` cannot disagree about
        // what happened — which is the whole reason the outcome is a value and not a sentence.
        assertEquals(report.update.name, AgentUpdate.read(RealVfs(), paths)!!.outcomeName)
    }

    // ---- there is no guest, so the update does not run --------------------------------------------

    @Test
    fun aDeviceWithNoDebianNeverRunsTheUpdateAndStillReportsWhatItWouldHaveDone() {
        val report = boot(ProvisionState.NONE)

        assertEquals("nothing at all may be launched on a device with no guest", emptyList<String>(), order)
        assertEquals(UpdateOutcome.NOT_PROVISIONED, report.update)
        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertNull(report.version)
        assertTrue(report.lines.any { it.startsWith("omp update in the guest: not-provisioned") })
        // The record says it too, so a device that has never been provisioned is distinguishable
        // from one that has been provisioned and whose update failed.
        assertEquals("NOT_PROVISIONED", AgentUpdate.read(RealVfs(), paths)!!.outcomeName)
    }

    @Test
    fun aDebianWithNoAgentIsNotUpdatedAndDoesNotStartAnAgentThatIsNotThere() {
        val report = boot(ProvisionState(rootfsInstalled = true, agentInstalled = false))

        assertEquals(emptyList<String>(), order)
        assertEquals(UpdateOutcome.NO_AGENT, report.update)
        assertEquals(Answering.KOTLIN_AGENT, report.answering)
    }

    // ---- the &&, and the guest that survives it ---------------------------------------------------

    @Test
    fun aFailedUpdateDoesNotStartTheGuestsAgentAndLeavesTheGuestUsable() {
        // The measured failure: status 1, the reason on the guest's own stream, and the version
        // line still printed ahead of it.
        answer = UpdateRun(
            1,
            "Current version: 18.3.5\nFailed to check for updates: TypeError: Unable to connect. " +
                "Is the computer able to access the url?\n",
        )

        val report = boot(ProvisionState(true, true))

        assertEquals("`omp update && omp` stops at the first half", listOf("update"), order)
        assertEquals(UpdateOutcome.FAILED, report.update)
        assertEquals(1, report.updateStatus)
        assertNull("the guest's agent was not started, so it has no status", report.runStatus)
        // The guest is not the agent. These four come up whether or not the update worked, and the
        // line that says so is the whole difference between a failed update and a dead device.
        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        val line = report.lines.first { it.contains("the guest's own omp was not started") }
        assertTrue(line, line.contains("Apache"))
        assertTrue(line, line.contains("PHP API"))
        assertTrue(line, line.contains("'web' command"))
        assertTrue(line, line.contains("do not depend on the agent"))
        // And the failure is a named outcome on a line of its own, carrying the guest's own words.
        val updateLine = report.lines.first { it.startsWith("omp update in the guest: failed") }
        assertTrue(updateLine, updateLine.contains("Unable to connect"))
        // Which is on the disk as well, because nobody was there to read the boot.
        assertEquals("FAILED", AgentUpdate.read(RealVfs(), paths)!!.outcomeName)
    }

    @Test
    fun anUpdateStoppedAtTheBoundIsItsOwnOutcomeAndAlsoDoesNotStartTheAgent() {
        answer = UpdateRun(0, "Current version: 18.3.5\n", stopped = true)

        val report = boot(ProvisionState(true, true))

        assertEquals(listOf("update"), order)
        assertEquals(UpdateOutcome.TIMED_OUT, report.update)
        assertNull("a stopped run has no status of its own", report.updateStatus)
        assertNull(report.runStatus)
        assertEquals(Answering.KOTLIN_AGENT, report.answering)
    }

    @Test
    fun aSuccessfulUpdateIsTheOnlyThingThatLetsTheGuestsAgentStart() {
        answer = UpdateRun(0, "Current version: 18.3.6\n✔ Updated to 18.3.6\n")

        val report = boot(ProvisionState(true, true))

        assertEquals(listOf("update", "omp"), order)
        assertEquals(UpdateOutcome.UPDATED, report.update)
        assertEquals("18.3.6", report.version)
        assertEquals(Answering.REAL_AGENT, report.answering)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun boot(state: ProvisionState) = Boot(
        Abi.ARM64,
        state,
        ArtifactManifest.of(Abi.ARM64),
        proot,
        launcher,
        AgentUpdate(paths, RealVfs(), proot, transport, now = { 1_789_000_000_000L }),
    ).boot()
}
