package omp.vm.doctor

import omp.shell.PlatformServices
import omp.shell.StubPlatformServices
import omp.shell.fs.RealVfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateOutcome
import omp.vm.provision.Abi
import omp.vm.provision.ProvisionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The `agent update` section, for the four devices a user can actually be holding.
 *
 * **A cold device, a device whose update never ran, one whose update ran and worked, and one whose
 * update ran and failed** are four different reports, and a section that printed the same shape for
 * all four would be worth nothing to the person reading it. Every value is pinned, line by line, for
 * the reason [omp.vm.doctor.DoctorTest] gives: a report whose lines move is a report nobody can
 * diff between two runs.
 *
 * **The column is built with the padding the report itself uses** rather than counted out by hand,
 * because what is under test here is what each line says; [omp.vm.doctor.DoctorTest] pins the width
 * itself, in a whole report, against literals.
 *
 * The records are written by hand rather than by [omp.vm.guestapi.AgentUpdate], because the subject
 * is what this command *reads* — including a record this build could not have written, which is the
 * fifth case here and the one that keeps the other four honest.
 */
class AgentUpdateSectionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var paths: ProvisionPaths
    private lateinit var services: PlatformServices

    @Before
    fun setUp() {
        files = folder.newFolder("files")
        val exec = folder.newFolder("lib", "arm64-v8a")
        val stub = StubPlatformServices(
            home = File(files, "home").path,
            initialDir = File(files, "home").path,
            appFiles = files.path,
        )
        stub.props[Doctor.FINGERPRINT] = "google/stub/stub:14/UP1A.231005.007/1:user/release-keys"
        stub.props[Doctor.ABILIST] = Abi.ARM64.abiName
        paths = ProvisionPaths(files.path, exec.path, files.path)
        services = stub
    }

    // ---- 1. a cold device: the app has never started here ---------------------------------------

    @Test
    fun aDeviceWithNoRecordSaysTheUpdateHasNeverRunAndNotThatItFailed() {
        provisionGuest()

        val report = doctor().report()

        assertEquals(
            report.text(),
            say("record", "${record()} — not there"),
            line(report, "record"),
        )
        assertEquals(
            report.text(),
            say(
                "outcome",
                "never: no record at ${record()}, so this build has not run 'omp update' in a guest " +
                    "on this device",
            ),
            line(report, "outcome"),
        )
        assertEquals(
            report.text(),
            say(
                "version",
                "none: nothing has run in a guest, so no version of the agent in one has been measured",
            ),
            line(report, "version"),
        )
        // Not a gap: the disk answered, and "this has not run" is an answer about the device rather
        // than a fact this run failed to read. A gap here would file a cold device in the same
        // bucket as a directory the filesystem would not open.
        assertTrue(report.text(), report.gaps.none { it.contains("omp update") })
    }

    // ---- 2. a device whose update never ran, and said why ---------------------------------------

    @Test
    fun aDeviceThatIsNotProvisionedSaysTheUpdateDidNotRunAndSaysWhy() {
        provisionGuest()
        writeRecord(
            AgentUpdate.KEY_OUTCOME to UpdateOutcome.NOT_PROVISIONED.name,
            AgentUpdate.KEY_AT to "2026-09-27T18:02:11Z",
            AgentUpdate.KEY_BOUND to "60000",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "outcome",
                "${UpdateOutcome.NOT_PROVISIONED.name}: 'omp update' did not run, because there is no " +
                    "unpacked Debian on this device to run it in; it wrote no line of its own",
            ),
            line(report, "outcome"),
        )
        assertEquals(
            report.text(),
            say("version", "none: the guest printed no 'Current version:' line, because nothing ran"),
            line(report, "version"),
        )
        assertEquals(
            report.text(),
            say(
                "last run",
                "at 2026-09-27T18:02:11Z, no status recorded, and this build cannot say what it was, " +
                    "bound 60000ms",
            ),
            line(report, "last run"),
        )
    }

    // ---- 3. a device whose update ran and succeeded ---------------------------------------------

    @Test
    fun aDeviceWhoseUpdateRanAndFoundTheAgentCurrentPrintsTheVersionItReported() {
        provisionGuest()
        writeRecord(
            AgentUpdate.KEY_OUTCOME to UpdateOutcome.ALREADY_CURRENT.name,
            AgentUpdate.KEY_AT to "2026-09-27T18:02:11Z",
            AgentUpdate.KEY_VERSION to "18.3.5",
            AgentUpdate.KEY_SAID to "✔ Already up to date",
            AgentUpdate.KEY_STATUS to "0",
            AgentUpdate.KEY_BOUND to "60000",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "outcome",
                "${UpdateOutcome.ALREADY_CURRENT.name}: 'omp update' ran and said the agent was " +
                    "already current, so nothing was downloaded; its own last line was " +
                    "\"✔ Already up to date\"",
            ),
            line(report, "outcome"),
        )
        assertEquals(
            report.text(),
            say("version", "18.3.5 is what the guest reported for itself"),
            line(report, "version"),
        )
        assertEquals(
            report.text(),
            say("last run", "at 2026-09-27T18:02:11Z, status 0, bound 60000ms"),
            line(report, "last run"),
        )
    }

    @Test
    fun aVersionThatMovedBetweenTwoBootsIsPrintedOnBothSidesAndTheAfterIsNotClaimed() {
        provisionGuest()
        writeRecord(
            AgentUpdate.KEY_OUTCOME to UpdateOutcome.UPDATED.name,
            AgentUpdate.KEY_AT to "2026-09-27T18:02:11Z",
            AgentUpdate.KEY_VERSION to "18.3.6",
            AgentUpdate.KEY_WAS to "18.3.5",
            AgentUpdate.KEY_STATUS to "0",
            AgentUpdate.KEY_BOUND to "60000",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "outcome",
                "${UpdateOutcome.UPDATED.name}: 'omp update' ran, exited 0 and did not say the agent " +
                    "was already current, so it installed something; it wrote no line of its own",
            ),
            line(report, "outcome"),
        )
        // Both numbers, and the limit stated: this build has never watched an install finish, so the
        // newer number is what the guest reported and not a claim about what the install left behind.
        assertEquals(
            report.text(),
            say(
                "version",
                "18.3.6 is what the guest reported for itself, and 18.3.5 is what the run before it " +
                    "reported; what a run that installs leaves behind is not established by this build",
            ),
            line(report, "version"),
        )
    }

    // ---- 4. a device whose update ran and failed -------------------------------------------------

    @Test
    fun aFailedUpdateIsPrintedWithTheGuestsOwnWordsAndSaysTheAgentIsUnchanged() {
        provisionGuest()
        writeRecord(
            AgentUpdate.KEY_OUTCOME to UpdateOutcome.FAILED.name,
            AgentUpdate.KEY_AT to "2026-09-27T18:02:11Z",
            AgentUpdate.KEY_VERSION to "18.3.5",
            AgentUpdate.KEY_SAID to "Failed to check for updates: TypeError: Unable to connect. " +
                "Is the computer able to access the url?",
            AgentUpdate.KEY_STATUS to "1",
            AgentUpdate.KEY_BOUND to "60000",
        )

        val report = doctor().report()

        assertEquals(
            report.text(),
            say(
                "outcome",
                "${UpdateOutcome.FAILED.name}: 'omp update' ran and exited 1, and the agent on this " +
                    "device is unchanged; its own last line was \"Failed to check for updates: " +
                    "TypeError: Unable to connect. Is the computer able to access the url?\"",
            ),
            line(report, "outcome"),
        )
        assertEquals(
            report.text(),
            say("version", "18.3.5 is what the guest reported for itself"),
            line(report, "version"),
        )
    }

    // ---- the fifth case: a record this build did not write ---------------------------------------

    @Test
    fun aRecordNamingAnOutcomeThisBuildDoesNotHaveIsUnreadableAndNamedInTheGaps() {
        provisionGuest()
        writeRecord(
            AgentUpdate.KEY_OUTCOME to "reinstalled",
            AgentUpdate.KEY_AT to "2026-09-27T18:02:11Z",
        )

        val report = doctor().report()

        // Reported as a value this build cannot interpret, and never as one of its six: a name from
        // a newer build is not a failure and must not be read as one.
        assertEquals(
            report.text(),
            say(
                "outcome",
                "unreadable: the record at ${record()} names an outcome 'reinstalled', which is not " +
                    "one of this build's UpdateOutcome " +
                    "(${UpdateOutcome.entries.joinToString(", ") { it.name }}), so this build cannot " +
                    "say what happened",
            ),
            line(report, "outcome"),
        )
        assertTrue(
            report.text(),
            report.gaps.any { it.contains("names an outcome 'reinstalled'") },
        )
    }

    // ---- where the section sits -------------------------------------------------------------------

    @Test
    fun theUpdateSectionPrintsBeforeTheGuestsOwnOriginSoTwoReportsCanBeDiffed() {
        provisionGuest()
        writeRecord(
            AgentUpdate.KEY_OUTCOME to UpdateOutcome.ALREADY_CURRENT.name,
            AgentUpdate.KEY_VERSION to "18.3.5",
        )

        val heads = doctor().report().lines.filter { it.isNotBlank() && !it.startsWith(" ") }

        assertEquals(
            listOf(
                "omp doctor: read-only — nothing below downloads, writes, starts or stops anything",
                "identity",
                "guest",
                "helper",
                "provisioning",
                "guest state",
                Doctor.SECTION_UPDATE,
                Doctor.SECTION_ORIGIN,
                "chat",
                "gaps",
                "next",
            ),
            heads,
        )
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun doctor() = Doctor(services, RealVfs(), paths, probe = Doctor.Probe { false })

    private fun record() = AgentUpdate.recordFile(paths)

    /**
     * One line of this section, found after its heading.
     *
     * **Scoped to the section because `version:` is a key in two of them** — the identity section has
     * one too, and a helper that took the first match in the report would quietly assert about the
     * wrong line.
     */
    private fun line(report: Doctor.Report, key: String): String {
        val head = report.lines.indexOf(Doctor.SECTION_UPDATE)
        return report.lines.drop(head + 1).first { it.startsWith("  $key:") }
    }

    /** One `key: value` as the report writes it, so no column is counted out by hand here. */
    private fun say(key: String, value: String) = "  " + (key + ":").padEnd(Doctor.WIDTH) + value

    /** Enough of a provisioned device that the sections above this one have something to say. */
    private fun provisionGuest() {
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsDir, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
        File(paths.agentDir).mkdirs()
        File(paths.agentBinary).writeBytes(ByteArray(1_024))
    }

    private fun writeRecord(vararg lines: Pair<String, String>) {
        File(paths.downloadDir).mkdirs()
        File(record()).writeText(
            buildString {
                append(AgentUpdate.RECORD_HEADER).append('\n')
                for ((key, value) in lines) append(key).append(' ').append(value).append('\n')
            },
        )
    }
}
