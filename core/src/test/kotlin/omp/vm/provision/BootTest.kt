package omp.vm.provision

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateRun
import omp.vm.guestapi.UpdateTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a start of the app does, and — the part that matters — what it refuses to do on its own.
 *
 * The rule under test is a promise to a user: nothing is downloaded without being asked for, and
 * whichever agent answers says so. Both are observable only through what was *not* called, so the
 * launcher here records every run and the assertions are about the count, the order and the
 * sentences in between. **The boot's `omp update` is a separate step with a separate seam, and
 * what it did is the subject of [BootUpdateSequenceTest]; here it is a fake that says the agent is
 * current, so these tests are about the boot's own decisions.**
 */
class BootTest {

    private val proot = ProotCommand(
        prootPath = "/lib/omp/proot",
        rootfs = "/lib/omp/rootfs",
        host = Abi.ARM64,
        guest = Abi.ARM64,
        dataDir = "/data/files",
        visibleDir = "/sdcard/Documents/omp",
        agentDir = "/lib/omp/bin",
    )

    private val runs = ArrayList<List<String>>()

    private val launcher = ProotLauncher { argv, _, _ ->
        runs += argv
        0
    }

    /** The measured success, so a test here is about the boot and not about a failed update. */
    private val transport = UpdateTransport { _, _, _, _ -> UpdateRun(0, "Current version: 18.3.5\n✔ Already up to date\n") }

    /**
     * A disk that takes the record and holds nothing else. **The boot's own tests are about the
     * boot**, and [omp.vm.guestapi.AgentUpdateTest] is where a refused write is the subject.
     */
    private val disk = object : Vfs by RealVfs() {
        override fun readBytes(path: String): ByteArray = throw FsException(FsErrno.NO_SUCH_FILE, path)
        override fun mkdir(path: String) = Unit
        override fun writeBytes(path: String, bytes: ByteArray) = Unit
    }

    private val paths = ProvisionPaths("/data/files", null, "/data/files")

    private fun updater() = AgentUpdate(paths, disk, proot, transport, now = { 1_789_000_000_000L })

    @Test
    fun anUnprovisionedDeviceIsNotDownloadedAndSaysTheKotlinAgentAnswers() {
        val report = boot(Abi.ARM64, ProvisionState.NONE)

        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertTrue("nothing may be launched before anything is installed", runs.isEmpty())
        assertNull(report.updateStatus)
        assertNull(report.runStatus)
        val line = report.lines.first { it.contains("not provisioned") }
        // The exact numbers the user is about to be asked about: the wire cost including the
        // guest's own apt transfer, and the room the same run needs on the device.
        assertTrue(line, line.contains("350,458,048 bytes"))
        assertTrue(line, line.contains("334.2 MiB"))
        assertTrue(line, line.contains("745,403,584 bytes"))
        assertTrue(line, line.contains("not being started without you asking"))
        // LAMP is not bundled with this app, and the line that says so is one a reader sees.
        val lamp = report.lines.first { it.contains("provisioned with LAMP") }
        assertTrue(lamp, lamp.contains("apache2-bin"))
        assertTrue(lamp, lamp.contains("first boot"))
        assertTrue(report.lines.any { it.contains("answering now: the Kotlin agent") })
    }

    @Test
    fun aProvisionedDeviceUpdatesTheAgentAndThenRunsIt() {
        val report = boot(Abi.ARM64, ProvisionState(true, true))

        assertEquals(Answering.REAL_AGENT, report.answering)
        // One launch through this seam, and it is the agent: the update went through its own, and
        // BootUpdateSequenceTest is where the order of the two is pinned.
        assertEquals(1, runs.size)
        assertEquals(listOf("/usr/local/bin/omp"), runs[0].takeLast(1))
        assertEquals(0, report.updateStatus)
        assertEquals(0, report.runStatus)
        assertTrue(report.lines.any { it.contains("omp update in the guest: already-current") })
        assertTrue(report.lines.any { it.contains("the real omp v18.3.4 is answering") })
    }

    @Test
    fun a32BitDeviceWithADebianSaysTheAgentWillNeverBeHere() {
        val report = boot(Abi.ARMEABI_V7A, ProvisionState(rootfsInstalled = true, agentInstalled = false))

        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertTrue(runs.isEmpty())
        assertTrue(report.lines.any { it.contains(Abi.NO_32_BIT_AGENT) })
        assertTrue(report.lines.any { it.contains("answering now: the Kotlin agent") })
    }

