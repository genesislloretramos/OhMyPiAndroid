package omp.vm.guestapi

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.vm.provision.Abi
import omp.vm.provision.ProotCommand
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The boot's `omp update`: the six outcomes, the bound, the record, and the version.
 *
 * **Every case here is answered by a fake [UpdateTransport] and a real filesystem, with no clock and
 * no network.** The seconds in the class KDoc were measured on `omp/18.3.5` on a laptop; what has to
 * be checked on a JVM is that each of those measurements lands in the right *outcome*, that nothing
 * runs on a device without a guest, and that the record a user reads afterwards says the same thing
 * the boot printed. The transport is a parameter precisely so that a test can hold a guest still
 * while it is being watched.
 *
 * The output strings below are the ones that were measured, character for character:
 * `Current version: 18.3.5` and `✔ Already up to date` on success, and
 * `Failed to check for updates: TypeError: Unable to connect…` with status 1 when the network is
 * gone. Nothing here is a shape somebody imagined.
 */
class AgentUpdateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var paths: ProvisionPaths

    /** Every vector this run handed the transport, and the bound it handed with it. */
    private val vectors = ArrayList<List<String>>()
    private val bounds = ArrayList<Long>()

    /** What the next run of the guest is to answer with. Replaced by a test that needs a different one. */
    private var answer = UpdateRun(0, "Current version: 18.3.5\n✔ Already up to date\n")

    private val transport = UpdateTransport { argv, _, _, boundMs ->
        vectors += argv
        bounds += boundMs
        answer
    }

    private val proot = ProotCommand(
        prootPath = "/data/app/~~kQ==/com.omp.terminal-1==/lib/arm64/libproot.so",
        rootfs = "/data/files/omp/rootfs",
        host = Abi.ARM64,
        guest = Abi.ARM64,
        dataDir = "/data/files",
        visibleDir = "/storage/emulated/0/Documents/omp",
        agentDir = "/data/files/omp/bin",
    )

    @Before
    fun setUp() {
        files = folder.newFolder("files")
        paths = ProvisionPaths(files.path, null, files.path)
    }

    // ---- there is no guest, so there is nothing to update -----------------------------------------

    @Test
    fun aDeviceWithNoDebianNeverRunsTheUpdateAndSaysWhyItDidNot() {
        val report = updater().run(ProvisionState.NONE)

        assertEquals(UpdateOutcome.NOT_PROVISIONED, report.outcome)
        assertTrue("nothing may be launched before there is a guest to launch it in", vectors.isEmpty())
        assertNull(report.version)
        assertNull(report.status)
        assertTrue(report.line, report.line.contains("not-provisioned"))
        assertTrue(report.line, report.line.contains("Nothing was started and nothing was downloaded."))
    }

    @Test
    fun aDebianWithNoAgentInItIsNotUpdatedEitherAndSaysThatIsTheReason() {
        val report = updater().run(ProvisionState(rootfsInstalled = true, agentInstalled = false))

        assertEquals(UpdateOutcome.NO_AGENT, report.outcome)
        assertTrue("a 32-bit device has a Debian and no agent, and must not launch anything", vectors.isEmpty())
        assertTrue(report.line, report.line.contains("no-agent"))
        assertTrue(report.line, report.line.contains(paths.agentBinary))
    }

    // ---- the command, and the flags that are deliberately not there -------------------------------

    @Test
    fun theUpdateRunsInsideTheGuestAndCarriesNoFlagAtAll() {
        updater().run(ProvisionState(true, true))

        assertEquals(1, vectors.size)
        // The guest's own binary and one token. Everything before it is proot's, built by
        // ProotCommand, and the two binds that matter are the rootfs and /usr/local/bin.
        val argv = vectors.single()
        assertEquals(listOf("/usr/local/bin/omp", "update"), argv.takeLast(2))
        // **The pin, and the reason the file fails rather than a phone.** A flag this build cannot
        // quote from `omp update --help` is a flag that silently does nothing, so the guest's part
        // of the vector carries none: `-r`, `-b`, `-w` and `-k` above the binary are proot's, built
        // by ProotCommand out of its own documented options, and are not this class's to choose.
        val command = argv.dropWhile { it != "/usr/local/bin/omp" }
        assertTrue(
            "the boot must pass no flag to the guest: ${command.filter { it.startsWith("-") }}",
            command.none { it.startsWith("-") },
        )
    }

    @Test
    fun theHelpTheNoFlagDecisionCameFromIsTheOneInThisFile() {
        val help = AgentCommand.UPDATE_HELP.joinToString("\n")
        // Every flag `omp update --help` does offer is named here, so the decision not to pass one
        // is a decision a reader can check against the help rather than take on trust.
        for (flag in listOf("-f, --force", "-c, --check", "-l, --plugins", "--canary", "--stable")) {
            assertTrue("`$flag` is in the help this build copied and nowhere in this test", help.contains(flag))
        }
        assertTrue(help, help.startsWith("Check for and install updates"))
    }

    // ---- the four things a run can do -----------------------------------------------------------

    @Test
    fun aRunThatSaysItIsAlreadyCurrentIsItsOwnOutcomeAndRecordsTheVersionItReported() {
        val report = updater().run(ProvisionState(true, true))

        assertEquals(UpdateOutcome.ALREADY_CURRENT, report.outcome)
        assertEquals("18.3.5", report.version)
        assertEquals(0, report.status)
        assertEquals("✔ Already up to date", report.said)
        assertTrue(report.line, report.line.contains("already-current"))
        assertTrue(report.line, report.line.contains("nothing was downloaded"))
    }

    @Test
    fun aRunThatInstalledSomethingSaysUpdatedAndSaysTheVersionAfterIsNotEstablished() {
        // The only success this build has *measured* is the "already current" one, so the output
        // below is deliberately not a real install's: what is under test is the classification and
        // the sentence, not a shape nobody has seen.
        answer = UpdateRun(0, "Current version: 18.3.5\n✔ Updated to 18.3.6\n")

        val report = updater().run(ProvisionState(true, true))

        assertEquals(UpdateOutcome.UPDATED, report.outcome)
        assertEquals("18.3.5", report.version)
        assertTrue(
            report.line,
            report.line.contains("not established by this build"),
        )
    }

    @Test
    fun aFailedRunIsFailedWithTheGuestsOwnWordsAndSaysTheAgentIsUnchanged() {
        // Measured: exit status 1, the reason on the guest's stderr, and the version line still on
        // its stdout — which is why the version is a fact on this path and not a hope.
        answer = UpdateRun(
            1,
            "Current version: 18.3.5\nFailed to check for updates: TypeError: Unable to connect. " +
                "Is the computer able to access the url?\n",
        )

        val report = updater().run(ProvisionState(true, true))

        assertEquals(UpdateOutcome.FAILED, report.outcome)
        assertEquals(1, report.status)
        assertEquals("18.3.5", report.version)
        assertEquals(
            "Failed to check for updates: TypeError: Unable to connect. Is the computer able to access the url?",
            report.said,
        )
        assertTrue(report.line, report.line.contains("exited 1"))
        assertTrue(report.line, report.line.contains("The agent on this device is unchanged at 18.3.5"))
    }

    @Test
    fun aRunStillGoingAtTheBoundIsStoppedAndRecordedWithTheOutcomesOwnNumber() {
        answer = UpdateRun(0, "Current version: 18.3.5\n", stopped = true)

        val report = updater().run(ProvisionState(true, true))

        assertEquals(UpdateOutcome.TIMED_OUT, report.outcome)
        // A stopped process has no status of its own, and the record carries the outcome's fixed
        // number instead of a hole: two boots have to be diffable and 124 is what says "we stopped
        // it" rather than "it failed".
        assertNull("a stopped run has no status of its own", report.status)
        assertEquals("124", AgentUpdate.read(RealVfs(), paths)!!.status)
        assertEquals(UpdateOutcome.TIMED_OUT.exitStatus, 124)
        assertTrue(report.line, report.line.contains("after ${AgentUpdate.BOUND_MS}ms and was stopped"))
    }

    @Test
    fun aGuestThatWroteNothingAtAllIsSaidToHaveWrittenNothing() {
        answer = UpdateRun(1, "")

        val report = updater().run(ProvisionState(true, true))

        assertEquals(UpdateOutcome.FAILED, report.outcome)
        assertNull("there was no line to quote", report.said)
        assertTrue(report.line, report.line.contains("The guest wrote nothing before it stopped."))
    }

    // ---- the bound -------------------------------------------------------------------------------

    @Test
    fun everyRunIsGivenTheSameBoundAndItIsTheOneThisFileStates() {
        updater().run(ProvisionState(true, true))
        updater().run(ProvisionState(true, true))

        // Measured on `omp/18.3.5`: 0.48–0.58 s for a check that finds nothing, 0.23 s for a refused
        // connect. Sixty seconds is a hundredfold over both, and it is deliberately not long enough
        // to cover a 224 MB install on a phone radio — see AgentUpdate's KDoc for what happens then.
        assertEquals(listOf(60_000L, 60_000L), bounds)
        assertEquals(60_000L, AgentUpdate.BOUND_MS)
    }

    // ---- the record, and the version across two boots ---------------------------------------------

    @Test
    fun theRecordIsBesideTheProvisionStateFileAndNotInsideTheRootfs() {
        val at = AgentUpdate.recordFile(paths)

        assertEquals("${paths.downloadDir}/update", at)
        assertEquals("${paths.stateFile.substringBeforeLast('/')}/update", at)
        // Outside the payload, because `omp doctor` reads host paths and a `vm reset` throws the
        // rootfs away: a record inside it would be the one file a user loses with the thing it
        // describes.
        assertFalse(at, at.startsWith(paths.rootfsDir))
    }

    @Test
    fun aSuccessfulRunIsRecordedWhereDoctorCanReadIt() {
        updater().run(ProvisionState(true, true))

        val record = AgentUpdate.read(RealVfs(), paths)!!
        assertEquals("UPDATED/ALREADY_CURRENT as written", "ALREADY_CURRENT", record.outcomeName)
        assertEquals(UpdateOutcome.ALREADY_CURRENT, record.outcome)
        assertEquals("18.3.5", record.version)
        assertEquals("0", record.status)
        assertEquals("${AgentUpdate.BOUND_MS}", record.bound)
        assertEquals("✔ Already up to date", record.said)
        assertTrue("a record with no time in it cannot be ordered against anything", record.at!!.endsWith("Z"))
        // A run that changed nothing carries no `was` line: a number that did not move is not news.
        assertNull(record.was)
    }

    @Test
    fun aVersionThatMovedBetweenTwoBootsIsRecordedOnBothSides() {
        updater().run(ProvisionState(true, true))
        assertNull("nothing moved yet", AgentUpdate.read(RealVfs(), paths)!!.was)

        answer = UpdateRun(0, "Current version: 18.3.6\n✔ Updated to 18.3.6\n")
        updater().run(ProvisionState(true, true))

        val record = AgentUpdate.read(RealVfs(), paths)!!
        // The honest before-and-after, assembled from two boots' own reports rather than invented
        // from one: this build has never watched an install finish, so the two numbers a user wants
        // are the guest's own reports either side of it.
        assertEquals("18.3.6", record.version)
        assertEquals("18.3.5", record.was)
        assertEquals(UpdateOutcome.UPDATED, record.outcome)
    }

    @Test
    fun aRecordThisBuildCannotWriteIsSaidOnTheLineRatherThanThrown() {
        val readOnly = object : Vfs by RealVfs() {
            override fun writeBytes(path: String, bytes: ByteArray) {
                throw FsException(FsErrno.READ_ONLY, path)
            }
        }

        val report = AgentUpdate(paths, readOnly, proot, transport, now = { 0L }).run(ProvisionState(true, true))

        assertEquals(UpdateOutcome.ALREADY_CURRENT, report.outcome)
        assertFalse("the report must say the record did not land", report.recorded)
        assertTrue(report.line, report.line.contains("could not be written to ${AgentUpdate.recordFile(paths)}"))
        assertTrue(report.line, report.line.contains(AgentUpdate.UNREADABLE))
    }

    @Test
    fun aRecordNamingAnOutcomeThisBuildDoesNotHaveIsReadWholeAndNotAsOneOfTheSix() {
        // A record written by a newer build. Reporting its value as one of this build's six would
        // be a lie about a name nobody here chose, so the name is kept and the answer is null.
        File(paths.downloadDir).mkdirs()
        File(AgentUpdate.recordFile(paths)).writeText(
            "${AgentUpdate.RECORD_HEADER}\n${AgentUpdate.KEY_OUTCOME} reinstalled\n",
        )

        val record = AgentUpdate.read(RealVfs(), paths)!!

        assertNull("a name this build does not have is not one of its outcomes", record.outcome)
        assertEquals("reinstalled", record.outcomeName)
    }

    @Test
    fun aRecordWithNoContentIsNoRecordAtAll() {
        File(paths.downloadDir).mkdirs()
        File(AgentUpdate.recordFile(paths)).writeText("${AgentUpdate.RECORD_HEADER}\n")

        assertNull("a file holding nothing but a comment says nothing", AgentUpdate.read(RealVfs(), paths))
    }

    // ---- what a runaway line does -----------------------------------------------------------------

    @Test
    fun aGuestLineTooLongForAReportIsCutAndTheCutIsVisible() {
        val shouty = "!".repeat(4_000)
        answer = UpdateRun(1, "Current version: 18.3.5\n$shouty\n")

        val report = updater().run(ProvisionState(true, true))

        assertTrue("the record is not allowed to be 4 KB of one guest's line", report.said!!.length < 300)
        assertTrue(report.said, report.said.endsWith("(cut)"))
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun updater(boundMs: Long = AgentUpdate.BOUND_MS) =
        AgentUpdate(paths, RealVfs(), proot, transport, boundMs = boundMs, now = { 1_789_000_000_000L })
}
