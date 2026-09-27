package omp.vm.provision

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The guest's `apt` sequence, as data, and the state machine around it.
 *
 * **There is no `apt` in this project and there is not going to be one.** The launcher here is a
 * fake that records what it was asked to run and answers with a status, so what these tests pin is
 * the *decision* — which command, in which order, with which environment, and when not to run any
 * of it — and not the fact that installing LAMP in a Debian works. Nobody knows whether it works.
 * The first device that finds out is a phone, and its report is a [ProotLauncher]'s exit status.
 */
class GuestPackagesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var paths: ProvisionPaths
    private lateinit var vfs: Vfs
    private lateinit var proot: ProotCommand

    /** Every `(argv, env)` the guest was asked for, in order. */
    private val runs = ArrayList<Pair<List<String>, Map<String, String>>>()

    /** The step number (1-based) to start answering non-zero at, or -1 for a clean run. */
    private var failFrom: Int = -1

    private val guest = ArtifactManifest.of(Abi.ARM64).guest

    private val launcher = ProotLauncher { argv, env, _ ->
        runs += argv to env
        if (failFrom in 1..runs.size) 100 + runs.size else 0
    }

    @Before
    fun setUp() {
        paths = ProvisionPaths(folder.newFolder("files").path, null, folder.newFolder("work").path)
        vfs = RealVfs()
        // A Debian, in the only way this package can tell there is one: the mark the unpacker
        // leaves inside the tree it unpacked. Nothing here needs a real rootfs and nothing here
        // would behave differently if it had one.
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsMarker).writeText("omp-provisioned test\n")
        proot = ProotCommand(
            prootPath = "/lib/omp/proot",
            rootfs = paths.rootfsDir,
            host = Abi.ARM64,
            guest = Abi.ARM64,
            dataDir = "/data/files",
            visibleDir = "/sdcard/Documents/omp",
            agentDir = paths.agentDir,
        )
    }

    // ---- the sequence ---------------------------------------------------------------------------

    @Test
    fun theSequenceIsUpdateThenInstallThenEnableAndNothingElse() {
        val steps = packages().steps(guest)

        assertEquals(3, steps.size)
        assertEquals(listOf("/usr/bin/apt-get", "update"), steps[0].command)
        assertEquals(listOf("/usr/bin/apt-get", "install", "-y") + guest.packages, steps[1].command)
        assertEquals(listOf("/usr/sbin/a2enmod", "php8.4"), steps[2].command)
        // The package set is the manifest's own, in the manifest's order: this step does not keep a
        // second list of what a Debian is supposed to have in it.
        assertEquals(ArtifactManifest.LAMP_PACKAGES, guest.packages)
    }

    @Test
    fun theVectorThatWouldRunIsProotsOwnWithTheCommandAtTheEnd() {
        val argv = proot.argv(packages().steps(guest)[1].command)

        assertEquals("/lib/omp/proot", argv.first())
        assertTrue(argv.contains(paths.rootfsDir))
        assertEquals(
            listOf(
                "/usr/bin/apt-get",
                "install",
                "-y",
                "apache2-bin",
                "libapache2-mod-php8.4",
                "php8.4-cli",
                "mariadb-server",
            ),
            argv.takeLast(7),
        )
        // --no-install-recommends is absent on purpose: the 385,689 KiB the user is asked to agree
        // to was measured the way apt installs by default, and a flag that makes the real cost
        // smaller than the number on the screen is a lie with a flag in front of it.
        assertFalse(argv.contains("--no-install-recommends"))
    }

    @Test
    fun theEnvironmentIsTheGuestsOwnPlusTheTwoThatStopAPromptHanging() {
        val env = packages().env()

        assertEquals(proot.env()["PATH"], env["PATH"])
        assertEquals(proot.env()["HOME"], env["HOME"])
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
        assertEquals("true", env["DEBCONF_NONINTERACTIVE_SEEN"])
    }

    // ---- the three answers ----------------------------------------------------------------------

    @Test
    fun aGuestWithNoMarkRunsTheWholeSequenceAndThenIsMarked() {
        val report = packages().install(guest)

        assertEquals(report.lines.toString(), GuestOutcome.INSTALLED, report.outcome)
        assertEquals(3, report.stepsRun)
        assertEquals(3, runs.size)
        val mark = File(paths.guestMarker)
        assertTrue(mark.path, mark.isFile)
        // The mark is a file a user can open: it names the packages and both measured figures.
        val text = mark.readText()
        assertTrue(text, text.contains("LAMP apache2-bin,libapache2-mod-php8.4,php8.4-cli,mariadb-server"))
        assertTrue(text, text.contains("57211704"))
        assertTrue(text, text.contains("385689"))
        assertTrue(report.lines.any { it.contains("LAMP is installed in the Debian") })
    }

    @Test
    fun aGuestThatAlreadyHasThePackagesRunsNothingAtAll() {
        assertEquals(GuestOutcome.INSTALLED, packages().install(guest).outcome)
        val before = File(paths.guestMarker).readText()

        // A second caller is a boot after a boot: a new object, nothing remembered, and the mark
        // on the disk is the whole of what it knows.
        val report = packages().install(guest)

        assertEquals(report.lines.toString(), GuestOutcome.ALREADY_INSTALLED, report.outcome)
        assertEquals("57 MB of mobile data may not be spent twice", 3, runs.size)
        assertEquals(0, report.stepsRun)
        assertEquals(before, File(paths.guestMarker).readText())
    }

    @Test
    fun aStepThatFailsLeavesNoMarkAndTheNextRunStartsFromTheTop() {
        failFrom = 2

        val failed = packages().install(guest)

        assertEquals(failed.lines.toString(), GuestOutcome.FAILED, failed.outcome)
        assertEquals(2, failed.stepsRun)
        val line = failed.lines.first { it.contains("exited with status") }
        assertTrue(line, line.contains("apt-get install -y apache2-bin"))
        assertTrue(line, line.contains("status 102"))
        assertFalse(
            "a mark here is a claim about an install that stopped half way",
            File(paths.guestMarker).exists(),
        )

        failFrom = -1
        val retried = packages().install(guest)

        assertEquals(retried.lines.toString(), GuestOutcome.INSTALLED, retried.outcome)
        // The two that ran before, and all three again: no guess about which half was already
        // unpacked — `apt` is the thing that knows that, and this class only knows whether the
        // mark is there.
        assertEquals(5, runs.size)
    }

    @Test
    fun aGuestWithNoDebianIsNotAskedToInstallAnything() {
        File(paths.rootfsMarker).delete()

        val report = packages().install(guest)

        assertEquals(report.lines.toString(), GuestOutcome.FAILED, report.outcome)
        assertEquals(0, report.stepsRun)
        assertTrue("57 MB may not be spent finding out there is no Debian", runs.isEmpty())
        assertTrue(report.lines.any { it.contains("no unpacked Debian") })
    }

    @Test
    fun aMarkThisClassCannotWriteIsAFailureAndNotASilentSuccess() {
        val full = object : Vfs by vfs {
            override fun writeBytes(path: String, bytes: ByteArray) {
                throw FsException(FsErrno.NO_SPACE, path)
            }
        }

        val report = GuestPackages(paths, proot, launcher, full).install(guest)

        assertEquals(report.lines.toString(), GuestOutcome.FAILED, report.outcome)
        assertEquals(3, report.stepsRun)
        assertTrue(report.lines.any { it.contains("the next run will install them again") })
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun packages() = GuestPackages(paths, proot, launcher, vfs)
}
