package omp.vm.provision

import omp.shell.fs.RealVfs
import omp.shell.fs.VDiskUsage
import omp.shell.fs.Vfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * The download-and-unpack path, judged by `java.io.File` after every claim it makes.
 *
 * A [Provisioner] returns a report, and a report is a thing a class can write whether or not
 * anything happened, so the assertions here are about the disk: the bytes that are on it, the
 * bytes that are not, the file that was deleted and the file that was refused. The two directories
 * mirror the phone's — a target directory for the payload and a separate exec directory for a
 * native helper — because that split is the constraint the whole layout exists for, and the
 * payload lands in the first while nothing in this layer ever writes to the second.
 *
 * The manifests here are built by hand with small sizes so a test's arithmetic can be checked by
 * eye; [ArtifactManifestTest] is where the real measured numbers are pinned.
 */
class ProvisionerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var targetDir: File
    private lateinit var execDir: File
    private lateinit var workDir: File
    private lateinit var transport: FakeTransport
    private lateinit var paths: ProvisionPaths

    private val agentBytes = "#!glibc\nomp".toByteArray()

    /**
     * A rootfs with a member too big to arrive in one write.
     *
     * The 300 KB of [Random] bytes are there because gzip would squash anything patterned down to
     * nothing, and a body smaller than the 64 KiB buffer is written in a single call — which is the
     * one call a cancel cannot land on. Everything else in the archive is the smallest honest
     * Debian: a directory, an executable, a library, a conffile and a symlink.
     */
    private val rootfsBytes = Tar.gz(
        Tar.directory("usr"),
        Tar.directory("usr/bin"),
        Tar.file("usr/bin/ls", "ELF-ish".toByteArray(), 0x1ED),
        Tar.file("usr/lib/libc.so.6", Random(7).let { random -> ByteArray(300_000).also(random::nextBytes) }),
        Tar.file("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux\"\n".toByteArray()),
        Tar.symlink("bin/sh", "usr/bin/ls"),
    )

    @Before
    fun setUp() {
        targetDir = folder.newFolder("files")
        workDir = folder.newFolder("downloads")
        transport = FakeTransport()
        // The exec directory is read-only and is only ever named, never written: the folder here
        // stands in for the one the package manager populates from the APK.
        execDir = folder.newFolder("lib")
        paths = ProvisionPaths(targetDir.path, execDir.path, workDir.path)
        transport.bodies[ROOTFS_URL] = rootfsBytes
        transport.bodies[AGENT_URL] = agentBytes
    }

    // ---- the whole path, once ------------------------------------------------------------

    @Test
    fun aFullRunUnpacksTheRootfsAndInstallsTheAgent() {
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.PROVISIONED, report.outcome)
        val ls = File(targetDir, "omp/rootfs/usr/bin/ls")
        assertTrue(ls.path, ls.isFile)
        assertEquals("ELF-ish", ls.readText())
        // The mode came out of the tar header and not out of the app's umask: a Debian whose
        // binaries are 0644 cannot be exec'd by the loader inside the rootfs.
        assertTrue(ls.path, ls.canExecute())
        val sh = File(targetDir, "omp/rootfs/bin/sh")
        assertTrue(sh.path, Files.isSymbolicLink(sh.toPath()))
        assertEquals("usr/bin/ls", Files.readSymbolicLink(sh.toPath()).toString())
        assertEquals(
            "PRETTY_NAME=\"Debian GNU/Linux\"",
            File(targetDir, "omp/rootfs/etc/os-release").readText().trim(),
        )
        val marker = File(targetDir, "omp/rootfs/${ProvisionPaths.ROOTFS_MARKER}")
        assertTrue(marker.path, marker.isFile)
        val agent = File(targetDir, "omp/bin/omp")
        assertTrue(agent.path, agent.isFile)
        assertEquals(agentBytes.size.toLong(), agent.length())
        assertTrue(agent.path, agent.canExecute())
        // A partial file that survived a successful run is a file a later run would have to trust.
        assertFalse(partial(ROOTFS).exists())
        assertFalse(partial(AGENT).exists())
        // And the read-only directory is still empty: it is the package manager's, this layer only
        // ever names it, and a single file here would be a download that fails on a real device
        // with permission denied rather than a test that fails.
        assertEquals(
            "nothing may be written into the exec directory",
            emptyList<String>(),
            execDir.list()?.toList() ?: emptyList<String>(),
        )
    }

    @Test
    fun theSecondCallOnAProvisionedDeviceDoesNothing() {
        val first = provisioner().provision(manifest())
        assertEquals(first.lines.toString(), ProvisionOutcome.PROVISIONED, first.outcome)
        val requestsAfterFirst = transport.requests.size

        val second = provisioner().provision(manifest())

        assertEquals(second.lines.toString(), ProvisionOutcome.ALREADY_PROVISIONED, second.outcome)
        assertEquals("nothing may be fetched twice", requestsAfterFirst, transport.requests.size)
        assertTrue(first.lines.none { it.contains("already provisioned") })
        assertTrue(second.lines.any { it.contains("already provisioned") })
        assertTrue(File(targetDir, "omp/bin/omp").isFile)
    }

    @Test
    fun aLostStateFileDoesNotBringBackTwoHundredMegabytes() {
        provisioner().provision(manifest())
        val requestsAfterFirst = transport.requests.size
        val stateFile = File(workDir, "provision/state")
        assertTrue(stateFile.path, stateFile.isFile)
        // The state file is the record of a download in progress, not the record of an install: the
        // install is the marker inside the rootfs and the binary itself, and neither is in here.
        assertTrue(stateFile.delete())
        assertFalse(stateFile.exists())

        val again = provisioner().provision(manifest())

        assertEquals(again.lines.toString(), ProvisionOutcome.ALREADY_PROVISIONED, again.outcome)
        assertEquals(requestsAfterFirst, transport.requests.size)
        assertTrue(File(targetDir, "omp/bin/omp").isFile)
    }

    // ---- resuming ------------------------------------------------------------------------

    @Test
    fun aDownloadThatStoppedResumesAtTheOffsetItStopped() {
        transport.stopAfter = 400
        val first = provisioner().provision(manifest())

        assertEquals(first.lines.toString(), ProvisionOutcome.FAILED, first.outcome)
        assertTrue(first.lines.any { it.contains("is kept and the next run continues") })
        val held = partial(ROOTFS)
        assertTrue(held.path, held.isFile)
        assertEquals(400L, held.length())
        // The state file is how a person reads what happened, and it must never claim more than
        // the file on disk holds.
        assertTrue(File(workDir, "provision/state").readText().contains("400 partial"))

        transport.stopAfter = -1
        val second = provisioner().provision(manifest())

        assertEquals(second.lines.toString(), ProvisionOutcome.PROVISIONED, second.outcome)
        assertEquals(ROOTFS_URL, transport.requests[1].first)
        assertEquals("the resume must ask the server for the rest", 400L, transport.requests[1].second)
        assertFalse(held.exists())
        assertTrue(File(targetDir, "omp/rootfs/usr/bin/ls").isFile)
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsTheFileOver() {
        // A half-written rootfs and no marker: the state a run interrupted mid-download leaves.
        File(targetDir, "omp/rootfs/${ProvisionPaths.ROOTFS_MARKER}").delete()
        File(workDir, "provision").mkdirs()
        File(workDir, "provision/$ROOTFS.part").writeBytes(rootfsBytes.copyOfRange(0, 400))
        transport.ignoreRange = true

        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.PROVISIONED, report.outcome)
        assertTrue(report.lines.any { it.contains("sent the whole file instead of the range") })
        // A body appended to a half file is the right size and the wrong bytes; the rootfs on disk
        // is the only proof that this was not what happened.
        assertEquals("ELF-ish", File(targetDir, "omp/rootfs/usr/bin/ls").readText())
        assertTrue(File(targetDir, "omp/rootfs/usr/lib/libc.so.6").isFile)
    }

    @Test
    fun cancellingKeepsWhatArrivedAndTheNextRunContinuesIt() {
        var stop = false
        val report = provisioner(
            onProgress = { if (it.receivedBytes > 0) stop = true },
            cancelled = { stop },
        ).provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.CANCELLED, report.outcome)
        val held = partial(ROOTFS)
        assertTrue("a cancelled download is a resumable one", held.isFile)
        assertTrue(held.length() > 0)
        assertTrue(held.length() < rootfsBytes.size)
        assertTrue(report.lines.any { it.contains("the next run continues from there") })
        assertFalse(File(targetDir, "omp/rootfs/${ProvisionPaths.ROOTFS_MARKER}").exists())
        val heldBytes = held.length()

        val second = provisioner().provision(manifest())

        assertEquals(second.lines.toString(), ProvisionOutcome.PROVISIONED, second.outcome)
        assertEquals(heldBytes, transport.requests.last { it.first == ROOTFS_URL }.second)
        assertTrue(File(targetDir, "omp/bin/omp").isFile)
    }

    @Test
    fun aCancelBeforeTheFirstByteMovesNothing() {
        val report = provisioner(cancelled = { true }).provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.CANCELLED, report.outcome)
        assertTrue(transport.requests.isEmpty())
        assertFalse(partial(ROOTFS).exists())
        assertTrue(report.lines.any { it.contains("before a byte") })
    }

    // ---- verification --------------------------------------------------------------------

    @Test
    fun aChecksumMismatchDeletesThePartialAndSaysWhichHash() {
        val wrong = "0".repeat(64)
        val report = provisioner().provision(manifest(agentSha = wrong))

        assertEquals(report.lines.toString(), ProvisionOutcome.FAILED, report.outcome)
        assertFalse("known-bad bytes must not survive", partial(AGENT).exists())
        assertFalse(File(targetDir, "omp/bin/omp").exists())
        // The rootfs is a different artifact and was fine: one bad download does not undo the
        // 58 MB that verified.
        assertTrue(File(targetDir, "omp/rootfs/${ProvisionPaths.ROOTFS_MARKER}").isFile)
        val line = report.lines.first { it.contains(wrong) }
        assertTrue(line, line.contains(wrong))
        assertTrue(line, line.contains(sha256Of(agentBytes)))
        assertTrue(line, line.contains("was deleted"))
    }

    @Test
    fun anArtifactWithNoPinnedDigestIsTakenAsItArrived() {
        val report = provisioner().provision(manifest(rootfsSha = null, agentSha = null))

        assertEquals(report.lines.toString(), ProvisionOutcome.PROVISIONED, report.outcome)
        assertTrue(report.lines.any { it.contains("no sha256 is pinned") })
        assertTrue(File(targetDir, "omp/rootfs/usr/bin/ls").isFile)
    }

    @Test
    fun anHttpErrorIsReportedAndNothingIsUnpacked() {
        transport.status = 500
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.FAILED, report.outcome)
        assertTrue(report.lines.any { it.contains("HTTP 500") })
        assertFalse(partial(ROOTFS).exists())
        assertFalse(File(targetDir, "omp/rootfs").exists())
    }

    // ---- the tar, and the paths it may not reach -----------------------------------------

    @Test
    fun aMemberNamedWithDotDotIsRefusedAndNothingIsWrittenOutside() {
        transport.bodies[ROOTFS_URL] = Tar.gz(
            Tar.file("../escape.txt", "gotcha".toByteArray()),
        )
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.FAILED, report.outcome)
        val line = report.lines.first { it.contains("not inside the directory") }
        assertTrue(line, line.contains("../escape.txt"))
        assertFalse(File(targetDir, "escape.txt").exists())
        assertFalse(File(targetDir.parentFile, "escape.txt").exists())
        assertFalse(File(targetDir, "omp/rootfs/usr").exists())
    }

    @Test
    fun aMemberWithAnAbsoluteNameIsRefused() {
        transport.bodies[ROOTFS_URL] = Tar.gz(
            Tar.file("/etc/shadow", "root:*:1:".toByteArray()),
            Tar.file("usr/bin/ls", "never reached".toByteArray()),
        )
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.FAILED, report.outcome)
        assertTrue(report.lines.any { it.contains("/etc/shadow") })
        assertFalse(File(targetDir, "omp/rootfs/etc").exists())
        assertFalse(File(targetDir, "omp/rootfs/usr").exists())
    }

    @Test
    fun aMemberWhoseNameIsTooLongForTheHeaderIsUnpackedUnderItsRealName() {
        val long = "usr/lib/" + "a".repeat(120) + "/libsomething.so.1.2.3"
        transport.bodies[ROOTFS_URL] = Tar.gz(
            Tar.directory("usr/lib"),
            Tar.longName(long, "so".toByteArray()),
            Tar.file("usr/bin/ls", "ELF-ish".toByteArray()),
        )
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.PROVISIONED, report.outcome)
        val installed = File(targetDir, "omp/rootfs/$long")
        assertTrue(installed.path, installed.isFile)
        // And nothing lands under the 100 bytes that fit in the header: a reader that forgot the
        // 'L' member writes a file where nothing is and fails on the member after it.
        val truncated = File(targetDir, "omp/rootfs/${long.take(100)}")
        assertFalse(truncated.path, truncated.exists())
    }

    // ---- the checks that happen before a byte moves --------------------------------------

    @Test
    fun tooLittleRoomIsRefusedBeforeAnyBytesMove() {
        val starved = object : Vfs by RealVfs() {
            override fun diskUsage(path: String) = VDiskUsage(1_000_000_000L, 4_096L)
        }
        val report = provisioner(vfs = starved).provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.REFUSED, report.outcome)
        assertTrue("a refusal is a refusal before the first byte", transport.requests.isEmpty())
        assertFalse(partial(ROOTFS).exists())
        val line = report.lines.first { it.contains("not enough room") }
        assertTrue(line, line.contains("4096 bytes"))
        assertTrue(line, line.contains("Nothing was downloaded"))
    }

    @Test
    fun aConnectionThatBreaksIsReportedAndWhatArrivedIsKept() {
        // A radio that loses the call 100 KB into a 300 KB body: the normal failure on a phone, and
        // the one a resume exists for.
        transport.breakAfter = 100_000
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.FAILED, report.outcome)
        val line = report.lines.first { it.contains("the connection broke after") }
        assertTrue(line, line.contains("connection reset"))
        assertTrue(line, line.contains("What arrived is kept"))
        val held = partial(ROOTFS)
        assertTrue("a broken transfer is a resumable one", held.isFile)
        assertEquals(100_000L, held.length())
        assertTrue(File(workDir, "provision/state").readText().contains("100000 partial"))
    }

    @Test
    fun anArchiveThatIsTheRightLengthAndTheWrongBytesIsRefusedByTheUnpack() {
        // The right size and a gzip stream that ends early: the one thing a length check cannot
        // see, which is why the Debian image has no pinned digest in this build and the report says
        // so rather than calling this "verified".
        transport.corruptTail = 24
        val report = provisioner().provision(manifest())

        assertEquals(report.lines.toString(), ProvisionOutcome.FAILED, report.outcome)
        assertTrue(
            report.lines.toString(),
            report.lines.any { it.contains("the download or the unpack failed") },
        )
        // The agent never starts: the rootfs did not unpack, so there is no userland to run it in.
        assertFalse(File(targetDir, "omp/bin/omp").exists())
        assertFalse(File(targetDir, "omp/rootfs/${ProvisionPaths.ROOTFS_MARKER}").exists())
    }

    @Test
    fun aDeviceWithRoomForTheDownloadButNotForWhatItInstallsIsRefusedBeforeAnyBytesMove() {
        // The whole point of the check: 4 MB free is more than the compressed artifacts need and
        // far less than 4 MB of rootfs plus a 8 MiB installed footprint.
        val manifest = manifest(guestDownload = 1_000L, guestInstalledKib = 8_192L)
        val wire = manifest.totalBytes
        val needed = manifest.requiredBytes
        assertTrue("the test needs the gap to be real", needed > wire)
        val free = wire + (needed - wire) / 2
        val starved = object : Vfs by RealVfs() {
            override fun diskUsage(path: String) = VDiskUsage(1_000_000_000L, free)
        }

        val report = provisioner(vfs = starved).provision(manifest)

        assertEquals(report.lines.toString(), ProvisionOutcome.REFUSED, report.outcome)
        assertTrue("room for the download is not room for the install", transport.requests.isEmpty())
        assertFalse(partial(ROOTFS).exists())
        val line = report.lines.first { it.contains("not enough room") }
        assertTrue(line, line.contains(ArtifactManifest.humanBytes(needed)))
        assertTrue(line, line.contains("the download and what it installs"))
    }

    @Test
    fun theFloorIsExactlyTheFloorAndTheUnpackedRootfsIsStillNotInIt() {
        // The other side of the refusal above, and the reason the floor is a floor: the threshold is
        // the wire bytes plus the measured install and nothing else, so the device with exactly that
        // much room is let through and the rootfs — which is larger unpacked than it arrived — is
        // the thing that runs out of disk. Pinning the boundary is how "no expansion factor is
        // applied" stays a decision instead of quietly becoming a claim: a byte less is refused, a
        // byte more is not, and the refusal quotes the measured sum rather than an estimate.
        val manifest = manifest(guestDownload = 0L, guestInstalledKib = 64L)
        val floor = manifest.requiredBytes
        assertEquals(manifest.totalBytes + 64L * 1024L, floor)
        val oneByteLess = object : Vfs by RealVfs() {
            override fun diskUsage(path: String) = VDiskUsage(1_000_000_000L, floor - 1L)
        }

        val refused = provisioner(vfs = oneByteLess).provision(manifest)

        assertEquals(refused.lines.toString(), ProvisionOutcome.REFUSED, refused.outcome)
        assertTrue("a byte short is a refusal", transport.requests.isEmpty())

        val exact = object : Vfs by RealVfs() {
            override fun diskUsage(path: String) = VDiskUsage(1_000_000_000L, floor)
        }
        val accepted = provisioner(vfs = exact).provision(manifest)

        assertEquals(accepted.lines.toString(), ProvisionOutcome.PROVISIONED, accepted.outcome)
        assertEquals("both artifacts were fetched", 2, transport.requests.size)
        assertTrue(File(targetDir, "omp/rootfs/${ProvisionPaths.ROOTFS_MARKER}").isFile)
    }

    @Test
    fun aDeviceWithNothingToDownloadIsToldWhyWithoutAsking() {
        val report = provisioner().provision(ArtifactManifest.of(Abi.X86))

        assertEquals(report.lines.toString(), ProvisionOutcome.REFUSED, report.outcome)
        assertTrue(transport.requests.isEmpty())
        assertTrue(report.lines.any { it.contains(ArtifactManifest.NO_ROOTFS) })
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun provisioner(
        vfs: Vfs = RealVfs(),
        onProgress: (Progress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) = Provisioner(paths, vfs, transport, onProgress, cancelled)

    private fun partial(name: String) = File(workDir, "provision/$name.part")

    /**
     * A manifest for whatever the fake server is holding, so the sizes agree with the bytes.
     *
     * The real table is pinned in [ArtifactManifestTest]; here the numbers only have to be true
     * about the transport, because a manifest that promises 300 KB to a server with 180 bytes in
     * it is a test that would fail for the right reason and read as the wrong one.
     */
    private fun manifest(
        rootfsSha: String? = null,
        agentSha: String? = sha256Of(agentBytes),
        guestDownload: Long? = 0L,
        guestInstalledKib: Long? = 0L,
    ) = ArtifactManifest(
        abi = Abi.ARM64,
        rootfs = Artifact(
            ROOTFS,
            ROOTFS_URL,
            transport.bodies.getValue(ROOTFS_URL).size.toLong(),
            rootfsSha,
        ),
        agent = Artifact(AGENT, AGENT_URL, agentBytes.size.toLong(), agentSha),
        // Small, and a real second number rather than zero: a manifest whose guest costs nothing
        // cannot be used to show that the space check honours the installed size.
        guest = GuestInstall("LAMP", listOf("apache2-bin"), guestDownload, guestInstalledKib),
    )

    private companion object {
        const val ROOTFS = "debian-trixie-rootfs-arm64"
        const val AGENT = "omp-linux-arm64"
        const val ROOTFS_URL = "https://deb.debian.org/debian/netboot/debian-13.1.0/arm64/linux"
        const val AGENT_URL =
            "https://github.com/can1357/oh-my-pi/releases/download/v18.3.4/omp-linux-arm64"
    }
}
