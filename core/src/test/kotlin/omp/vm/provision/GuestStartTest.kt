package omp.vm.provision

import omp.shell.fs.RealVfs
import omp.vm.guestapi.AgentUpdate
import omp.vm.guestapi.UpdateOutcome
import omp.vm.guestapi.UpdateRun
import omp.vm.guestapi.UpdateTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The guest start: the six states, what is launched in each of them, and what is written down
 * afterwards.
 *
 * **What is under test is the order and the refusals, not the guest.** Every step below this class
 * is a `ProcessBuilder` on a phone, and the two that a test can see — a port check and a probe — are
 * both parameters. That is what makes the interesting cases reachable here: a device with no Debian,
 * a port somebody else holds, a server that was launched and answered nothing, and a guest whose
 * agent update failed. All four are ordinary things for a user to be holding and none of them can
 * be produced by a build machine.
 *
 * **No sleeps, no sockets and no processes.** The clock is a lambda, the probe and the binder are
 * lambdas, and the only filesystem is a [org.junit.rules.TemporaryFolder] a [omp.shell.fs.RealVfs]
 * is pointed at — the same arrangement [omp.vm.provision.BootUpdateSequenceTest] uses for the same
 * reason.
 */
class GuestStartTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var paths: ProvisionPaths
    private val disk = RealVfs()

    /** Everything that was launched, in the order it was launched, as readable words. */
    private val ran = ArrayList<String>()

    /** What the guest's `omp update` will say. */
    private var update = UpdateRun(0, "Current version: 18.3.5\n✔ Already up to date\n")

    /** Whether the reserved port is free, and the port each call was asked about. */
    private var portFree = true
    private val portsAsked = ArrayList<Int>()

    /** Whether a page came back, and the port each probe was asked about. */
    private var serving = false
    private val portsProbed = ArrayList<Int>()

    /** Whether the Apache process comes up, and what it says if it does not. */
    private var apacheStarts = true
    private var apacheSaid: String? = null

    /** The vectors the guest was launched with, so the command is asserted where it is built. */
    private val apacheArgv = ArrayList<List<String>>()

    private val proot = ProotCommand(
        prootPath = "/data/app/~~kQ==/com.omp.terminal-1==/lib/arm64/libproot.so",
        rootfs = "/data/files/omp/rootfs",
        host = Abi.ARM64,
        guest = Abi.ARM64,
        dataDir = "/data/files",
        visibleDir = "/storage/emulated/0/Documents/omp",
        agentDir = "/data/files/omp/bin",
    )

    /** The steps that finish — `a2enconf` and the guest's own `omp update` — through one seam. */
    private val launcher = ProotLauncher { argv, _, _ ->
        // `a2enconf` is launched as `/usr/sbin/a2enconf <name>`, so the last word is the snippet it was
        // asked to link in rather than the tool. Both are pinned where the vector is built, in
        // `GuestPackagesTest`; what this class cares about is that the step ran and in what order.
        ran += argv.takeLast(2).joinToString(" ")
        0
    }

    private val transport = UpdateTransport { _, _, _, _ ->
        ran += "omp update"
        update
    }

    private val server = GuestServer { argv, _, _ ->
        ran += "apache"
        apacheArgv += argv
        if (apacheStarts) GuestLaunch(true) else GuestLaunch(false, apacheSaid)
    }

    @Before
    fun setUp() {
        files = folder.newFolder("files")
        paths = ProvisionPaths(files.path, null, files.path)
    }

    // ---- nothing to start -------------------------------------------------------------------------

    @Test
    fun aDeviceWithNoDebianLaunchesNothingAtAllAndSaysWhy() {
        // The whole point of a stage at start-up: a phone that has never been provisioned must cost
        // nothing. Not a bind check, not an `a2enconf`, not an `omp update` — nothing is launched,
        // and a start that launched an `omp update` here would be launching a binary in a rootfs
        // that does not exist.
        val report = start()

        assertEquals(GuestState.NO_DEBIAN, report.state)
        assertEquals(emptyList<String>(), ran)
        assertFalse("nothing was reserved, so nothing could collide", report.reserved)
        assertFalse(report.serving)
        assertTrue(report.lines.single().contains("there is no unpacked Debian"))
    }

    @Test
    fun aDeviceWithNoDebianIsNotAskedAboutItsPortAtAll() {
        start()

        // The order is the order of the checks, and this is the first: with no Debian there is no
        // reason to ask whether a port is free, and a binder that answered on this path would be a
        // binder being consulted about a guest that does not exist.
        assertEquals(emptyList<Int>(), portsAsked)
        assertEquals(emptyList<Int>(), portsProbed)
    }

    // ---- the collision ------------------------------------------------------------------------------

    @Test
    fun aPortSomebodyElseHoldsIsRefusedAndNamedRatherThanReplaced() {
        provisionGuest()
        portFree = false

        val report = start()

        assertEquals(GuestState.PORT_TAKEN, report.state)
        // Nothing was launched. A collision that started the guest anyway would be starting an Apache
        // on a number something else is holding, which fails in a way that looks like a broken Debian.
        assertEquals(emptyList<String>(), ran)
        assertEquals(listOf(GuestWeb.RESERVED_PORT), portsAsked)
        assertFalse(report.serving)
    }

    @Test
    fun aCollisionSaysInWordsThatTheAppsOwnServerIsNotTheAnswer() {
        provisionGuest()
        portFree = false

        val report = start()

        // The specific lie this whole path exists to prevent: a user told the real agent is answering
        // when the Kotlin one is. The refusal has to say both halves — the port, and that nothing is
        // standing in for it.
        val line = report.lines.last()
        assertTrue(line, line.contains("http://127.0.0.1:${GuestWeb.RESERVED_PORT}"))
        assertTrue(line, line.contains("already held"))
        assertTrue(line, line.contains("does not show its own loopback server in its place"))
    }

    @Test
    fun aCollisionIsRecordedAsItsOwnStateSoTheDoctorCanNameIt() {
        provisionGuest()
        portFree = false

        val report = start()

        val record = readRecord(disk, paths)!!
        assertEquals(GuestState.PORT_TAKEN.name, record.stateName)
        assertEquals(false, record.reserved)
        assertEquals(false, record.launched)
        assertEquals(false, record.answered)
        assertEquals(report.state, record.state)
    }

    // ---- a guest that cannot be launched ----------------------------------------------------------------

    @Test
    fun aDebianWithNoLampIsNotStartedAndSaysTheInstallIsNotAutomatic() {
        provisionRootfs()

        val report = start()

        assertEquals(GuestState.APACHE_NOT_ANSWERING, report.state)
        // Nothing was launched at all, and that is `GuestPackages.enableApi` being careful rather
        // than lucky: with the drop-in absent, `a2enconf` would exit non-zero, and a failed step here
        // would be a 57 MB `apt` being sent after it on the next start.
        assertEquals(emptyList<String>(), ran)
        assertFalse(report.serving)
        assertTrue(report.lines.any { it.contains("is not in the Debian") })
        assertTrue(report.lines.any { it.contains("'omp provision' prints the cost and asks") })
    }

    @Test
    fun aGuestThatWouldNotLaunchIsReportedWithTheGuestsOwnWords() {
        provisionGuest()
        apacheStarts = false
        apacheSaid = "proot: it could not be started: /data/app/.../libproot.so (Permission denied)"

        val report = start()

        assertEquals(GuestState.APACHE_NOT_ANSWERING, report.state)
        // "Launched" is the field that means a process exists, and none does: the refusal is the
        // whole of what the guest had to say and it is the reason the page is not being served.
        assertFalse(report.launched)
        assertEquals(
            "proot: it could not be started: /data/app/.../libproot.so (Permission denied)",
            report.said,
        )
        assertTrue(report.lines.any { it.contains("Permission denied") })
    }

    // ---- starting is not being up ------------------------------------------------------------------------

    @Test
    fun aGuestThatWasLaunchedAndAnsweredNothingIsNotServing() {
        provisionGuest()
        serving = false

        val report = start()

        // The distinction the whole origin decision rests on. A process exists; a server does not.
        assertTrue("the process was started", report.launched)
        assertFalse("nothing came back, so nothing is serving", report.answered)
        assertEquals(GuestState.APACHE_NOT_ANSWERING, report.state)
        assertFalse(report.serving)
        assertEquals(listOf(GuestWeb.RESERVED_PORT), portsProbed)
    }

    @Test
    fun aGuestThatWasLaunchedAndAnsweredIsServedOnlyAfterThePageCameBack() {
        provisionGuest()
        serving = true

        val report = start()

        assertTrue(report.answered)
        assertEquals(GuestState.UP, report.state)
        assertTrue(report.serving)
    }

    @Test
    fun theGuestsPortIsProbedAfterTheProcessIsLaunchedAndNotBefore() {
        provisionGuest()
        serving = true

        start()

        // Asking first would be a question about a port nothing is listening on, and answering it
        // before the start is how a guest that was never launched comes to be reported as serving.
        assertEquals(listOf("/usr/sbin/a2enconf omp-guest", "apache", "omp update"), ran)
        assertEquals(listOf(GuestWeb.RESERVED_PORT), portsAsked)
        assertEquals(listOf(GuestWeb.RESERVED_PORT), portsProbed)
    }

    @Test
    fun theCommandLaunchedIsApachesOwnWithProotsFlagsInFront() {
        provisionGuest()
        serving = true

        start()

        val argv = apacheArgv.single()
        // The tail is what the guest runs and it is pinned here because this is the only place in the
        // project where it is assembled; `ProotCommandTest` pins the flags that come before it.
        assertEquals(GuestWeb.APACHE_COMMAND, argv.takeLast(GuestWeb.APACHE_COMMAND.size))
        assertEquals("/data/app/~~kQ==/com.omp.terminal-1==/lib/arm64/libproot.so", argv.first())
        assertTrue(argv.contains("-r"))
    }

    // ---- the boot contract: a failed update leaves the web half up -------------------------------------------

    @Test
    fun aGuestWhoseUpdateFailedStillStartsApacheAndStillServesThePage() {
        // The contract `omp.vm.guestapi.AgentUpdate` states and `omp.vm.provision.Boot` enforces:
        // `omp update && omp` stops at the first half, and the first half is the *agent*. Apache, its
        // PHP API, its filesystem and its `web` command are none of that agent's business, and a
        // start that took the `&&` as a reason to leave the guest down would have turned a failed
        // update into a device with nothing on it.
        provisionGuest()
        serving = true
        update = UpdateRun(
            1,
            "Current version: 18.3.5\nFailed to check for updates: TypeError: Unable to connect. " +
                "Is the computer able to connect to the url?\n",
        )

        val report = start()

        assertEquals(
            "apache2 was started and left running, and the update ran after it",
            listOf("/usr/sbin/a2enconf omp-guest", "apache", "omp update"),
            ran,
        )
        assertTrue(report.launched)
        assertTrue(report.answered)
        assertEquals(GuestState.AGENT_UPDATE_FAILED, report.state)
        // The origin is still the guest: this is a real page out of a real Debian, and trading it for
        // this app's Kotlin server on the strength of a version number would be the wrong answer.
        assertTrue(report.serving)
        assertEquals(UpdateOutcome.FAILED, report.update)
    }

    @Test
    fun aGuestWhoseUpdateWasStoppedStillServesAndSaysTheOutcomeByName() {
        provisionGuest()
        serving = true
        update = UpdateRun(0, "Current version: 18.3.5\n", stopped = true)

        val report = start()

        assertEquals(GuestState.AGENT_UPDATE_FAILED, report.state)
        assertEquals(UpdateOutcome.TIMED_OUT, report.update)
        assertTrue(report.serving)
    }

    @Test
    fun aGuestWithNoAgentIsStillStartedBecauseTheAgentIsNotTheWebHalf() {
        // A 32-bit device has a Debian and no agent and always will have one. Apache, its PHP API and
        // its document root do not depend on the agent binary existing, and the origin is then the
        // app's own — but only because `ProvisionState.realAgentInstalled` is false, which is a fact
        // about the disk and not a second opinion about the guest.
        provisionRootfs()
        File(paths.rootfsDir, ProvisionPaths.GUEST_MARKER).writeText("#omp-guest/v1\n")
        serving = true

        val report = start()

        assertEquals(GuestState.AGENT_UPDATE_FAILED, report.state)
        assertEquals(UpdateOutcome.NO_AGENT, report.update)
        assertTrue(report.launched)
        assertTrue(report.serving)
        assertFalse("the update was never launched", ran.contains("omp update"))
    }

    // ---- what the run leaves behind ---------------------------------------------------------------------------

    @Test
    fun aRunWritesTheRecordTheDoctorReadsAndEveryFieldInIt() {
        provisionGuest()
        serving = true

        val report = start()

        val record = readRecord(disk, paths)!!
        assertEquals(GuestState.UP.name, record.stateName)
        assertEquals(GuestState.UP, record.state)
        assertEquals(GuestWeb.RESERVED_PORT, record.port)
        assertEquals(true, record.reserved)
        assertEquals(true, record.launched)
        assertEquals(true, record.answered)
        assertEquals(UpdateOutcome.ALREADY_CURRENT.name, record.update)
        assertEquals("18.3.5", record.version)
        assertEquals(report.at, record.at)
    }

    @Test
    fun aRunThatCannotWriteItsRecordSaysSoOnItsOwnReport() {
        // The read-only filesystem is a real case and the failure has to be on the report the caller
        // logs, not swallowed: a report that claims to be on disk and is not would leave `omp doctor`
        // reporting a state from the previous run with nothing to say the run happened.
        val denied = object : omp.shell.fs.Vfs by disk {
            override fun writeBytes(path: String, bytes: ByteArray) {
                throw omp.shell.fs.FsException(omp.shell.fs.FsErrno.PERM_DENIED, path)
            }
        }
        provisionGuest()
        serving = true

        val report = GuestStart(
            paths, denied, proot, packages(), server,
            GuestWeb.PortBinder { true }, GuestWeb.WebProbe { true }, updater(), now = { 0L },
        ).start()

        assertEquals(GuestState.UP, report.state)
        assertTrue(report.lines.last().startsWith("this report could not be written to"))
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun start(): GuestStartReport = GuestStart(
        paths = paths,
        vfs = disk,
        proot = proot,
        packages = packages(),
        server = server,
        binder = { port ->
            portsAsked += port
            portFree
        },
        probe = { port ->
            portsProbed += port
            serving
        },
        update = updater(),
        now = { 1_789_000_000_000L },
    ).start()

    private fun packages() = GuestPackages(paths, proot, launcher, disk)

    private fun updater() = AgentUpdate(paths, disk, proot, transport, now = { 1_789_000_000_000L })

    private fun provisionRootfs() {
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsDir, ProvisionPaths.ROOTFS_MARKER).writeText("omp-provisioned test\n")
    }

    /** A Debian with its agent and its own `apt` install, which is what a start needs to go further. */
    private fun provisionGuest() {
        provisionRootfs()
        File(paths.agentDir).mkdirs()
        File(paths.agentBinary).writeBytes(ByteArray(1_024))
        File(paths.rootfsDir, ProvisionPaths.GUEST_MARKER).writeText("#omp-guest/v1\n")
        // The drop-in `a2enconf` links in, which `GuestPackages.enableApi` refuses without launching
        // anything when it is absent — a different case, and it has its own test above.
        File(paths.rootfsDir + paths.guestConf).parentFile!!.mkdirs()
        File(paths.rootfsDir + paths.guestConf).writeText("# written by omp.vm.guestapi.GuestApiTree\n")
    }
}