    @Test
    fun a32BitDeviceWithoutADebianIsToldTheSizeAndNotStarted() {
        val report = boot(Abi.ARMEABI_V7A, ProvisionState.NONE)

        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertTrue(runs.isEmpty())
        assertTrue(report.lines.any { it.contains("39,098,143 bytes") })
    }

    @Test
    fun anX86DeviceIsToldThereIsNothingToDownloadAtAll() {
        val report = boot(Abi.X86, ProvisionState.NONE)

        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertTrue(runs.isEmpty())
        val line = report.lines.first { it.contains("i386") }
        assertTrue(line, line.startsWith("x86: "))
        assertTrue(line, line.contains(ArtifactManifest.NO_ROOTFS))
        assertTrue(line, line.contains("Nothing was downloaded"))
    }

    @Test
    fun aDeviceThisAppDoesNotKnowIsNotGuessedFor() {
        val report = Boot(null, ProvisionState.NONE, null, proot, launcher, updater()).boot()

        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertTrue(runs.isEmpty())
        assertTrue(report.lines.any { it.contains("does not know") })
    }

    @Test
    fun aRootfsWithNoAgentIsNotDownloadedOnTheWayPast() {
        val report = boot(Abi.ARM64, ProvisionState(rootfsInstalled = true, agentInstalled = false))

        assertEquals(Answering.KOTLIN_AGENT, report.answering)
        assertTrue(runs.isEmpty())
        val line = report.lines.first { it.contains("the agent is not") }
        assertTrue(line, line.contains("234,866,984 bytes"))
        assertTrue(line, line.contains("not being downloaded without you asking"))
    }

    @Test
    fun aProvisionedDeviceSaysWhatTheGuestsLampCostsAndWhetherItIsInThere() {
        val before = boot(Abi.ARM64, ProvisionState(rootfsInstalled = true, agentInstalled = true))

        assertEquals(Answering.REAL_AGENT, before.answering)
        val notYet = before.lines.single { it.contains("LAMP") }
        assertTrue(notYet, notYet.contains("provisioned with LAMP from the Debian archive"))
        assertTrue(notYet, notYet.contains("57,211,704 bytes"))
        assertTrue(notYet, notYet.contains("394,945,536 bytes"))
        // The one thing this class must not do with 57 MB of somebody's mobile data is start it:
        // two runs, and neither of them an apt-get.
        assertTrue(runs.none { it.contains("/usr/bin/apt-get") })

        val after = Boot(
            Abi.ARM64,
            ProvisionState(rootfsInstalled = true, agentInstalled = true, guestInstalled = true),
            ArtifactManifest.of(Abi.ARM64),
            proot,
            launcher,
            updater(),
        ).boot()

        val installed = after.lines.single { it.contains("LAMP") }
        assertTrue(installed, installed.contains("LAMP is installed in the Debian"))
        assertTrue(installed, installed.contains("57,211,704 bytes"))
        // Two boots, two launches: bare `omp` in each, and no apt in either. This class states what
        // the guest's packages cost; it does not spend them.
        assertEquals(2, runs.size)
        assertTrue(runs.none { it.contains("a2enmod") })
        assertTrue(runs.none { it.contains("/usr/bin/apt-get") })
        assertTrue(runs.none { it.contains("a2enmod") })
    }

    @Test
    fun a32BitDeviceWithADebianIsStillToldWhatTheGuestsLampWillCost() {
        val report = boot(
            Abi.ARMEABI_V7A,
            ProvisionState(rootfsInstalled = true, agentInstalled = false),
        )

        val lamp = report.lines.single { it.contains("LAMP") }
        // Nothing has been measured for these packages on this ABI, and the sentence says so
        // rather than borrowing arm64's numbers.
        assertTrue(lamp, lamp.contains("nothing has been measured for LAMP on this ABI"))
        assertFalse(lamp.contains("57,211,704"))
    }

    private fun boot(abi: Abi, state: ProvisionState) =
        Boot(abi, state, ArtifactManifest.of(abi), proot, launcher, updater()).boot()
}
