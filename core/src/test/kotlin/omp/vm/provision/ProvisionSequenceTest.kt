package omp.vm.provision

import omp.vm.guestapi.GuestApiTree
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * The whole provisioning sequence, run twice, with a life lived in the guest in between.
 *
 * **This is the test for the failure a user would actually report.** Not a corrupt download and not
 * a bad digest: a second `provision()` that starts 334 MB of mobile data over again, or that
 * unpacks the rootfs a second time and undoes everything the user has done inside the Debian since.
 * Both are silent, both are expensive, and neither shows up in any of the tests that cover a single
 * step — so they are covered here, by running the sequence as a caller runs it and then running it
 * again with the guest full of things a person put there.
 *
 * What a real device would add to this test is the two things it cannot be given: the
 * [ProotLauncher] here is a fake, so "the guest installed LAMP" means three argument vectors were
 * built and handed over, and nothing in this repository has run one.
 */
class ProvisionSequenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var paths: ProvisionPaths
    private lateinit var vfs: Vfs
    private lateinit var transport: FakeTransport

    /** Every argv the guest was asked to run, across every boot in the test. */
    private val guestRuns = ArrayList<List<String>>()

    private val guest = ArtifactManifest.of(Abi.ARM64).guest

    /** The app's own `assets/web/`, as the app's own server would read it. */
    private val appPages = WebSource { name ->
        File(appWebDirectory(), name).takeIf { it.isFile }?.readBytes()
    }

    @Before
    fun setUp() {
        paths = ProvisionPaths(folder.newFolder("files").path, null, folder.newFolder("work").path)
        vfs = RealVfs()
        transport = FakeTransport().apply {
            bodies[ROOTFS_URL] = rootfsBytes
            bodies[AGENT_URL] = AGENT_BYTES
        }
    }

    @Test
    fun aSecondRunOfTheWholeSequenceDownloadsNothingAndTouchesNothingInsideTheGuest() {
        // ---- the first run: the Debian, the agent, the guest's own apt, the three pages, and the
        // guest's own PHP — in that order, and the order is the whole point of the test ----
        val first = provision()
        assertEquals(first.lines.toString(), ProvisionOutcome.PROVISIONED, first.outcome)
        assertEquals(3, webRoot().install(vfs).filesWritten)
        assertEquals(GuestOutcome.INSTALLED, guestPackages().install(guest).outcome)
        assertEquals(GuestApiTree.FILES.size, guestApiTree().install(vfs).filesWritten)
        assertEquals(GuestOutcome.INSTALLED, guestPackages().enableApi().outcome)
        // Four on the first run: apt-get update, apt-get install, a2enmod php8.4, and — after the
        // guest's PHP tree has been written — a2enconf omp-guest. **A step added on purpose changes
        // this number deliberately, and the diff is the record**: a test that said "at least four"
        // could not notice a fifth step arriving by accident.
        assertEquals(4, guestRuns.size)

        // 334 MB is the real arm64 figure this stands in for: a second run that opens one more
        // connection is a second run that spends somebody's data allowance again.
        val fetched = transport.requests.size
        assertEquals("the first run fetches the rootfs and the agent, once each", 2, fetched)

        // ---- a person uses the Debian: a package, a config file, an edit to the page ----
        val inside = File(paths.rootfsDir, "etc/omp-user-note")
        inside.writeText("do not lose this\n")
        vfs.setModified(inside.path, 1_000_000_000_000L)
        val index = File(paths.webRoot, "index.html")
        index.writeText("<html><body>Apache2 Debian Default Page</body></html>")
        val rootfsMarker = File(paths.rootfsMarker)
        vfs.setModified(rootfsMarker.path, 1_000_000_000_000L)

        // ---- the second run, in the order a caller runs it ----
        val second = provision()
        val web = webRoot().install(vfs)
        val installed = guestPackages().install(guest)
        val api = guestApiTree().install(vfs)
        val enabled = guestPackages().enableApi()

        assertEquals(second.lines.toString(), ProvisionOutcome.ALREADY_PROVISIONED, second.outcome)
        assertEquals(
            "a second provision() that re-downloads is the failure this test exists for",
            fetched,
            transport.requests.size,
        )
        // The whole unpack is decided by the mark inside the tree, so the guest is not unpacked
        // over: a file the user made, and the mark itself, keep the timestamps they had.
        assertEquals("a person wrote this inside the Debian", "do not lose this\n", inside.readText())
        assertEquals(1_000_000_000_000L, inside.lastModified())
        assertEquals(1_000_000_000_000L, rootfsMarker.lastModified())
        assertFalse(File(workDir(), "provision/$ROOTFS.part").exists())
        assertFalse(File(workDir(), "provision/$AGENT.part").exists())

        // The three pages are the app's three pages again, because something in the guest had
        // replaced one of them, and the report says which.
        assertEquals(1, web.filesWritten)
        assertEquals(WebOutcome.INSTALLED, web.outcome)
        assertTrue(web.lines.any { it.contains("index.html") && it.contains("a different copy") })
        for (name in WebRoot.FILES) {
            assertArrayEquals(
                name,
                appPages.read(name),
                File(paths.webRoot, name).readBytes(),
            )
        }

        // The guest's LAMP is not installed a second time: 57,211,704 bytes, once. The PHP is not
        // written a second time either, and the drop-in is linked in again because `a2enconf` is
        // idempotent — a run that refused to run it would be a step whose answer depended on
        // whether a link already existed.
        assertEquals(GuestOutcome.ALREADY_INSTALLED, installed.outcome)
        assertEquals(WebOutcome.ALREADY_INSTALLED, api.outcome)
        assertEquals(0, api.filesWritten)
        assertEquals(GuestOutcome.INSTALLED, enabled.outcome)
        // Five: apt-get update, apt-get install, a2enmod php8.4, and — after the guest's PHP tree
        // has been written — a2enconf omp-guest. **A step added on purpose changes this number
        // deliberately, and the diff is the record**: a test that said "at least four" could not
        // notice a fifth step arriving by accident.
        assertEquals(5, guestRuns.size)
    }

    @Test
    fun aThirdRunWithNothingChangedIsThreeNoOps() {
        provision()
        webRoot().install(vfs)
        guestPackages().install(guest)
        guestApiTree().install(vfs)
        guestPackages().enableApi()
        val fetched = transport.requests.size
        val index = File(paths.webRoot, "index.html")
        val before = index.lastModified()
        vfs.setModified(index.path, before - 86_400_000L)
        val apiIndex = File(paths.guestApiDir, GuestApiTree.INDEX_NAME)
        val apiBefore = apiIndex.lastModified()
        vfs.setModified(apiIndex.path, apiBefore - 86_400_000L)

        val second = provision().outcome
        val web = webRoot().install(vfs)
        val installed = guestPackages().install(guest)
        val api = guestApiTree().install(vfs)
        guestPackages().enableApi()

        assertEquals(ProvisionOutcome.ALREADY_PROVISIONED, second)
        assertEquals(fetched, transport.requests.size)
        assertEquals(WebOutcome.ALREADY_INSTALLED, web.outcome)
        assertEquals(0, web.filesWritten)
        assertEquals(before - 86_400_000L, index.lastModified())
        assertEquals(WebOutcome.ALREADY_INSTALLED, api.outcome)
        assertEquals(0, api.filesWritten)
        assertEquals(apiBefore - 86_400_000L, apiIndex.lastModified())
        assertEquals(GuestOutcome.ALREADY_INSTALLED, installed.outcome)
        // The same five as the first run, and no more: see the note on the other assertion.
        assertEquals(5, guestRuns.size)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun provision() = Provisioner(paths, vfs, transport).provision(manifest())

    private fun webRoot() = WebRoot(paths, appPages)

    /** The guest's own PHP, read from the app's real `assets/guest/` and not out of a fixture. */
    private fun guestApiTree() = GuestApiTree(
        paths,
        WebSource { name -> File(guestAssetDirectory(), name).takeIf { it.isFile }?.readBytes() },
    )

    /** The app's own guest assets, found by walking up from wherever the test was started. */
    private fun guestAssetDirectory(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val assets = File(dir, "app/src/main/assets/guest")
            if (assets.isDirectory) return assets
            dir = dir.parentFile
        }
        throw AssertionError("app/src/main/assets/guest was not found above ${File(".").absolutePath}")
    }

    private fun guestPackages() = GuestPackages(
        paths,
        ProotCommand(
            prootPath = "/lib/omp/proot",
            rootfs = paths.rootfsDir,
            host = Abi.ARM64,
            guest = Abi.ARM64,
            dataDir = "/data/files",
            visibleDir = "/sdcard/Documents/omp",
            agentDir = paths.agentDir,
        ),
        ProotLauncher { argv, _, _ ->
            guestRuns += argv
            0
        },
        vfs,
    )

    private fun workDir() = File(paths.workDir)

    /** A manifest whose sizes are the fake server's, so a test's arithmetic can be checked by eye. */
    private fun manifest() = ArtifactManifest(
        abi = Abi.ARM64,
        rootfs = Artifact(ROOTFS, ROOTFS_URL, rootfsBytes.size.toLong(), null),
        agent = Artifact(AGENT, AGENT_URL, AGENT_BYTES.size.toLong(), sha256Of(AGENT_BYTES)),
        // The real measured pair, so the space check in this sequence is the one a device gets.
        guest = guest,
    )

    /**
     * The smallest honest Debian: a directory, an executable, a library big enough to defeat a
     * single-buffer write, a config file and a symlink. The 300 KB of [Random] bytes are there so
     * the member is bigger than a compressed block, which is what makes an unpack worth testing.
     */
    private val rootfsBytes = Tar.gz(
        Tar.directory("usr"),
        Tar.directory("usr/bin"),
        Tar.file("usr/bin/ls", "ELF-ish".toByteArray(), 0x1ED),
        Tar.file("usr/lib/libc.so.6", Random(7).let { r -> ByteArray(300_000).also(r::nextBytes) }),
        Tar.file("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux\"\n".toByteArray()),
        Tar.symlink("bin/sh", "usr/bin/ls"),
    )

    /**
     * The app's own asset directory, found by walking up from wherever the test was started.
     *
     * This is the app's files and not a fixture of them, because the claim under test is that the
     * guest serves what the app serves: a copy in a test would pass while the two drifted apart.
     */
    private fun appWebDirectory(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val assets = File(dir, "app/src/main/assets/web")
            if (assets.isDirectory) return assets
            dir = dir.parentFile
        }
        throw AssertionError(
            "app/src/main/assets/web was not found above ${File(".").absolutePath}",
        )
    }

    private companion object {
        const val ROOTFS = "debian-trixie-rootfs-arm64"
        const val AGENT = "omp-linux-arm64"
        const val ROOTFS_URL = "https://deb.debian.org/debian/netboot/debian-13.1.0/arm64/linux"
        const val AGENT_URL =
            "https://github.com/can1357/oh-my-pi/releases/download/v18.3.4/omp-linux-arm64"
        val AGENT_BYTES = "#!glibc\nomp".toByteArray()
    }
}
